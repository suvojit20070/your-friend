package com.suvo.agent.core.llm;

import org.json.JSONException;
import org.json.JSONObject;

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
}
