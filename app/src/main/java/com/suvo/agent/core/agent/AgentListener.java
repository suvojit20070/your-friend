package com.suvo.agent.core.agent;

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
}
