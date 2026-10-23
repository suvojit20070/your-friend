package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Type text into the currently focused input field. */
public class InputTextTool implements Tool {

    @Override public String name() { return "type_text"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject props = new JSONObject();
            props.put("text", new JSONObject().put("type", "string"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("text"));
            return new ToolSchema(name(), "Type text into the focused text field on screen.", params, "medium");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Type text.", ToolSchema.emptyParams(), "medium");
        }
    }

    @Override public boolean isAvailable() { return AgentAccessibilityService.isConnected(); }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled.";
        String text = args.optString("text", "");
        if (text.isEmpty()) return "Error: text required.";
        return s.inputText(text) ? "Typed." : "Error: no focused field to type into.";
    }
}
