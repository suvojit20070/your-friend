package com.suvo.agent.core.llm;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Provider-independent chat request. Tools are already in OpenAI "function" format. */
public final class ChatRequest {

    public final List<ChatMessage> messages = new ArrayList<>();
    public JSONArray tools = new JSONArray();
    /** "auto", "none", "required" or null to omit. */
    public String toolChoice = "auto";
    /** Overrides the provider's max tokens when > 0. */
    public int maxTokensOverride = 0;
    /** Overrides temperature when not NaN. */
    public double temperatureOverride = Double.NaN;
    /** Forces non-streaming (used for summaries and memory extraction). */
    public boolean forceNoStream = false;

    public ChatRequest() {
    }

    public ChatRequest(List<ChatMessage> messages) {
        this.messages.addAll(messages);
    }
}
