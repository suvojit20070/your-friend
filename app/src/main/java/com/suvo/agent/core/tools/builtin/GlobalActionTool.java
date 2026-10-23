package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Global navigation: back, home, recents, notifications. */
public class GlobalActionTool implements Tool {

    @Override public String name() { return "device_action"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject action = new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("back").put("home").put("recents").put("notifications"));
            JSONObject props = new JSONObject().put("action", action);
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("action"));
            return new ToolSchema(name(), "Navigate the device: back, home, recents, or open notifications.", params, "low");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Device navigation.", ToolSchema.emptyParams(), "low");
        }
    }

    @Override public boolean isAvailable() { return AgentAccessibilityService.isConnected(); }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled.";
        switch (args.optString("action", "")) {
            case "back": return s.back() ? "Went back" : "Error";
            case "home": return s.home() ? "Went home" : "Error";
            case "recents": return s.recents() ? "Opened recents" : "Error";
            case "notifications": return s.notifications() ? "Opened notifications" : "Error";
            default: return "Error: action must be back|home|recents|notifications";
        }
    }
}
