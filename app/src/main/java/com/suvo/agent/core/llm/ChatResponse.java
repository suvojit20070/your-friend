package com.suvo.agent.core.llm;

import java.util.ArrayList;
import java.util.List;

/** Final, fully accumulated assistant turn. */
public final class ChatResponse {

    public String content = "";
    public String reasoning = "";
    public final List<ToolCall> toolCalls = new ArrayList<>();
    public String finishReason = "";
    public int promptTokens = -1;
    public int completionTokens = -1;
    public String model = "";

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public ChatMessage toMessage() {
        return hasToolCalls() ? ChatMessage.assistant(content, toolCalls) : ChatMessage.assistant(content);
    }
}
