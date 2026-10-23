package com.suvo.agent.core.memory;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.core.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the prompt inside the model's context window. Older history is folded into a running
 * summary; raw messages are never deleted — the caller marks them "compacted" only after the new
 * summary has been saved, so the summary is never the only copy until it is durable.
 */
public final class ContextCompactor {

    /** Messages eligible for compaction, oldest first, with their database ids. */
    public static final class Plan {
        public final List<ChatMessage> toCompact;
        public final List<ChatMessage> toKeep;
        public final int tokensBefore;

        Plan(List<ChatMessage> toCompact, List<ChatMessage> toKeep, int tokensBefore) {
            this.toCompact = toCompact;
            this.toKeep = toKeep;
            this.tokensBefore = tokensBefore;
        }

        public boolean isEmpty() {
            return toCompact.isEmpty();
        }

        public List<Long> compactedIds() {
            List<Long> ids = new ArrayList<>();
            for (ChatMessage m : toCompact) if (m.localId >= 0) ids.add(m.localId);
            return ids;
        }
    }

    public static final class Result {
        public final String summary;
        public final List<Long> compactedIds;
        public final boolean usedModel;

        Result(String summary, List<Long> compactedIds, boolean usedModel) {
            this.summary = summary;
            this.compactedIds = compactedIds;
            this.usedModel = usedModel;
        }
    }

    /** Fraction of the context window the history may use before compaction starts. */
    public double triggerFraction = 0.70;
    /** After compaction, history should use at most this fraction. */
    public double targetFraction = 0.40;
    /** Always keep at least this many recent messages verbatim. */
    public int minKeepMessages = 6;
    public int summaryMaxWords = 450;

    /**
     * Decides whether to compact. {@code fixedTokens} is the cost of system prompt + memories + tools.
     *
     * @param force compact even below the trigger (used after a context-length error)
     */
    public Plan plan(List<ChatMessage> history, int contextWindow, int fixedTokens, boolean force) {
        int historyTokens = TokenEstimator.estimate(history);
        int budget = Math.max(1024, contextWindow - fixedTokens);
        boolean over = historyTokens > budget * triggerFraction;
        if ((!over && !force) || history.size() <= minKeepMessages) {
            return new Plan(new ArrayList<>(), new ArrayList<>(history), historyTokens);
        }
        int target = (int) (budget * targetFraction);
        if (force) {
            // The provider rejected the request even though our estimate said it fits, so the estimate
            // is too optimistic for this model: shrink relative to the actual history size.
            target = Math.min((int) (target * 0.75), historyTokens / 2);
        }
        // Walk back from the end keeping messages until the target is used.
        int keepFrom = history.size();
        int kept = 0;
        while (keepFrom > 0) {
            int t = TokenEstimator.estimate(history.get(keepFrom - 1));
            if (history.size() - keepFrom >= minKeepMessages && kept + t > target) break;
            kept += t;
            keepFrom--;
        }
        // Never split an assistant tool-call message from its tool results: move the cut forward to
        // the start of a user turn.
        int cut = keepFrom;
        while (cut < history.size() && history.get(cut).role != ChatMessage.Role.USER) cut++;
        if (cut >= history.size()) {
            // No user turn in the kept part (one long autonomous task): cut before an assistant message instead.
            cut = keepFrom;
            while (cut < history.size() && history.get(cut).role == ChatMessage.Role.TOOL) cut++;
            if (cut >= history.size() - 1) cut = keepFrom;
            while (cut > 0 && history.get(cut).role == ChatMessage.Role.TOOL) cut--;
        }
        if (cut <= 0) return new Plan(new ArrayList<>(), new ArrayList<>(history), historyTokens);
        return new Plan(new ArrayList<>(history.subList(0, cut)), new ArrayList<>(history.subList(cut, history.size())), historyTokens);
    }

