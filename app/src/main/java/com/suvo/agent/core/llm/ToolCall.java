package com.suvo.agent.core.llm;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A tool call requested by the model. Arguments are kept as the raw JSON string the model produced.
 *
 * {@link #extra} keeps any provider-specific fields that came with the call (for example Gemini's
 * {@code extra_content.google.thought_signature}). They are stored and sent back unchanged, because
 * some providers reject the next request when they are missing.
 */
public final class ToolCall {

    /** Keys of a wire tool call that this class models itself; everything else goes into {@link #extra}. */
    static final java.util.Set<String> KNOWN_KEYS = new java.util.HashSet<>(java.util.Arrays.asList("id", "type", "function", "index"));

    public final String id;
    public final String name;
    public final String argumentsJson;
    /** Provider-specific fields of the call, never null. */
    public final JSONObject extra;

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }

    public ToolCall(String id, String name, String argumentsJson, JSONObject extra) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.argumentsJson = argumentsJson == null ? "" : argumentsJson;
        this.extra = extra == null ? new JSONObject() : extra;
    }

    /** Gemini's thought signature for this call, or "" when the provider sent none. */
    public String thoughtSignature() {
        JSONObject ec = extra.optJSONObject("extra_content");
        JSONObject google = ec != null ? ec.optJSONObject("google") : null;
        return google != null ? google.optString("thought_signature", "") : "";
    }

    /** Copy of this call with Gemini's thought signature set (used for history from other providers). */
    public ToolCall withThoughtSignature(String signature) {
        try {
            JSONObject copy = new JSONObject(extra.toString());
            JSONObject ec = copy.optJSONObject("extra_content");
            if (ec == null) {
                ec = new JSONObject();
                copy.put("extra_content", ec);
            }
            JSONObject google = ec.optJSONObject("google");
            if (google == null) {
                google = new JSONObject();
                ec.put("google", google);
            }
            google.put("thought_signature", signature);
            return new ToolCall(id, name, argumentsJson, copy);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses arguments. Empty arguments become {}. Throws with a readable message if the model
     * produced malformed JSON so the error can be returned to the model instead of crashing.
     */
    public JSONObject parseArguments() throws JSONException {
        String s = argumentsJson.trim();
        if (s.isEmpty()) return new JSONObject();
        // Some providers double-encode arguments as a JSON string.
        if (s.startsWith("\"") && s.endsWith("\"")) {
            Object inner = new org.json.JSONTokener(s).nextValue();
            if (inner instanceof String) s = ((String) inner).trim();
        }
        Object v = new org.json.JSONTokener(s).nextValue();
        if (!(v instanceof JSONObject)) {
            throw new JSONException("Tool arguments must be a JSON object");
        }
        return (JSONObject) v;
    }

    public JSONObject toJson() {
        JSONObject fn = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            fn.put("name", name);
            fn.put("arguments", argumentsJson.isEmpty() ? "{}" : argumentsJson);
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!KNOWN_KEYS.contains(k)) o.put(k, extra.get(k));
            }
            o.put("id", id);
            o.put("type", "function");
            o.put("function", fn);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static ToolCall fromJson(JSONObject o) {
        JSONObject fn = o.optJSONObject("function");
        String name = fn != null ? fn.optString("name", "") : o.optString("name", "");
        String args;
        if (fn != null) {
            Object a = fn.opt("arguments");
            args = a == null || a == JSONObject.NULL ? "" : a.toString();
        } else {
            args = "";
        }
        JSONObject extra = new JSONObject();
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!KNOWN_KEYS.contains(k)) {
                try {
                    extra.put(k, o.get(k));
                } catch (JSONException ignored) {
                }
            }
        }
        return new ToolCall(o.optString("id", ""), name, args, extra);
    }

    /** Stable signature used for loop detection. */
    public String signature() {
        String args;
        try {
            args = parseArguments().toString();
        } catch (JSONException e) {
            args = argumentsJson;
        }
        return name + "|" + canonical(args);
    }

    private static String canonical(String json) {
        try {
            Object v = new org.json.JSONTokener(json).nextValue();
            return canonicalValue(v);
        } catch (JSONException e) {
            return json;
        }
    }

    private static String canonicalValue(Object v) throws JSONException {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            java.util.List<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (String k : keys) {
                sb.append(JSONObject.quote(k)).append(':').append(canonicalValue(o.get(k))).append(',');
            }
            return sb.append('}').toString();
        }
        if (v instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) sb.append(canonicalValue(a.get(i))).append(',');
            return sb.append(']').toString();
        }
        if (v instanceof String) return JSONObject.quote((String) v);
        return String.valueOf(v);
    }

    @Override
    public String toString() {
        return name + "(" + argumentsJson + ")#" + id;
    }
}
