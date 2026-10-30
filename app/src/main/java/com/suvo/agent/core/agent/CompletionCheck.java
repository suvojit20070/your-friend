package com.suvo.agent.core.agent;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Your Friend — Original project by Suvo.
 *
 * Stops "done!" when it is not done. Before a task that used tools is reported as finished, a
 * separate, short request — a reviewer that did not do the work — reads the user's request, the
 * actions taken with their results, and the final answer, and says whether the request is really
 * fulfilled by the evidence (searched but never played the song, typed but never pressed send,
 * claimed a result no tool showed…). If not, the agent is told what is still missing and keeps
 * working; it also has to confirm the end state with a read (screen, page, file) before claiming
 * it. At most a couple of checks per task, and an honest "I could not do it" or a question to the
 * user is accepted as is.
 *
 * Pure Java.
 */
public final class CompletionCheck {

    public static final String MARKER = FollowThrough.MARKER;

    private static final int KEEP = 16;
    private final Deque<String> log = new ArrayDeque<>();

    /** Records one tool call and its result (shortened). */
    public void record(String tool, String args, String result) {
        log.addLast("- " + tool + " " + cut(args == null ? "" : args, 300) + "\n  → " + cut(result == null ? "" : result, 700));
        while (log.size() > KEEP) log.removeFirst();
    }

    public boolean isEmpty() {
        return log.isEmpty();
    }

    static String cut(String s, int n) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    public static final class Verdict {
        public final boolean done;
        public final String missing;

        Verdict(boolean done, String missing) {
            this.done = done;
            this.missing = missing == null ? "" : missing;
        }
    }

    static final String SYSTEM = "You review the work of an AI agent that operates the user's phone, apps, websites and accounts, "
            + "before its final answer is shown to the user. You get the user's request, the actions the agent took (tool calls "
            + "with their results, oldest first) and its final answer. Decide from the EVIDENCE whether the request is really "
            + "fulfilled — not whether the answer sounds finished.\n"
            + "Common misses: searched but never opened / played / sent / bought; typed a message but never pressed send; "
            + "opened an app but did not finish the action; a step failed or was denied but the answer says it worked; "
            + "the answer claims a result that no tool result shows; it did only the first part of a multi-part request.\n"
            + "Count it as fulfilled when the evidence shows the end state (e.g. a screen read or page text after the action "
            + "shows the video playing / the message sent). If the agent honestly reports that it could not do it (and why), "
            + "or asks the user for something it really needs, or the request was only a question it answered, that is "
            + "fine: done = true.\n"
            + "Answer with JSON only: {\"done\": true|false, \"missing\": \"if not done: what is still missing and how to "
            + "do and confirm it, one or two short sentences\"}";

    /** The reviewer's request. */
    ChatRequest request(String goal, String finalAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("User's request:\n").append(cut(goal == null ? "" : goal, 1500)).append("\n\nActions (oldest first):\n");
        for (String l : log) sb.append(l).append('\n');
        sb.append("\nAgent's final answer:\n").append(cut(finalAnswer == null ? "" : finalAnswer, 1500));
        ChatRequest req = new ChatRequest();
        req.forceNoStream = true;
        req.toolChoice = null;
        req.temperatureOverride = 0.0;
        req.maxTokensOverride = 900;
        req.messages.add(ChatMessage.system(SYSTEM));
        req.messages.add(ChatMessage.user(sb.toString()));
        return req;
    }

    /** Reads the reviewer's answer; anything unreadable counts as done (never blocks the task). */
    static Verdict parse(String text) {
        if (text == null) return new Verdict(true, "");
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return new Verdict(true, "");
        try {
            JSONObject o = new JSONObject(text.substring(a, b + 1));
            boolean done = o.optBoolean("done", true);
            return new Verdict(done, o.optString("missing", "").trim());
        } catch (Exception e) {
            return new Verdict(true, "");
        }
    }

    /** Asks the reviewer. Null when the check could not run (then the task just finishes). */
    public Verdict check(LlmClient llm, String goal, String finalAnswer, CancellationToken cancel) {
        try {
            ChatResponse r = llm.chat(request(goal, finalAnswer), StreamListener.NONE, cancel);
            return parse(r == null ? null : r.content);
        } catch (Exception e) {
            return null;
        }
    }

    /** What the agent is told when the work is not finished. */
    public static String reminder(String missing) {
        return MARKER + " Not finished yet — a check of your actions found: "
                + (missing.isEmpty() ? "the request is not fulfilled by what the tools showed." : missing)
                + " Continue now: do the remaining steps, then confirm the end state with a read (screen, page text, file, "
                + "API result) before you say it is done. If it truly cannot be done, say exactly what blocks it.";
    }
}