    /** Produces the new running summary. Falls back to an extractive summary if the model fails. */
    public Result summarize(Plan plan, String previousSummary, String taskProgress, LlmClient llm, CancellationToken cancel) {
        String transcript = transcript(plan.toCompact, 24_000);
        if (llm != null) {
            ChatRequest req = new ChatRequest();
            req.forceNoStream = true;
            req.temperatureOverride = 0.2;
            req.maxTokensOverride = Math.max(400, summaryMaxWords * 2);
            req.messages.add(ChatMessage.system(
                    "You maintain the long-term running summary of a conversation between a user and their personal AI companion. "
                            + "Merge the previous summary with the new transcript into ONE updated summary of at most " + summaryMaxWords + " words. "
                            + "Keep: stable facts and preferences the user stated, decisions, commitments, ongoing projects, what the agent did "
                            + "(with verified outcomes), unresolved items, and exact references (file paths, URLs, names, IDs, numbers). "
                            + "Drop small talk and redundant detail. Never include passwords, API keys or other secrets. "
                            + "Write in English with short headed sections: Facts & preferences / Decisions / Progress / Open items / References. "
                            + "Output only the summary."));
            StringBuilder u = new StringBuilder();
            u.append("PREVIOUS SUMMARY:\n").append(TextUtil.isBlank(previousSummary) ? "(none)" : previousSummary).append("\n\n");
            if (!TextUtil.isBlank(taskProgress)) u.append("CURRENT TASK STATE:\n").append(taskProgress).append("\n\n");
            u.append("NEW TRANSCRIPT TO FOLD IN:\n").append(transcript);
            req.messages.add(ChatMessage.user(u.toString()));
            try {
                ChatResponse r = llm.chat(req, StreamListener.NONE, cancel);
                String s = SecretFilter.redact(r.content.trim());
                if (s.length() > 40) return new Result(s, plan.compactedIds(), true);
            } catch (LlmException ignored) {
                // fall through to extractive summary
            }
        }
        return new Result(extractive(previousSummary, plan.toCompact), plan.compactedIds(), false);
    }

    static String transcript(List<ChatMessage> msgs, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            switch (m.role) {
                case USER:
                    sb.append("User: ").append(TextUtil.truncate(m.contentOrEmpty(), 1500));
                    if (!m.images.isEmpty()) sb.append(" [").append(m.images.size()).append(" image(s)]");
                    break;
                case ASSISTANT:
                    sb.append("Assistant: ").append(TextUtil.truncate(m.contentOrEmpty(), 1500));
                    for (ToolCall c : m.toolCalls) sb.append(" [called ").append(c.name).append(' ').append(TextUtil.truncate(c.argumentsJson, 200)).append(']');
                    break;
                case TOOL:
                    sb.append("Tool ").append(m.name == null ? "" : m.name).append(" result: ").append(TextUtil.truncate(m.contentOrEmpty(), 600));
                    break;
                default:
                    continue;
            }
            sb.append('\n');
        }
        return TextUtil.headTail(SecretFilter.redact(sb.toString()), maxChars);
    }

    static String extractive(String previous, List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtil.isBlank(previous)) sb.append(previous.trim()).append("\n\n");
        sb.append("Earlier conversation (condensed automatically):\n");
        for (ChatMessage m : msgs) {
            if (m.role == ChatMessage.Role.USER && !TextUtil.isBlank(m.content)) {
                sb.append("- User said: ").append(TextUtil.truncate(m.content.replace('\n', ' '), 200)).append('\n');
            } else if (m.role == ChatMessage.Role.ASSISTANT && !m.hasToolCalls() && !TextUtil.isBlank(m.content)) {
                sb.append("- Assistant replied: ").append(TextUtil.truncate(m.content.replace('\n', ' '), 160)).append('\n');
            } else if (m.role == ChatMessage.Role.ASSISTANT) {
                for (ToolCall c : m.toolCalls) sb.append("- Agent used ").append(c.name).append('\n');
            }
        }
        return TextUtil.headTail(SecretFilter.redact(sb.toString()), 6000);
    }
}
