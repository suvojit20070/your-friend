package com.suvo.agent.device.accessibility;

import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.ToastLog;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Appends the toasts / snackbars / status announcements that appeared during a screen-related tool
 * call to that call's result, so the agent learns e.g. "Message sent" or "Couldn't connect" that it
 * cannot see in the window tree. Messages the agent has not been told about yet (for example a toast
 * that came a moment after the previous tap) are included with the next screen tool result.
 */
public final class ToastAttach implements ToolExecutor.ResultDecorator {

    private static final Set<String> SCREEN_TOOLS = new HashSet<>(Arrays.asList(
            "screen_operation_accessibility", "screen_operation_shell", "screenshot", "launch_app", "open_uri",
            "device_admin"));
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList("read", "get_text", "find", "wait_for"));
    /** How far back an unreported message may come from (an earlier action of this task). */
    private static final long LOOKBACK_MS = 20_000L;

    private final String ownPackage;

    public ToastAttach(String ownPackage) {
        this.ownPackage = ownPackage;
    }

    @Override
    public ToolResult decorate(String toolName, JSONObject args, long startedAtMs, ToolResult result) {
        if (!SCREEN_TOOLS.contains(toolName) || !AgentAccessibilityService.isConnected()) return result;
        boolean acts = !READ_ONLY.contains(args == null ? "" : args.optString("action", ""))
                && !"screenshot".equals(toolName);
        long now = System.currentTimeMillis();
        if (acts && result.succeeded() && now - startedAtMs < 900) {
            // Toasts usually appear 100–500 ms after a tap; give a quick action a moment to show one.
            try {
                Thread.sleep(Math.min(500, 900 - (now - startedAtMs)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = System.currentTimeMillis();
        }
        List<ToastLog.Entry> got = new ArrayList<>();
        for (ToastLog.Entry e : ToastLog.takeUnreported(startedAtMs - LOOKBACK_MS, now)) {
            if (!ownPackage.equals(e.pkg)) got.add(e);
        }
        String note = ToastLog.describe(got, startedAtMs);
        return note == null ? result : result.withNote(note);
    }
}
