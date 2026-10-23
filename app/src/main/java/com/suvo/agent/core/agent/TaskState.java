package com.suvo.agent.core.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Durable state of one agent task. Kept separate from long-term memory and persisted after every
 * step, so context compaction or a process restart never loses what the task has done so far.
 */
public final class TaskState {

    public enum Status {
        PENDING, RUNNING, PAUSED, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, LIMIT_REACHED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == LIMIT_REACHED;
        }
    }

    /** One line in the visible execution log (real tool calls and their verified outcome). */
    public static final class Step {
        public final long time;
        public final String tool;
        public final String outcome;
        public final String detail;

        public Step(long time, String tool, String outcome, String detail) {
            this.time = time;
            this.tool = tool;
            this.outcome = outcome;
            this.detail = detail;
        }
    }

    public final String id;
    public final long conversationId;
    public String goal;
    public volatile Status status = Status.PENDING;
    public int llmTurns;
    public int toolCalls;
    public long startedAt;
    public long finishedAt;
    public String finalText = "";
    public String error = "";
    /** Unresolved items carried across compaction ("waiting for user to log in" ...). */
    public final List<String> openItems = Collections.synchronizedList(new ArrayList<>());
    private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());

    public TaskState(long conversationId, String goal) {
        this(UUID.randomUUID().toString(), conversationId, goal);
    }

    public TaskState(String id, long conversationId, String goal) {
        this.id = id;
        this.conversationId = conversationId;
        this.goal = goal == null ? "" : goal;
    }

    public void addStep(String tool, String outcome, String detail) {
        steps.add(new Step(System.currentTimeMillis(), tool, outcome, detail == null ? "" : detail));
    }

    public List<Step> steps() {
        synchronized (steps) {
            return new ArrayList<>(steps);
        }
    }

    /** Compact text used to restore task context into the prompt after compaction. */
    public String progressSummary(int maxSteps) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(goal).append('\n');
        sb.append("Status: ").append(status).append(", tool calls so far: ").append(toolCalls).append('\n');
        List<Step> s = steps();
        int from = Math.max(0, s.size() - maxSteps);
        if (from > 0) sb.append("(").append(from).append(" earlier steps omitted)\n");
        for (int i = from; i < s.size(); i++) {
            Step st = s.get(i);
            sb.append("- ").append(st.tool).append(": ").append(st.outcome);
            if (!st.detail.isEmpty()) sb.append(" — ").append(st.detail.length() > 160 ? st.detail.substring(0, 160) + "…" : st.detail);
            sb.append('\n');
        }
        synchronized (openItems) {
            for (String o : openItems) sb.append("Open: ").append(o).append('\n');
        }
        return sb.toString().trim();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("conversationId", conversationId).put("goal", goal).put("status", status.name())
                    .put("llmTurns", llmTurns).put("toolCalls", toolCalls).put("startedAt", startedAt)
                    .put("finishedAt", finishedAt).put("finalText", finalText).put("error", error);
            JSONArray st = new JSONArray();
            for (Step s : steps()) {
                st.put(new JSONObject().put("time", s.time).put("tool", s.tool).put("outcome", s.outcome).put("detail", s.detail));
            }
            o.put("steps", st);
            JSONArray open = new JSONArray();
            synchronized (openItems) {
                for (String s : openItems) open.put(s);
            }
            o.put("openItems", open);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static TaskState fromJson(JSONObject o) {
        TaskState t = new TaskState(o.optString("id"), o.optLong("conversationId", -1), o.optString("goal"));
        try {
            t.status = Status.valueOf(o.optString("status", "PENDING"));
        } catch (IllegalArgumentException e) {
            t.status = Status.FAILED;
        }
        t.llmTurns = o.optInt("llmTurns");
        t.toolCalls = o.optInt("toolCalls");
        t.startedAt = o.optLong("startedAt");
        t.finishedAt = o.optLong("finishedAt");
        t.finalText = o.optString("finalText", "");
        t.error = o.optString("error", "");
        JSONArray st = o.optJSONArray("steps");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.optJSONObject(i);
                if (s != null) t.steps.add(new Step(s.optLong("time"), s.optString("tool"), s.optString("outcome"), s.optString("detail")));
            }
        }
        JSONArray open = o.optJSONArray("openItems");
        if (open != null) for (int i = 0; i < open.length(); i++) t.openItems.add(open.optString(i));
        return t;
    }
}
