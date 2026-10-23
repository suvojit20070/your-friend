package com.suvo.agent.core.llm;

/** Receives incremental output while a streamed response arrives. All methods are optional. */
public interface StreamListener {

    default void onTextDelta(String delta) {
    }

    /** Called when a tool call's name becomes known, so the UI can show "preparing tool X". */
    default void onToolCallStarted(int index, String name) {
    }

    StreamListener NONE = new StreamListener() {
    };
}
