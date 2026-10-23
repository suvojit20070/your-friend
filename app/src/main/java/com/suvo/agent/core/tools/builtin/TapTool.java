package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONException;
import org.json.JSONObject;

/** Tap the screen — by coordinates, or by the text of a button/element. */
public class TapTool implements Tool {

    @Override public String name() { return "tap"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject props = new JSONObject();
            props.put("x", new JSONObject().put("type", "integer").put("description", "X coordinate (from read_screen)"));
            props.put("y", new JSONObject().put("type", "integer").put("description", "Y coordinate"));
            props.put("text", new JSONObject().put("type", "string").put("description", "Instead of x/y: tap the element containing this text"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props);
            return new ToolSchema(name(),
                    "Tap the screen, either at x,y or on the element whose text you pass.",
                    params, "medium");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Tap the screen.", ToolSchema.emptyParams(), "medium");
        }
    }

    @Override public boolean isAvailable() { return AgentAccessibilityService.isConnected(); }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled.";
        String text = args.optString("text", "");
        if (!text.isEmpty()) {
            return s.clickByText(text) ? "Tapped: " + text : "Error: no tappable element found for '" + text + "'";
        }
        if (args.has("x") && args.has("y")) {
            int x = args.optInt("x"), y = args.optInt("y");
            return s.tap(x, y) ? "Tapped at " + x + "," + y : "Error: tap failed";
        }
        return "Error: provide x and y, or text.";
    }
}
