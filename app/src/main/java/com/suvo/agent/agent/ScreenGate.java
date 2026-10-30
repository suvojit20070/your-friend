package com.suvo.agent.agent;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Your Friend — Original project by Suvo.
 *
 * Lets a screen of the app that sits on top of other apps (the assistant card) step aside right
 * before the agent uses a tool that reads or operates the screen — not earlier. The agent's thread
 * waits (briefly) until the card is gone, so taps and screen reads hit the app underneath and the
 * card never shows up in the screen tree or a screenshot.
 */
public final class ScreenGate {

    private ScreenGate() {
    }

    /** Main thread: get out of the way, then run {@code done}. */
    public interface Hider {
        void hide(Runnable done);
    }

    /** Tools that look at or act on what is on screen. */
    private static final Set<String> SCREEN_TOOLS = new HashSet<>(Arrays.asList(
            "screen_operation_accessibility", "screen_operation_shell", "screenshot", "launch_app", "open_uri"));

    private static volatile Hider hider;

    public static void set(Hider h) {
        hider = h;
    }

    public static void clear(Hider h) {
        if (hider == h) hider = null;
    }

    public static boolean isScreenTool(String name) {
        return name != null && SCREEN_TOOLS.contains(name);
    }

    /** Called on the agent's thread just before a tool runs. */
    public static void beforeTool(String toolName) {
        Hider h = hider;
        if (h == null || !isScreenTool(toolName)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) return; // cannot wait here
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                h.hide(done::countDown);
            } catch (RuntimeException e) {
                done.countDown();
            }
        });
        try {
            if (done.await(1500, TimeUnit.MILLISECONDS)) Thread.sleep(350); // let the window animation finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
