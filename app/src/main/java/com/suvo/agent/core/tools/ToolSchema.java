package com.suvo.agent.core.tools;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The function schema advertised to the LLM for one tool.
 * {@code parameters} is a JSON-schema object (as a JSONObject).
 */
public class ToolSchema {
    public final String name;
    public final String description;
    public final JSONObject parameters;
    public final String danger;      // "low" | "medium" | "high"

    public ToolSchema(String name, String description, JSONObject parameters, String danger) {
        this.name = name;
        this.description = description;
        this.parameters = parameters != null ? parameters : emptyParams();
        this.danger = danger == null ? "low" : danger;
    }

    public static JSONObject emptyParams() {
        try {
            return new JSONObject().put("type", "object").put("properties", new JSONObject());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        JSONObject o = new JSONObject();
        o.put("type", "function");
        o.put("function", fn);
        return o;
    }
}
