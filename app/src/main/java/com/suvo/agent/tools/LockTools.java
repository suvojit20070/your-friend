package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.device.lock.ScreenUnlocker;
import com.suvo.agent.device.lock.UnlockSecret;

/**
 * Your Friend — Original project by Suvo.
 *
 * {@code lock_screen} and {@code unlock_screen}: for when the phone lies locked and the user asks by
 * voice for something that needs the phone open (an app, the screen). Many things work without
 * unlocking (calls, alarms, music, answers): unlock only when the job really needs the screen.
 */
public final class LockTools {

    private LockTools() {
    }

    public static final class Lock extends BaseTool {
        public Lock(AppGraph g) {
            super(g, ToolSpec.builder("lock_screen")
                    .description("Lock the phone (screen off). Use when the user asks, or after finishing a job you unlocked the "
                            + "phone for if the user wants it locked again.")
                    .schema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                    .risk(RiskLevel.LOW).backend("local").timeoutMs(15_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            ScreenUnlocker.Result r = ScreenUnlocker.lock(g.ctx);
            return r.message.startsWith("Cannot") ? ToolResult.fail(r.message) : ToolResult.ok(r.message);
        }
    }

    public static final class Unlock extends BaseTool {
        public Unlock(AppGraph g) {
            super(g, ToolSpec.builder("unlock_screen")
                    .description("Wake and unlock the phone when it is locked and the job needs the screen (opening an app, "
                            + "reading or tapping the screen). Not needed for calls, alarms, timers, music or plain answers. "
                            + "With a saved PIN / pattern (user setting) it unlocks by itself; without one it shows the unlock "
                            + "screen and the USER must unlock — then tell them so and wait (use wait, then check). "
                            + "Check first with device_status or just call it: it returns at once when already unlocked.")
                    .schema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                    .risk(RiskLevel.LOW).backend("local").timeoutMs(30_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            inv.progress.update("Unlocking…");
            ScreenUnlocker.Result r = ScreenUnlocker.unlock(g.ctx);
            if (r.unlocked) return ToolResult.ok(r.message);
            return ToolResult.fail(r.message + " (saved unlock: " + UnlockSecret.method(g.ctx) + ")");
        }
    }
}
