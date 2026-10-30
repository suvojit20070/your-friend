package com.suvo.agent.agent;

import android.content.Context;
import android.os.SystemClock;

import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.core.util.TokenCount;
import com.suvo.agent.data.UsageStore;

/**
 * Your Friend — Original project by Suvo.
 *
 * Wraps a provider's client and records every request in {@link UsageStore}: tokens counted by
 * the app itself (input = what was sent, output = what came back), the time to the first token,
 * generation speed, and failures grouped by kind. Nothing about the request itself changes.
 */
public final class TrackedLlm implements LlmClient {

    private final LlmClient inner;
    private final Context ctx;

    public TrackedLlm(Context ctx, LlmClient inner) {
        this.ctx = ctx.getApplicationContext();
        this.inner = inner;
    }

    /** {@code inner} wrapped, unless it already is. */
    public static LlmClient wrap(Context ctx, LlmClient inner) {
        return inner instanceof TrackedLlm ? inner : new TrackedLlm(ctx, inner);
    }

    @Override
    public ProviderConfig config() {
        return inner.config();
    }

    @Override
    public ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws LlmException {
        ProviderConfig p = inner.config();
        long id = p == null ? -1 : p.id;
        String name = p == null ? "" : p.name;
        String model = p == null ? "" : p.model;
        int in = TokenCount.of(request);
        long t0 = SystemClock.elapsedRealtime();
        final long[] first = {0};
        final StreamListener out = listener == null ? StreamListener.NONE : listener;
        StreamListener watch = new StreamListener() {
            @Override
            public void onTextDelta(String delta) {
                if (first[0] == 0) first[0] = SystemClock.elapsedRealtime();
                out.onTextDelta(delta);
            }

            @Override
            public void onReasoningDelta(String delta) {
                if (first[0] == 0) first[0] = SystemClock.elapsedRealtime();
                out.onReasoningDelta(delta);
            }

            @Override
            public void onToolCallStarted(int index, String toolName) {
                if (first[0] == 0) first[0] = SystemClock.elapsedRealtime();
                out.onToolCallStarted(index, toolName);
            }
        };
        UsageStore store = UsageStore.get(ctx);
        try {
            ChatResponse r = inner.chat(request, watch, cancel);
            long end = SystemClock.elapsedRealtime();
            store.success(id, name, model, in, TokenCount.of(r), t0, first[0], end,
                    r == null ? -1 : r.promptTokens, r == null ? -1 : r.completionTokens);
            return r;
        } catch (LlmException e) {
            long end = SystemClock.elapsedRealtime();
            if (e.kind == LlmException.Kind.CANCELLED || (cancel != null && cancel.isCancelled())) {
                store.cancelled(id, name, model);
            } else {
                String kind = e.kind.name() + (e.httpStatus > 0 ? " " + e.httpStatus : "");
                store.failure(id, name, model, in, kind, e.getMessage(), t0, end);
            }
            throw e;
        } catch (RuntimeException e) {
            store.failure(id, name, model, in, "CRASH " + e.getClass().getSimpleName(), e.getMessage(), t0, SystemClock.elapsedRealtime());
            throw e;
        }
    }
}
