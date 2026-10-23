package com.suvo.agent.core.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One message in the conversation sent to / received from an OpenAI-compatible LLM.
 * Roles: "system", "user", "assistant", "tool".
 */
public class ChatMessage {

    public final String role;
    public String content;              // may be null when assistant only returns tool_calls

    // assistant -> tool calls it wants to run
    public List<ToolCall> toolCalls = new ArrayList<>();
    // tool -> which call this result answers
    public String toolCallId;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(String c) { return new ChatMessage("system", c); }
    public static ChatMessage user(String c)   { return new ChatMessage("user", c); }
    public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }

    public static ChatMessage toolResult(String callId, String content) {
        ChatMessage m = new ChatMessage("tool", content);
        m.toolCallId = callId;
        return m;
    }

    /** Serialize to the OpenAI chat-completions message shape. */
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content == null ? "" : content);
        if (toolCallId != null) o.put("tool_call_id", toolCallId);
        if (!toolCalls.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (ToolCall tc : toolCalls) arr.put(tc.toJson());
            o.put("tool_calls", arr);
        }
        return o;
    }
}
