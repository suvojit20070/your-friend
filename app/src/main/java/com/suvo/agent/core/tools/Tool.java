package com.suvo.agent.core.tools;

import org.json.JSONObject;

<<<<<<< HEAD
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
=======
/**
 * A capability the agent can call. Built-in tools (screenshot, tap, file, shell,
 * camera...) and user skills both implement this.
 */
public interface Tool {

    /** Stable unique name the model calls, e.g. "take_screenshot". */
    String name();

    /** Schema advertised to the LLM. */
    ToolSchema schema();

    /** Whether this tool is currently usable (permission granted, enabled). */
    boolean isAvailable();

    /**
     * Run the tool. Called on a background thread. Return a short result string
     * for the model (what happened / what was found). Never throw for normal
     * failures — return an "Error: ..." string instead.
     */
    String run(JSONObject args) throws Exception;
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
