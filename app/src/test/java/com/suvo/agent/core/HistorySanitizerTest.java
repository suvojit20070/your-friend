package com.suvo.agent.core;

import com.suvo.agent.core.agent.HistorySanitizer;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistorySanitizerTest {

    @Test
    public void dropsOrphanResultsAndClosesOpenCalls() {
        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.tool("old", "notify", "{}"));                       // orphan after compaction
        h.add(ChatMessage.user("do two things"));
        h.add(ChatMessage.assistant("", Arrays.asList(new ToolCall("a", "x", "{}"), new ToolCall("b", "y", "{}"))));
        h.add(ChatMessage.tool("a", "x", "{}"));                              // b never finished (crash)
        h.add(ChatMessage.user("hello?"));
        h.add(ChatMessage.assistant("", Collections.singletonList(new ToolCall("c", "z", "{}")))); // trailing open call
        List<ChatMessage> s = HistorySanitizer.sanitize(h);
        assertEquals(ChatMessage.Role.USER, s.get(0).role);
        assertEquals("a", s.get(2).toolCallId);
        assertEquals("b", s.get(3).toolCallId);
        assertTrue(s.get(3).content.contains("Interrupted"));
        assertEquals(ChatMessage.Role.USER, s.get(4).role);
        assertEquals("c", s.get(s.size() - 1).toolCallId);
        assertEquals(7, s.size());
    }

    @Test
    public void keepsOnlyRecentImages() {
        ChatMessage.Image img = new ChatMessage.Image("data:image/jpeg;base64,AA", "low");
        List<ChatMessage> h = new ArrayList<>();
        for (int i = 0; i < 4; i++) h.add(ChatMessage.user("pic " + i, Collections.singletonList(img)));
        List<ChatMessage> s = HistorySanitizer.limitImages(h, 2);
        assertEquals(0, s.get(0).images.size());
        assertEquals(0, s.get(1).images.size());
        assertEquals(1, s.get(2).images.size());
        assertEquals(1, s.get(3).images.size());
        assertTrue(s.get(0).content.contains("omitted"));
    }
}
