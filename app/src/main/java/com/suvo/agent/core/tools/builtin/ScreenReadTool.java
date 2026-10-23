package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.service.AgentAccessibilityService;

import org.json.JSONObject;

/** Read what is currently on the screen (text + tappable elements with coordinates). */
public class ScreenReadTool implements Tool {

    @Override public String name() { return "read_screen"; }

    @Override public ToolSchema schema() {
        return new ToolSchema(name(),
                "Read what is currently on the screen: visible text and tappable elements with "
                        + "their screen coordinates (use these x,y with tap). Call before acting on an app.",
                ToolSchema.emptyParams(), "low");
    }

    @Override public boolean isAvailable() { return AgentAccessibilityService.isConnected(); }

    @Override public String run(JSONObject args) {
        AgentAccessibilityService s = AgentAccessibilityService.get();
        if (s == null) return "Error: accessibility not enabled. Ask the user to turn on 'Your Friend' in Accessibility settings.";
        return s.dumpScreen();
    }
}
