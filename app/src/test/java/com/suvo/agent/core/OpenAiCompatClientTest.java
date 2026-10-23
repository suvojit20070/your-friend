package com.suvo.agent.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the real HTTP client against a local in-process server. */
public class OpenAiCompatClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private ProviderConfig start(Handler h) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            h.handle(ex);
        });
        server.start();
        ProviderConfig p = new ProviderConfig();
        p.name = "local";
        p.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        p.model = "test-model";
        p.apiKey = "sk-test-123";
        p.timeoutMs = 5000;
        return p;
    }

    @After
    public void stop() {
        if (server != null) server.stop(0);
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static ChatRequest hello() {
        ChatRequest r = new ChatRequest();
        r.messages.add(ChatMessage.user("hello"));
        return r;
    }

    @Test
    public void streamsTextAndToolCalls() throws Exception {
        ProviderConfig p = start(ex -> send(ex, 200, "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"function\":{\"name\":\"notify\",\"arguments\":\"{\\\"title\\\":\\\"x\\\"}\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        StringBuilder sb = new StringBuilder();
        ChatResponse r = new OpenAiCompatClient(p).chat(hello(), new StreamListener() {
            @Override
            public void onTextDelta(String d) {
                sb.append(d);
            }
        }, new CancellationToken());
        assertEquals("Hello", r.content);
        assertEquals("Hello", sb.toString());
        assertEquals(1, r.toolCalls.size());
        assertEquals("notify", r.toolCalls.get(0).name);
        assertEquals("Bearer sk-test-123", lastAuth.get());
        JSONObject sent = new JSONObject(lastBody.get());
        assertEquals("test-model", sent.getString("model"));
        assertTrue(sent.getBoolean("stream"));
    }

    @Test
    public void plainJsonWhenServerIgnoresStream() throws Exception {
        ProviderConfig p = start(ex -> send(ex, 200, "application/json",
                "{\"model\":\"m2\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));
        ChatResponse r = new OpenAiCompatClient(p).chat(hello(), StreamListener.NONE, new CancellationToken());
        assertEquals("ok", r.content);
        assertEquals("m2", r.model);
    }

    @Test
    public void rateLimitAndAuthErrorsAreClassified() throws Exception {
        ProviderConfig p = start(ex -> {
            ex.getResponseHeaders().add("Retry-After", "3");
            send(ex, 429, "application/json", "{\"error\":{\"message\":\"Too many requests\"}}");
        });
        try {
            new OpenAiCompatClient(p).chat(hello(), StreamListener.NONE, new CancellationToken());
            fail("expected exception");
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.RATE_LIMIT, e.kind);
            assertEquals(3000, e.retryAfterMs);
        }
        stop();
        ProviderConfig p2 = start(ex -> send(ex, 401, "application/json", "{\"error\":{\"message\":\"Invalid key\"}}"));
        try {
            new OpenAiCompatClient(p2).chat(hello(), StreamListener.NONE, new CancellationToken());
            fail("expected exception");
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.AUTH, e.kind);
            assertTrue("user message must not leak the key", !e.userMessage().contains("sk-test"));
        }
    }

    @Test
    public void malformedResponseIsReported() throws Exception {
        ProviderConfig p = start(ex -> send(ex, 200, "application/json", "<html>gateway</html>"));
        try {
            new OpenAiCompatClient(p).chat(hello(), StreamListener.NONE, new CancellationToken());
            fail("expected exception");
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.MALFORMED_RESPONSE, e.kind);
        }
    }

    @Test
    public void connectionRefusedIsNetworkError() throws Exception {
        ProviderConfig p = start(ex -> send(ex, 200, "application/json", "{}"));
        stop();
        server = null;
        try {
            new OpenAiCompatClient(p).chat(hello(), StreamListener.NONE, new CancellationToken());
            fail("expected exception");
        } catch (LlmException e) {
            assertTrue(e.kind == LlmException.Kind.NETWORK || e.kind == LlmException.Kind.TIMEOUT);
            assertTrue(e.isRetryable());
        }
    }

    @Test
    public void cancellationStopsAStreamingResponse() throws Exception {
        ProviderConfig p = start(ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            try {
                for (int i = 0; i < 200; i++) {
                    os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    Thread.sleep(20);
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // client disconnected
            } finally {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        });
        CancellationToken token = new CancellationToken();
        long start = System.currentTimeMillis();
        try {
            new OpenAiCompatClient(p).chat(hello(), new StreamListener() {
                int n;

                @Override
                public void onTextDelta(String d) {
                    if (++n == 5) token.cancel("user");
                }
            }, token);
            fail("expected cancellation");
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.CANCELLED, e.kind);
        }
        assertTrue("cancel should be prompt", System.currentTimeMillis() - start < 3000);
    }

    @Test
    public void listsModels() throws Exception {
        ProviderConfig p = start(ex -> send(ex, 200, "application/json", "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}"));
        List<String> models = new OpenAiCompatClient(p).listModels(new CancellationToken());
        assertEquals(2, models.size());
        assertEquals("b", models.get(1));
    }
}
