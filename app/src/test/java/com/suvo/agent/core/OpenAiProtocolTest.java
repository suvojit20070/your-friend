package com.suvo.agent.core;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.OpenAiProtocol;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenAiProtocolTest {

    private static ProviderConfig cfg() {
        ProviderConfig p = new ProviderConfig();
        p.name = "Test";
        p.baseUrl = "https://api.example.com/v1/";
        p.model = "m";
        return p;
    }

    private static ChatRequest req() throws Exception {
        ChatRequest r = new ChatRequest();
        r.messages.add(ChatMessage.system("sys"));
        r.messages.add(ChatMessage.user("look", Collections.singletonList(new ChatMessage.Image("data:image/png;base64,AA", "low"))));
        r.messages.add(ChatMessage.assistant("", Collections.singletonList(new ToolCall("c1", "screenshot", "{}"))));
        r.messages.add(ChatMessage.tool("c1", "screenshot", "{\"status\":\"succeeded\"}"));
        r.tools = new JSONArray().put(new JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"screenshot\",\"parameters\":{\"type\":\"object\"}}}"));
        return r;
    }

    @Test
    public void urlsAreNormalized() {
        ProviderConfig p = cfg();
        assertEquals("https://api.example.com/v1/chat/completions", p.chatCompletionsUrl());
        assertEquals("https://api.example.com/v1/models", p.modelsUrl());
        p.baseUrl = "https://x.dev/v1/chat/completions";
        assertEquals("https://x.dev/v1/chat/completions", p.chatCompletionsUrl());
        assertEquals("https://x.dev/v1/models", p.modelsUrl());
    }

    @Test
    public void fullCapabilityBody() throws Exception {
        ProviderConfig p = cfg();
        p.supportsVision = true;
        p.maxTokens = 500;
        JSONObject b = OpenAiProtocol.buildBody(p, req(), true);
        assertTrue(b.getBoolean("stream"));
        assertEquals(500, b.getInt("max_tokens"));
        assertEquals(1, b.getJSONArray("tools").length());
        JSONArray msgs = b.getJSONArray("messages");
        assertEquals("system", msgs.getJSONObject(0).getString("role"));
        assertTrue(msgs.getJSONObject(1).get("content") instanceof JSONArray);
        JSONObject asst = msgs.getJSONObject(2);
        assertTrue(asst.isNull("content"));
        assertEquals("c1", asst.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals("c1", msgs.getJSONObject(3).getString("tool_call_id"));
        assertFalse(b.has("stream_options"));
    }

    @Test
    public void noVisionNoToolsNoSystemFallbacks() throws Exception {
        ProviderConfig p = cfg();
        p.supportsVision = false;
        p.supportsTools = false;
        p.supportsSystemRole = false;
        JSONObject b = OpenAiProtocol.buildBody(p, req(), false);
        assertFalse(b.has("tools"));
        assertFalse(b.has("stream"));
        JSONArray msgs = b.getJSONArray("messages");
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            assertFalse("system role must be removed", "system".equals(m.getString("role")));
            assertFalse("tool role must be flattened", "tool".equals(m.getString("role")));
            assertFalse(m.has("tool_calls"));
            assertTrue(m.get("content") instanceof String);
        }
        String first = msgs.getJSONObject(0).getString("content");
        assertTrue(first.contains("sys"));
        assertTrue(first.contains("look"));
        assertTrue(first.contains("image(s) omitted"));
    }

    @Test
    public void errorClassification() {
        assertEquals(LlmException.Kind.AUTH, OpenAiProtocol.classify(401, "{\"error\":{\"message\":\"bad key\"}}", 0).kind);
        assertEquals(LlmException.Kind.RATE_LIMIT, OpenAiProtocol.classify(429, "slow down", 2000).kind);
        assertEquals(2000, OpenAiProtocol.classify(429, "", 2000).retryAfterMs);
        assertEquals(LlmException.Kind.CONTEXT_LENGTH, OpenAiProtocol.classify(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}", 0).kind);
        assertEquals(LlmException.Kind.SERVER, OpenAiProtocol.classify(502, "", 0).kind);
        assertEquals(LlmException.Kind.NOT_FOUND, OpenAiProtocol.classify(404, "", 0).kind);
        assertEquals(LlmException.Kind.UNSUPPORTED, OpenAiProtocol.classify(400, "tools are not supported by this model", 0).kind);
        assertEquals("bad key", OpenAiProtocol.classify(401, "{\"error\":{\"message\":\"bad key\"}}", 0).getMessage());
        assertTrue(OpenAiProtocol.classify(503, "", 0).isRetryable());
        assertFalse(OpenAiProtocol.classify(401, "", 0).isRetryable());
    }

    @Test
    public void providerValidationAndSecretFreeExport() {
        ProviderConfig p = cfg();
        p.apiKey = "sk-secret";
        p.headers.put("X-Org", "org-secret");
        assertNull(p.validate());
        JSONObject exported = p.toJson(false);
        assertFalse(exported.has("apiKey"));
        assertEquals("", exported.optJSONObject("headers").optString("X-Org"));
        assertEquals("sk-secret", p.toJson(true).optString("apiKey"));
        p.baseUrl = "ftp://x";
        assertNotNull(p.validate());
        p.baseUrl = "https://x";
        p.model = "";
        assertNotNull(p.validate());
    }

    @Test
    public void storageRoundTrip() throws Exception {
        ChatMessage m = ChatMessage.assistant("hi", Collections.singletonList(new ToolCall("a", "notify", "{\"title\":\"x\"}")));
        ChatMessage back = ChatMessage.fromStorageJson(new JSONObject(m.toStorageJson().toString()));
        assertEquals(ChatMessage.Role.ASSISTANT, back.role);
        assertEquals("notify", back.toolCalls.get(0).name);
        ChatMessage t = ChatMessage.tool("a", "notify", "{}");
        assertEquals("a", ChatMessage.fromStorageJson(t.toStorageJson()).toolCallId);
    }
}
