package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.ui.AppScreens;

import org.json.JSONArray;
import org.json.JSONObject;

/** Lets the agent read the guide to its own app, so it can tell the user where things are. */
public final class AppGuideTools {

    private AppGuideTools() {
    }

    public static final class LoadMyApp extends BaseTool {
        public LoadMyApp(AppGraph g) {
            super(g, ToolSpec.builder("load_my_app")
                    .description("Load the guide to this app (Your Friend): every screen, where it is, and what settings or features it "
                            + "contains. Use it when the user asks where a setting or feature is, how to change something in the app, or how "
                            + "to use the app. Optional query narrows it (e.g. \"voice\", \"memory limit\"). The guide explains how to give "
                            + "the user a tappable button: write a Markdown link like [Open Voice](yf://open/voice) in your answer and the "
                            + "chat turns it into a button that opens that screen.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"query\":{\"type\":\"string\",\"description\":\"What the user is looking for (optional)\"}"
                            + "},\"additionalProperties\":false}")
                    .risk(RiskLevel.READ_ONLY).timeoutMs(10_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String query = inv.str("query");
            String guide = AppScreens.guide(g.ctx, query);
            JSONArray keys = new JSONArray();
            for (AppScreens.Screen s : AppScreens.ALL) keys.put(s.key);
            JSONObject data = J.obj("guide", guide, "screen_keys", keys);
            return ToolResult.ok("App guide loaded" + (query.isEmpty() ? "" : " for \"" + query + "\""), data);
        }
    }
}
