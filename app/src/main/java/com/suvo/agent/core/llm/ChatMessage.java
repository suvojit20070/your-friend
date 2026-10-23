package com.suvo.agent.core.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
<<<<<<< HEAD
import java.util.Collections;
import java.util.List;

/**
 * One message in the OpenAI chat-completions format. Images are carried separately so the
 * request builder can drop them for providers without vision support.
 */
public final class ChatMessage {

    public enum Role {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");

        public final String wire;

        Role(String wire) {
            this.wire = wire;
        }

        public static Role fromWire(String s) {
            for (Role r : values()) if (r.wire.equals(s)) return r;
            throw new IllegalArgumentException("Unknown role: " + s);
        }
    }

    /** An inline image (base64 data URL or https URL). */
    public static final class Image {
        public final String url;
        public final String detail;

        public Image(String url, String detail) {
            this.url = url;
            this.detail = detail == null ? "auto" : detail;
        }
    }

    public final Role role;
    public final String content;
    public final List<Image> images;
    public final List<ToolCall> toolCalls;
    public final String toolCallId;
    public final String name;
    /** Local database id, -1 when not persisted. Not sent to the provider. */
    public long localId = -1;

    private ChatMessage(Role role, String content, List<Image> images, List<ToolCall> toolCalls,
                        String toolCallId, String name) {
        this.role = role;
        this.content = content;
        this.images = images == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(images));
        this.toolCalls = toolCalls == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.toolCallId = toolCallId;
        this.name = name;
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text, null, null, null, null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, null, null, null, null);
    }

    public static ChatMessage user(String text, List<Image> images) {
        return new ChatMessage(Role.USER, text, images, null, null, null);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text, null, null, null, null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, text, null, calls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String toolName, String content) {
        return new ChatMessage(Role.TOOL, content, null, null, toolCallId, toolName);
    }

    public static ChatMessage tool(String toolCallId, String toolName, String content, List<Image> images) {
        return new ChatMessage(Role.TOOL, content, images, null, toolCallId, toolName);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public String contentOrEmpty() {
        return content == null ? "" : content;
    }

    /**
     * Serializes for the chat-completions API.
     *
     * @param allowImages whether image parts may be sent (provider capability)
     */
    public JSONObject toJson(boolean allowImages) {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role.wire);
            if (role == Role.USER && allowImages && !images.isEmpty()) {
                JSONArray parts = new JSONArray();
                if (content != null && !content.isEmpty()) {
                    parts.put(new JSONObject().put("type", "text").put("text", content));
                }
                for (Image img : images) {
                    parts.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", img.url).put("detail", img.detail)));
                }
                o.put("content", parts);
            } else if (role == Role.ASSISTANT && hasToolCalls()) {
                // Many providers require content to be null or a string when tool_calls are present.
                o.put("content", content == null || content.isEmpty() ? JSONObject.NULL : content);
                JSONArray calls = new JSONArray();
                for (ToolCall c : toolCalls) calls.put(c.toJson());
                o.put("tool_calls", calls);
            } else {
                String text = content == null ? "" : content;
                if (!images.isEmpty() && !allowImages) {
                    text = text + "\n[" + images.size() + " image(s) omitted: the selected model does not accept images]";
                }
                o.put("content", text);
            }
            if (role == Role.TOOL) {
                o.put("tool_call_id", toolCallId == null ? "" : toolCallId);
                if (name != null) o.put("name", name);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    /** Persistence form (includes images and tool metadata); not the wire format. */
    public JSONObject toStorageJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("role", role.wire);
            o.put("content", content == null ? JSONObject.NULL : content);
            if (!toolCalls.isEmpty()) {
                JSONArray calls = new JSONArray();
                for (ToolCall c : toolCalls) calls.put(c.toJson());
                o.put("tool_calls", calls);
            }
            if (toolCallId != null) o.put("tool_call_id", toolCallId);
            if (name != null) o.put("name", name);
            if (!images.isEmpty()) {
                JSONArray imgs = new JSONArray();
                for (Image i : images) imgs.put(new JSONObject().put("url", i.url).put("detail", i.detail));
                o.put("images", imgs);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static ChatMessage fromStorageJson(JSONObject o) {
        Role role = Role.fromWire(o.optString("role", "user"));
        String content = o.isNull("content") ? null : o.optString("content", null);
        List<ToolCall> calls = new ArrayList<>();
        JSONArray arr = o.optJSONArray("tool_calls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) calls.add(ToolCall.fromJson(c));
            }
        }
        List<Image> images = new ArrayList<>();
        JSONArray imgs = o.optJSONArray("images");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject im = imgs.optJSONObject(i);
                if (im != null) images.add(new Image(im.optString("url"), im.optString("detail", "auto")));
            }
        }
        String toolCallId = o.has("tool_call_id") ? o.optString("tool_call_id") : null;
        String name = o.has("name") ? o.optString("name") : null;
        return new ChatMessage(role, content, images, calls, toolCallId, name);
    }

    @Override
    public String toString() {
        return role.wire + ": " + (content == null ? "" : content) + (hasToolCalls() ? " " + toolCalls : "");
    }
=======
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
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
