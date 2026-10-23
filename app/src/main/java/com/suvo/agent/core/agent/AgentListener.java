package com.suvo.agent.core.agent;

/** Callbacks from the agent loop back to the UI / voice layer. */
public interface AgentListener {

    /** A chunk of the assistant's reply text (streaming). */
    void onReplyDelta(String delta);

    /** A full sentence is ready — good point to start speaking it. */
    void onSentence(String sentence);

    /** The assistant turn finished with this final text. */
    void onReplyDone(String fullText);

    /** The agent is about to run a tool (show status / spinner). */
    void onToolStart(String toolName);

    /** A tool finished. */
    void onToolResult(String toolName, String result);

    /** A high-risk tool needs user confirmation. Return true to allow.
     *  Called on a background thread — block until the user decides. */
    boolean confirmDangerous(String toolName, String argsPreview);

    /** Something went wrong. */
    void onError(String message);
}
