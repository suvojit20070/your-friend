package com.suvo.agent.core.tools;

import org.json.JSONObject;

/** A capability the agent can call. Implementations must report real outcomes only. */
public interface Tool {

    ToolSpec spec();

    /**
     * Runtime availability beyond Android permissions (e.g. "Python not installed in Termux").
     * Returns null when available, or a reason when not. Unavailable tools are not offered to the model.
     */
    default String unavailableReason() {
        return null;
    }

    /** Risk of this particular call; e.g. a file tool returns HIGH for delete and READ_ONLY for read. */
    default RiskLevel riskFor(JSONObject args) {
        return spec().defaultRisk;
    }

    /** Short human-readable summary of what this call will do, shown in approval prompts. */
    default String describeCall(JSONObject args) {
        return spec().name + " " + args.toString();
    }

    ToolResult execute(ToolInvocation invocation) throws Exception;
}
