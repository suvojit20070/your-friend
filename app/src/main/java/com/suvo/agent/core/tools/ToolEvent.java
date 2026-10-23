package com.suvo.agent.core.tools;

/** Lifecycle event for audit history and live UI. */
public final class ToolEvent {

    public enum Phase {
        REQUESTED, VALIDATED, AUTHORIZED, EXECUTING, SUCCEEDED, FAILED, CANCELLED, DENIED
    }

    public interface Sink {
        void onEvent(ToolEvent event);

        Sink NONE = e -> {
        };
    }

    public final long timestamp;
    public final String taskId;
    public final String callId;
    public final String toolName;
    public final Phase phase;
    public final String detail;
    /** Arguments with sensitive values redacted. */
    public final String redactedArgs;

    public ToolEvent(String taskId, String callId, String toolName, Phase phase, String detail, String redactedArgs) {
        this.timestamp = System.currentTimeMillis();
        this.taskId = taskId;
        this.callId = callId;
        this.toolName = toolName;
        this.phase = phase;
        this.detail = detail == null ? "" : detail;
        this.redactedArgs = redactedArgs == null ? "" : redactedArgs;
    }
}
