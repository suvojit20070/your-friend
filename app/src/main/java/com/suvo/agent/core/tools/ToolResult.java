package com.suvo.agent.core.tools;

import com.suvo.agent.core.llm.ChatMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured outcome of a tool execution. Only tools (and the executor) create results; the model
 * never supplies them, so a "succeeded" status always reflects a backend-confirmed outcome.
 */
public final class ToolResult {

    public enum Status {
        SUCCEEDED, FAILED, CANCELLED, DENIED, UNAVAILABLE, INVALID_ARGUMENTS, TIMED_OUT
    }

    public final Status status;
    public final String message;
    public final JSONObject data;
    public final List<ChatMessage.Image> images;

    private ToolResult(Status status, String message, JSONObject data, List<ChatMessage.Image> images) {
        this.status = status;
        this.message = message == null ? "" : message;
        this.data = data == null ? new JSONObject() : data;
        this.images = images == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(images));
    }

    public static ToolResult ok(String message) {
        return new ToolResult(Status.SUCCEEDED, message, null, null);
    }

    public static ToolResult ok(String message, JSONObject data) {
        return new ToolResult(Status.SUCCEEDED, message, data, null);
    }

    public static ToolResult okWithImage(String message, JSONObject data, ChatMessage.Image image) {
        List<ChatMessage.Image> l = new ArrayList<>();
        l.add(image);
        return new ToolResult(Status.SUCCEEDED, message, data, l);
    }

    public static ToolResult fail(String message) {
        return new ToolResult(Status.FAILED, message, null, null);
    }

    public static ToolResult fail(String message, JSONObject data) {
        return new ToolResult(Status.FAILED, message, data, null);
    }

    public static ToolResult of(Status status, String message) {
        return new ToolResult(status, message, null, null);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /** JSON text returned to the model as the tool message content. */
    public String toModelContent(int maxChars) {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status.name().toLowerCase());
            if (!message.isEmpty()) o.put("message", message);
            if (data.length() > 0) o.put("data", data);
            if (!images.isEmpty()) o.put("images_attached", images.size());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        String s = o.toString();
        if (s.length() > maxChars) {
            // Keep valid JSON by truncating the data payload as a string.
            try {
                String d = data.toString();
                int keep = Math.max(0, maxChars - message.length() - 200);
                JSONObject t = new JSONObject();
                t.put("status", status.name().toLowerCase());
                t.put("message", message);
                t.put("data_truncated", d.substring(0, Math.min(d.length(), keep)));
                t.put("note", "Output truncated from " + d.length() + " characters. Ask for a narrower range if needed.");
                return t.toString();
            } catch (JSONException e) {
                return s.substring(0, maxChars);
            }
        }
        return s;
    }

    @Override
    public String toString() {
        return status + ": " + message;
    }
}
