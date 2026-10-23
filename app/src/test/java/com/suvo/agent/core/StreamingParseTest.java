package com.suvo.agent.core;

import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.SseParser;
import com.suvo.agent.core.llm.StreamAccumulator;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.llm.ToolCall;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamingParseTest {

    @Test
    public void sseParserHandlesCommentsMultilineAndDone() {
        SseParser p = new SseParser();
        List<String> got = new ArrayList<>();
        SseParser.EventHandler h = d -> {
            got.add(d);
            return true;
        };
        p.feedLine(": keep-alive", h);
        p.feedLine("data: {\"a\":1}", h);   // complete JSON dispatches immediately
        p.feedLine("", h);
        p.feedLine("event: message", h);
        p.feedLine("data: {\"b\":", h);
        p.feedLine("data: 2}", h);
        p.feedLine("", h);
        assertTrue(p.feedLine("data: [DONE]", h) == false);
        assertTrue(p.isDone());
        assertEquals(2, got.size());
        assertEquals("{\"a\":1}", got.get(0));
        assertEquals("{\"b\":\n2}", got.get(1));
    }

    @Test
    public void accumulatesTextAndToolCallDeltas() throws Exception {
        StreamAccumulator acc = new StreamAccumulator();
        StringBuilder text = new StringBuilder();
        List<String> started = new ArrayList<>();
        StreamListener l = new StreamListener() {
            @Override
            public void onTextDelta(String d) {
                text.append(d);
            }

            @Override
            public void onToolCallStarted(int index, String name) {
                started.add(name);
            }
        };
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Open\"}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"content\":\"ing\"}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"launch_app\",\"arguments\":\"\"}}]}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pack\"}}]}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"age\\\":\\\"com.x\\\"}\"}}]}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"notify\",\"arguments\":\"{}\"}}]}}]}"), l);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"), l);
        ChatResponse r = acc.build();
        assertEquals("Opening", r.content);
        assertEquals("Opening", text.toString());
        assertEquals(2, r.toolCalls.size());
        ToolCall c = r.toolCalls.get(0);
        assertEquals("c1", c.id);
        assertEquals("launch_app", c.name);
        assertEquals("com.x", c.parseArguments().getString("package"));
        assertEquals("notify", r.toolCalls.get(1).name);
        assertEquals("tool_calls", r.finishReason);
        assertEquals(10, r.promptTokens);
        assertEquals(2, started.size());
    }

    @Test
    public void handlesRepeatedFullNamesAndMissingIndex() throws Exception {
        StreamAccumulator acc = new StreamAccumulator();
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"memory\",\"arguments\":\"{\\\"action\\\":\"}}]}}]}"), StreamListener.NONE);
        acc.accept(new JSONObject("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"memory\",\"arguments\":\"\\\"search\\\"}\"}}]}}]}"), StreamListener.NONE);
        ChatResponse r = acc.build();
        assertEquals(1, r.toolCalls.size());
        assertEquals("memory", r.toolCalls.get(0).name);
        assertEquals("search", r.toolCalls.get(0).parseArguments().getString("action"));
    }

    @Test
    public void nonStreamedMessageIsAccepted() throws Exception {
        StreamAccumulator acc = new StreamAccumulator();
        acc.accept(new JSONObject("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\",\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"list_tools\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"), StreamListener.NONE);
        ChatResponse r = acc.build();
        assertEquals("hi", r.content);
        assertEquals(1, r.toolCalls.size());
    }

    @Test
    public void malformedToolArgumentsAreReportedNotThrownLater() {
        ToolCall bad = new ToolCall("1", "x", "{not json");
        boolean threw = false;
        try {
            bad.parseArguments();
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw);
        // Double-encoded arguments are accepted.
        ToolCall dbl = new ToolCall("2", "x", "\"{\\\"a\\\":1}\"");
        try {
            assertEquals(1, dbl.parseArguments().getInt("a"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(new ToolCall("3", "x", "").signature().isEmpty());
    }

    @Test
    public void signatureIgnoresKeyOrder() {
        assertEquals(new ToolCall("1", "t", "{\"a\":1,\"b\":2}").signature(), new ToolCall("2", "t", "{\"b\":2, \"a\":1}").signature());
    }
}
