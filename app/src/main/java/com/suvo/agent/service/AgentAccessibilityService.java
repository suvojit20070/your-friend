package com.suvo.agent.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The backbone of device control. Lets the agent:
 *   - read what's on screen (text + clickable elements)
 *   - tap / long-press / swipe anywhere via gestures
 *   - type text into the focused field
 *   - global actions: back, home, recents, notifications
 *   - take a screenshot (API 30+)
 *
 * The capability tools call these helpers. All are no-ops (return false/empty)
 * when the service is not connected, so nothing crashes.
 */
public class AgentAccessibilityService extends AccessibilityService {

    private static volatile AgentAccessibilityService INSTANCE;

    public static AgentAccessibilityService get() { return INSTANCE; }
    public static boolean isConnected() { return INSTANCE != null; }

    private volatile CharSequence currentPackage = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = event.getPackageName();
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        INSTANCE = null;
        return super.onUnbind(intent);
    }

    public String foregroundApp() {
        return currentPackage == null ? "" : currentPackage.toString();
    }

    // ---------------------------------------------------------------- read screen
    /** A compact text dump of the current screen: visible text + clickable labels with bounds. */
    public String dumpScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(screen not available)";
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(foregroundApp()).append('\n');
        List<String> lines = new ArrayList<>();
        walk(root, lines, 0);
        int max = 120;
        for (int i = 0; i < lines.size() && i < max; i++) sb.append(lines.get(i)).append('\n');
        if (lines.size() > max) sb.append("… (").append(lines.size() - max).append(" more)\n");
        try { root.recycle(); } catch (Exception ignore) {}
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 40) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null ? text.toString() : (desc != null ? desc.toString() : null);
        if (label != null && !label.trim().isEmpty()) {
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            String tag = node.isClickable() ? "[tap]" : "";
            out.add(tag + " \"" + label.trim() + "\" @" + r.centerX() + "," + r.centerY());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) { walk(c, out, depth + 1); try { c.recycle(); } catch (Exception ignore) {} }
        }
    }

    /** Find a clickable node whose text/desc contains {@code query} and click it. */
    public boolean clickByText(String query) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        boolean ok = clickMatch(root, query.toLowerCase());
        try { root.recycle(); } catch (Exception ignore) {}
        return ok;
    }

    private boolean clickMatch(AccessibilityNodeInfo node, String q) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String s = (t != null ? t : (d != null ? d : "")).toString().toLowerCase();
        if (!s.isEmpty() && s.contains(q)) {
            AccessibilityNodeInfo target = node;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) {
                boolean ok = clickMatch(c, q);
                try { c.recycle(); } catch (Exception ignore) {}
                if (ok) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- gestures
    public boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchBlocking(g);
    }

    public boolean longPress(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 600)).build();
        return dispatchBlocking(g);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, durationMs))).build();
        return dispatchBlocking(g);
    }

    private boolean dispatchBlocking(GestureDescription g) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        boolean dispatched = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { done.set(true); latch.countDown(); }
            @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
        }, null);
        if (!dispatched) return false;
        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        return done.get();
    }

    // ---------------------------------------------------------------- text input
    public boolean inputText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        boolean ok = false;
        if (focus != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            ok = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            try { focus.recycle(); } catch (Exception ignore) {}
        }
        try { root.recycle(); } catch (Exception ignore) {}
        return ok;
    }

    // ---------------------------------------------------------------- global actions
    public boolean back()    { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean home()    { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean recents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean notifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }

    // ---------------------------------------------------------------- screenshot
    public interface ShotCallback { void onShot(android.graphics.Bitmap bmp, String error); }

    /** Take a screenshot (API 30+). Bitmap returned on a background executor. */
    public void screenshot(ShotCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb.onShot(null, "screenshot needs Android 11+");
            return;
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        try {
                            android.graphics.Bitmap bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                                    result.getHardwareBuffer(), result.getColorSpace());
                            android.graphics.Bitmap copy = bmp != null
                                    ? bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : null;
                            try { result.getHardwareBuffer().close(); } catch (Exception ignore) {}
                            cb.onShot(copy, copy == null ? "null bitmap" : null);
                        } catch (Exception e) {
                            cb.onShot(null, e.getMessage());
                        }
                    }
                    @Override public void onFailure(int errorCode) {
                        cb.onShot(null, "screenshot failed code " + errorCode);
                    }
                });
    }
}
