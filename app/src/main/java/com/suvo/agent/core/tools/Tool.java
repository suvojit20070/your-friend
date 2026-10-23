package com.suvo.agent.core.tools;

import org.json.JSONObject;

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
}
