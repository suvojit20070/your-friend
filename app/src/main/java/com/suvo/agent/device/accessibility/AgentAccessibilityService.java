package com.suvo.agent.device.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.suvo.agent.core.util.ToastLog;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The accessibility service that lets the agent read the screen and act on it. It does nothing on
 * its own: actions only happen when an approved tool call asks for them. The user enables it in
 * Settings > Accessibility; Android shows its own disclosure when enabling.
 */
public final class AgentAccessibilityService extends AccessibilityService {

    public interface Listener {
        void onConnectionChanged(boolean connected);
    }

    private static volatile AgentAccessibilityService instance;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String foregroundPackage = "";
    private volatile String foregroundClass = "";
    private volatile long lastContentChange;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static AgentAccessibilityService get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        for (Listener l : listeners) l.onConnectionChanged(true);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        for (Listener l : listeners) l.onConnectionChanged(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        for (Listener l : listeners) l.onConnectionChanged(false);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null) foregroundPackage = pkg.toString();
            if (cls != null) foregroundClass = cls.toString();
            lastContentChange = System.currentTimeMillis();
            if (cls != null && cls.toString().contains("Toast")) {
                ToastLog.record(ToastLog.TOAST, str(pkg), joined(event), System.currentTimeMillis());
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentChange = System.currentTimeMillis();
            if ((event.getContentChangeTypes() & AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) != 0
                    || String.valueOf(event.getClassName()).contains("Snackbar")) {
                recordSnackbar(event);
            }
        } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // A toast (system or custom) arrives here without a Notification; real notifications are skipped.
            if (!(event.getParcelableData() instanceof android.app.Notification)) {
                ToastLog.record(ToastLog.TOAST, str(event.getPackageName()), joined(event), System.currentTimeMillis());
            }
        } else if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            ToastLog.record(ToastLog.ANNOUNCEMENT, str(event.getPackageName()), joined(event), System.currentTimeMillis());
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            // The floating character glances at whatever the user taps (only the position is used).
            android.view.accessibility.AccessibilityNodeInfo src = event.getSource();
            if (src != null && !getPackageName().contentEquals(String.valueOf(event.getPackageName()))) {
                android.graphics.Rect r = new android.graphics.Rect();
                src.getBoundsInScreen(r);
                if (!r.isEmpty()) com.suvo.agent.character.FloatingCompanionService.lookAtScreen(r.centerX(), r.centerY());
            }
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    private static String joined(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence c : e.getText()) {
            if (c == null || c.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c);
        }
        if (sb.length() == 0 && e.getContentDescription() != null) sb.append(e.getContentDescription());
        return sb.toString();
    }

    /** Material snackbars and other "panes" that pop in: record their visible text (not edit fields). */
    private void recordSnackbar(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return;
        // Only snackbar-like panes; other panes (dialogs, sheets) are already in the screen tree.
        if (!str(src.getClassName()).contains("Snackbar") && !str(e.getClassName()).contains("Snackbar")) return;
        StringBuilder sb = new StringBuilder();
        collectText(src, sb, 0);
        ToastLog.record(ToastLog.SNACKBAR, str(e.getPackageName()), sb.toString(), System.currentTimeMillis());
    }

    private static void collectText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 4 || sb.length() > 300) return;
        if (!n.isEditable() && !n.isPassword() && n.getText() != null && n.getText().length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(n.getText());
        }
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), sb, depth + 1);
    }

    @Override
    public void onInterrupt() {
        // Nothing continuous to interrupt.
    }

    public String foregroundPackage() {
        return foregroundPackage;
    }

    public String foregroundClass() {
        return foregroundClass;
    }

    public long lastContentChange() {
        return lastContentChange;
    }

    public AccessibilityNodeInfo root() {
        return getRootInActiveWindow();
    }

    /** Dispatches a gesture and waits for the system to confirm completion. */
    public boolean dispatchAndWait(GestureDescription gesture, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean dispatched = new AtomicBoolean(false);
        main.post(() -> {
            boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    completed.set(true);
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    latch.countDown();
                }
            }, main);
            dispatched.set(ok);
            if (!ok) latch.countDown();
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return dispatched.get() && completed.get();
    }

    public boolean global(int action) {
        return performGlobalAction(action);
    }

    /** Whether this Android version supports {@link #captureScreen(long)} (API 30+). */
    public static boolean canCaptureScreen() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /**
     * Captures the screen through the accessibility screenshot API (Android 11+). Secure windows
     * (FLAG_SECURE, e.g. banking apps) come back black or fail; that is enforced by Android.
     */
    public Bitmap captureScreen(long timeoutMs) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw new UnsupportedOperationException("Requires Android 11");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                HardwareBuffer hb = result.getHardwareBuffer();
                ColorSpace cs = result.getColorSpace();
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                if (hw != null) out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                hb.close();
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                err.set(screenshotError(errorCode));
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new Exception("Screenshot timed out");
        if (out.get() == null) throw new Exception(err.get() == null ? "Screenshot failed" : err.get());
        return out.get();
    }

    private static String screenshotError(int code) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "Screenshots requested too quickly; wait a second and retry";
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return "The current screen is protected (secure window) and cannot be captured";
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "Accessibility screenshot access is not available";
            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "Invalid display";
            default:
                return "Screenshot failed (code " + code + ")";
        }
    }
}
