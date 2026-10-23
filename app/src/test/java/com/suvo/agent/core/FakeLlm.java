package com.suvo.agent.core;

import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.util.CancellationToken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Scripted model for agent-loop tests. Each step is either a response or an exception. */
public final class FakeLlm implements LlmClient {

    private final Deque<Object> script = new ArrayDeque<>();
    public final List<ChatRequest> requests = new ArrayList<>();
    private final ProviderConfig cfg = new ProviderConfig();

    public FakeLlm() {
        cfg.name = "fake";
        cfg.baseUrl = "http://localhost";
        cfg.model = "fake-model";
    }

    public FakeLlm text(String content) {
        ChatResponse r = new ChatResponse();
        r.content = content;
        r.finishReason = "stop";
        script.add(r);
        return this;
    }

    public FakeLlm tools(ToolCall... calls) {
        ChatResponse r = new ChatResponse();
        r.finishReason = "tool_calls";
        for (ToolCall c : calls) r.toolCalls.add(c);
        script.add(r);
        return this;
    }

    public FakeLlm error(LlmException e) {
        script.add(e);
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws LlmException {
        requests.add(request);
        if (cancel != null && cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
        Object next = script.isEmpty() ? null : script.poll();
        if (next == null) {
            ChatResponse r = new ChatResponse();
            r.content = "(script exhausted)";
            return r;
        }
        if (next instanceof LlmException) throw (LlmException) next;
        ChatResponse r = (ChatResponse) next;
        if (!r.content.isEmpty()) listener.onTextDelta(r.content);
        return r;
    }

    @Override
    public ProviderConfig config() {
        return cfg;
    }
}
