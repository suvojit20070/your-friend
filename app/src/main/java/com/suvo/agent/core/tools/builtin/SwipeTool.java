package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONException;
import org.json.JSONObject;

/** Swipe / scroll gesture. */
public class SwipeTool implements Tool {

    @Override public String name() { return "swipe"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject props = new JSONObject();
            for (String k : new String[]{"x1", "y1", "x2", "y2"})
                props.put(k, new JSONObject().put("type", "integer"));
            props.put("durationMs", new JSONObject().put("type", "integer").put("description", "default 300"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new org.json.JSONArray().put("x1").put("y1").put("x2").put("y2"));
            return new ToolSchema(name(), "Swipe/scroll from x1,y1 to x2,y2.", params, "medium");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Swipe.", ToolSchema.emptyParams(), "medium");
        }
    }

    @Override public boolean isAvailable() { return AgentAccessibilityService.isConnected(); }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled.";
        boolean ok = s.swipe(args.optInt("x1"), args.optInt("y1"),
                args.optInt("x2"), args.optInt("y2"), args.optInt("durationMs", 300));
        return ok ? "Swiped" : "Error: swipe failed";
    }
}
