package com.suvo.agent.core.llm;

import org.json.JSONException;
import org.json.JSONObject;

<<<<<<< HEAD
/** A tool call requested by the model. Arguments are kept as the raw JSON string the model produced. */
public final class ToolCall {

    public final String id;
    public final String name;
    public final String argumentsJson;

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.argumentsJson = argumentsJson == null ? "" : argumentsJson;
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
        return new ToolCall(o.optString("id", ""), name, args);
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
=======
/** A single function/tool call the model asked for. */
public class ToolCall {
    public String id;
    public String name;
    public String argumentsJson = "{}";   // raw JSON string of arguments

    public ToolCall() {}

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id;
        this.name = name;
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }

    public JSONObject argsAsJson() {
        try {
            String a = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
            return new JSONObject(a);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("arguments", argumentsJson);
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", "function");
        o.put("function", fn);
        return o;
    }
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
}
