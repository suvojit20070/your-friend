package com.suvo.agent.core.tools;

import com.suvo.agent.core.util.CancellationToken;

/** Asks the human. The Android implementation shows an in-app dialog or a notification. */
public interface Approver {

    final class Request {
        public final String taskId;
        public final String callId;
        public final String toolName;
        public final RiskLevel risk;
        public final String summary;

        public Request(String taskId, String callId, String toolName, RiskLevel risk, String summary) {
            this.taskId = taskId;
            this.callId = callId;
            this.toolName = toolName;
            this.risk = risk;
            this.summary = summary;
        }
    }

    enum Outcome {
        APPROVED, DENIED, TIMED_OUT
    }

    /** Blocks until the user answers, the timeout elapses or the token is cancelled. */
    Outcome request(Request request, long timeoutMs, CancellationToken cancel);

    Approver DENY_ALL = (r, t, c) -> Outcome.DENIED;
    Approver APPROVE_ALL = (r, t, c) -> Outcome.APPROVED;
}
