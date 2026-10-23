package com.suvo.agent.core.agent;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Makes stored history acceptable to the chat-completions API before each request. */
public final class HistorySanitizer {

    private HistorySanitizer() {
    }

    /**
     * Repairs sequences the API rejects: tool results without their assistant call (after
     * compaction) and assistant tool calls without results (after a crash or the process being killed).
     */
    public static List<ChatMessage> sanitize(List<ChatMessage> history) {
        List<ChatMessage> out = new ArrayList<>();
        Set<String> openCalls = new LinkedHashSet<>();
        for (ChatMessage m : history) {
            if (m.role == ChatMessage.Role.TOOL) {
                if (!openCalls.remove(m.toolCallId)) continue; // orphan result
                out.add(m);
                continue;
            }
            closeOpen(out, openCalls);
            out.add(m);
            if (m.role == ChatMessage.Role.ASSISTANT) for (ToolCall c : m.toolCalls) openCalls.add(c.id);
        }
        closeOpen(out, openCalls);
        return out;
    }

    private static void closeOpen(List<ChatMessage> out, Set<String> openCalls) {
        for (String id : openCalls) {
            out.add(ChatMessage.tool(id, "unknown", "{\"status\":\"cancelled\",\"message\":\"Interrupted before a result was recorded\"}"));
        }
        openCalls.clear();
    }

    /** Keeps images only in the {@code keep} most recent image-bearing messages. */
    public static List<ChatMessage> limitImages(List<ChatMessage> history, int keep) {
        int seen = 0;
        List<ChatMessage> out = new ArrayList<>(history);
        for (int i = out.size() - 1; i >= 0; i--) {
            ChatMessage m = out.get(i);
            if (m.images.isEmpty()) continue;
            seen++;
            if (seen > keep) {
                ChatMessage stripped = m.role == ChatMessage.Role.USER
                        ? ChatMessage.user(m.contentOrEmpty() + " [earlier image omitted]")
                        : ChatMessage.tool(m.toolCallId, m.name, m.contentOrEmpty());
                stripped.localId = m.localId;
                out.set(i, stripped);
            }
        }
        return out;
    }
}
