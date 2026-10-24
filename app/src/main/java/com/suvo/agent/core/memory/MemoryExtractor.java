package com.suvo.agent.core.memory;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.core.util.TextUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Automatic memory extraction: asks the model for durable facts from a recent exchange, filters
 * secrets, and deduplicates against existing memories (updating them when information changed).
 */
public final class MemoryExtractor {

    public static final class Action {
        public enum Kind {ADDED, UPDATED, SKIPPED_DUPLICATE, REJECTED}

        public final Kind kind;
        public final MemoryEntry entry;
        public final String reason;

        Action(Kind kind, MemoryEntry entry, String reason) {
            this.kind = kind;
            this.entry = entry;
            this.reason = reason;
        }
    }

    public double duplicateThreshold = 0.82;
    public double updateThreshold = 0.5;
    public int maxPerRun = 6;

    public List<Action> extract(List<ChatMessage> recent, MemoryStore store, LlmClient llm, String source, CancellationToken cancel) {
        List<Action> actions = new ArrayList<>();
        // Attached file contents are not facts about the user (and may hold secrets): leave them out.
        String transcript = com.suvo.agent.core.util.Attachments.withoutContents(ContextCompactor.transcript(recent, 12_000));
        if (transcript.trim().isEmpty()) return actions;
        ChatRequest req = new ChatRequest();
        req.forceNoStream = true;
        req.temperatureOverride = 0.1;
        req.maxTokensOverride = 700;
        req.messages.add(ChatMessage.system(
                "Extract durable memories about the USER from the transcript: stable preferences, personal facts they chose to share, "
                        + "ongoing projects, decisions, and standing instructions for the assistant. Ignore one-off requests, small talk, "
                        + "anything the assistant said about itself, tool output details, and ALL passwords, keys, tokens, card or ID numbers. "
                        + "Write each memory as one short third-person sentence in English (\"User prefers…\"). "
                        + "Respond with JSON only: {\"memories\":[{\"content\":\"…\",\"category\":\"preference|fact|project|decision|instruction|character|workflow\","
                        + "\"importance\":1-5,\"tags\":[\"…\"]}]}. Return {\"memories\":[]} when nothing qualifies."));
        req.messages.add(ChatMessage.user(transcript));
        ChatResponse resp;
        try {
            resp = llm.chat(req, StreamListener.NONE, cancel);
        } catch (LlmException e) {
            return actions;
        }
        List<MemoryEntry> candidates = parse(resp.content);
        int n = 0;
        for (MemoryEntry c : candidates) {
            if (n >= maxPerRun) break;
            n++;
            c.source = source == null ? "" : source;
            actions.add(save(c, store));
        }
        return actions;
    }

    /** Stores a candidate with secret filtering and dedup/update logic. Also used by the memory tool. */
    public Action save(MemoryEntry c, MemoryStore store) {
        if (TextUtil.isBlank(c.content)) return new Action(Action.Kind.REJECTED, c, "empty");
        String reject = SecretFilter.rejectReason(c.content);
        if (reject != null) return new Action(Action.Kind.REJECTED, c, reject);
        c.content = c.content.trim();
        if (c.content.length() > 600) c.content = c.content.substring(0, 600);
        MemoryEntry best = null;
        double bestSim = 0;
        for (MemoryEntry e : store.searchCandidates(c.content, null, 40)) {
            double sim = TextUtil.similarity(e.content, c.content);
            if (sim > bestSim) {
                bestSim = sim;
                best = e;
            }
        }
        if (best != null && bestSim >= duplicateThreshold) {
            return new Action(Action.Kind.SKIPPED_DUPLICATE, best, String.format(java.util.Locale.ROOT, "similar to #%d (%.2f)", best.id, bestSim));
        }
        if (best != null && bestSim >= updateThreshold && best.category == c.category && !best.pinned) {
            best.content = c.content;
            best.importance = Math.max(best.importance, c.importance);
            for (String t : c.tags) if (!best.tags.contains(t)) best.tags.add(t);
            store.update(best);
            return new Action(Action.Kind.UPDATED, best, String.format(java.util.Locale.ROOT, "replaced older version (%.2f)", bestSim));
        }
        return new Action(Action.Kind.ADDED, store.add(c), "");
    }

    static List<MemoryEntry> parse(String text) {
        List<MemoryEntry> out = new ArrayList<>();
        if (text == null) return out;
        String s = text.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return out;
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            JSONArray arr = o.optJSONArray("memories");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                MemoryEntry e = new MemoryEntry();
                e.content = m.optString("content", "").trim();
                if (e.content.isEmpty()) continue;
                e.category = MemoryCategory.parse(m.optString("category"), MemoryCategory.FACT);
                e.importance = Math.max(1, Math.min(5, m.optInt("importance", 3)));
                JSONArray tags = m.optJSONArray("tags");
                if (tags != null) for (int j = 0; j < tags.length() && j < 6; j++) e.tags.add(tags.optString(j));
                out.add(e);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }
}
