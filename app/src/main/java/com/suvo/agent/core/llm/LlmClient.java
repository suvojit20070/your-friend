package com.suvo.agent.core.llm;

import com.suvo.agent.core.util.CancellationToken;

/** Abstraction over a chat model, so the agent loop can be tested with a fake. */
public interface LlmClient {

    ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws LlmException;

    /** Provider capabilities used to shape requests (tools, images...). */
    ProviderConfig config();
}
