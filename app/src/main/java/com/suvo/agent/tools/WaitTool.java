package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * {@code wait}: pauses the task for a while and then continues — for things that take time
 * outside the agent's control (a GitHub Pages / CI build, a deploy, a download, an app loading,
 * a message reply, a rate limit). Stoppable at any moment; the chat shows the countdown.
 */
public final class WaitTool extends BaseTool {

    public static final int MAX_SECONDS = 600;

    public WaitTool(AppGraph g) {
        super(g, ToolSpec.builder("wait")
                .description("Wait for a number of seconds, then continue the task. Use it when something needs time before you "
                        + "check again (a GitHub Pages / CI build or deploy, a download, an app or page loading, a reply, a "
                        + "rate limit) instead of ending the task or checking in a tight loop. After waiting, check the result "
                        + "with a tool. Max " + MAX_SECONDS + " s per call; call it again to wait longer.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"seconds\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":" + MAX_SECONDS + "},"
                        + "\"reason\":{\"type\":\"string\",\"maxLength\":200,\"description\":\"What you are waiting for (shown to the user)\"}"
                        + "},\"required\":[\"seconds\"],\"additionalProperties\":false}")
                .risk(RiskLevel.READ_ONLY).backend("local").timeoutMs((MAX_SECONDS + 30) * 1000L).cancellable(true).build());
    }

    @Override
    public String describeCall(org.json.JSONObject args) {
        return "Wait " + args.optInt("seconds") + " s" + (args.optString("reason").isEmpty() ? "" : ": " + args.optString("reason"));
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        int seconds = (int) Math.max(1, Math.min(MAX_SECONDS, inv.num("seconds", 10)));
        String reason = inv.str("reason").trim();
        long end = System.currentTimeMillis() + seconds * 1000L;
        long lastShown = -1;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            if (inv.cancel != null && inv.cancel.isCancelled()) {
                return ToolResult.fail("Stopped while waiting");
            }
            long s = (left + 999) / 1000;
            if (s != lastShown && (s % 5 == 0 || s <= 5 || lastShown < 0)) {
                inv.progress.update("Waiting " + s + " s" + (reason.isEmpty() ? "" : " — " + reason));
                lastShown = s;
            }
            Thread.sleep(Math.min(250, left));
        }
        // The time makes every result different, so waiting again is not mistaken for a loop.
        String at = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        return ToolResult.ok("Waited " + seconds + " s (now " + at + ")" + (reason.isEmpty() ? "" : " for: " + reason)
                + ". Check the result now.");
    }
}
