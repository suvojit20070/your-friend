package com.suvo.agent.core.agent;

import com.suvo.agent.core.llm.ChatMessage;

import org.json.JSONArray;

import java.util.List;

/**
 * Environment the loop runs in: persistence, context assembly and tool definitions. The Android
 * implementation backs this with SQLite, memory retrieval and context compaction.
 */
public interface AgentHost {

    /** Full message list for the next request: system prompt, summaries, memories, recent history. */
    List<ChatMessage> buildMessages(TaskState task) throws Exception;

    /** Appends a message to the conversation (persisted before the loop continues). */
    void persist(TaskState task, ChatMessage message) throws Exception;

    /** Saves task progress; called after every step. */
    void saveTask(TaskState task);

    /** Tool definitions currently offered to the model. */
    JSONArray toolDefinitions();

    /**
     * Called when the provider reports the context is too long. Should compact older history.
     *
     * @return true if something was compacted and a retry makes sense
     */
    boolean compactForOverflow(TaskState task) throws Exception;

    /** Whether images from tool results may be forwarded to the model. */
    boolean visionEnabled();

    /**
     * Messages that arrived while the task runs (the user wrote again, a team member reported):
     * taken once, and added to the conversation before the next model call — the agent reads them
     * and can change course without stopping. Default: none.
     */
    default List<ChatMessage> takeIncoming() {
        return java.util.Collections.emptyList();
    }

    /** True when {@link #takeIncoming} has something (checked before an answer ends the task). */
    default boolean hasIncoming() {
        return false;
    }
}
