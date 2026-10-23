package com.suvo.agent.core;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.OpenAiProtocol;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.StreamAccumulator;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.llm.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Gemini's OpenAI-compatible API needs the thought signature of every tool call sent back. */
public class GeminiSignatureTest {

    private static final String SIG = "CvcQAdHtim/pKv/c0ClPFkYA==";

    private static ProviderConfig gemini() {
        ProviderConfig p = new ProviderConfig();
        p.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        p.model = "gemini-3-pro-preview";
        p.supportsTools = true;
        return p;
    }

    private static JSONObject toolCallsOfFirstAssistant(JSONObject body) {
        JSONArray msgs = body.optJSONArray("messages");
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.optJSONObject(i);
            if ("assistant".equals(m.optString("role")) && m.has("tool_calls")) return m.optJSONArray("tool_calls").optJSONObject(0);
        }
        throw new AssertionError("no assistant tool call in body");
    }

    @Test
    public void streamedSignatureSurvivesStorageAndIsSentBack() throws Exception {
        StreamAccumulator acc = new StreamAccumulator();
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\","
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"" + SIG + "\"}},"
                + "\"function\":{\"name\":\"device_status\",\"arguments\":\"\"}}]}}]}"), StreamListener.NONE);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}"), StreamListener.NONE);
        ChatResponse r = acc.build();
        assertEquals(1, r.toolCalls.size());
        assertEquals(SIG, r.toolCalls.get(0).thoughtSignature());

        // Saved to the database and loaded again.
        ChatMessage stored = ChatMessage.fromStorageJson(r.toMessage().toStorageJson());
        assertEquals(SIG, stored.toolCalls.get(0).thoughtSignature());

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("check the phone"));
        history.add(stored);
        history.add(ChatMessage.tool("c1", "device_status", "{\"battery\":80}"));
        ChatRequest req = new ChatRequest(history);
        JSONObject call = toolCallsOfFirstAssistant(OpenAiProtocol.buildBody(gemini(), req, true));
        assertEquals(SIG, call.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"));
        assertEquals("device_status", call.getJSONObject("function").getString("name"));
        assertEquals("c1", call.getString("id"));
    }

    @Test
    public void oldHistoryWithoutSignatureGetsPlaceholderOnlyForGemini() throws Exception {
        List<ToolCall> calls = new ArrayList<>();
        calls.add(new ToolCall("a", "file_list", "{}"));
        calls.add(new ToolCall("b", "device_status", "{}"));
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("hi"));
        history.add(ChatMessage.assistant(null, calls));
        history.add(ChatMessage.tool("a", "file_list", "[]"));
        history.add(ChatMessage.tool("b", "device_status", "{}"));
        ChatRequest req = new ChatRequest(history);

        JSONObject body = OpenAiProtocol.buildBody(gemini(), req, false);
        JSONArray tc = null;
        for (int i = 0; i < body.getJSONArray("messages").length(); i++) {
            JSONObject m = body.getJSONArray("messages").getJSONObject(i);
            if (m.has("tool_calls")) tc = m.getJSONArray("tool_calls");
        }
        assertEquals("skip_thought_signature_validator",
                tc.getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"));
        assertFalse("parallel calls after the first get no invented signature", tc.getJSONObject(1).has("extra_content"));

        ProviderConfig openai = new ProviderConfig();
        openai.baseUrl = "https://api.openai.com/v1";
        openai.model = "gpt-4o-mini";
        openai.supportsTools = true;
        JSONObject plain = toolCallsOfFirstAssistant(OpenAiProtocol.buildBody(openai, req, false));
        assertFalse("other providers never see Gemini fields", plain.has("extra_content"));
    }

    @Test
    public void wholeMessageResponseKeepsExtraFields() throws Exception {
        JSONObject resp = new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"extra_content\":{\"google\":{\"thought_signature\":\"" + SIG + "\"}},"
                + "\"function\":{\"name\":\"notify\",\"arguments\":\"{\\\"title\\\":\\\"hi\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}");
        ChatResponse r = OpenAiProtocol.parseResponse(resp);
        assertEquals(SIG, r.toolCalls.get(0).thoughtSignature());
        assertTrue(r.toolCalls.get(0).parseArguments().has("title"));
    }
}
