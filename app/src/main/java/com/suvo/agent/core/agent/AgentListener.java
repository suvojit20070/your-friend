package com.suvo.agent.core.agent;

<<<<<<< HEAD
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.ToolResult;

/** Observes a running task. Called from the agent's worker thread. */
public interface AgentListener {

    default void onStatusChanged(TaskState task) {
    }

    /** Streaming assistant text. */
    default void onTextDelta(TaskState task, String delta) {
    }

    /** A streamed tool call's name became known (before arguments finish). */
    default void onToolCallPreparing(TaskState task, String toolName) {
    }

    default void onAssistantMessage(TaskState task, ChatMessage message) {
    }

    default void onToolExecuting(TaskState task, ToolCall call) {
    }

    default void onToolProgress(TaskState task, ToolCall call, String message) {
    }

    default void onToolResult(TaskState task, ToolCall call, ToolResult result) {
    }

    /** Non-fatal notices (retrying, compacting context...). */
    default void onNotice(TaskState task, String notice) {
    }

    default void onFinished(TaskState task) {
    }
=======
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
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
