package com.suvo.agent.agent;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Your Friend — Original project by Suvo.
 *
 * "Keep screen on" while a task runs, even after the user leaves the app: a 1×1 px invisible
 * window floats over everything with FLAG_KEEP_SCREEN_ON, from the start of the task until it ends.
 * (A screen wake lock is ignored by many phones now; a window that asks to keep the screen on is
 * honoured everywhere.) It needs "Display over other apps"; without it the old wake lock is used.
 *
 * The window takes no touches, has no focus and draws nothing. It is marked as a helper ({@link #TAG})
 * and hidden from accessibility, so the agent never mistakes it for part of a screen.
 */
public final class ScreenKeeper {

    private ScreenKeeper() {
    }

    /** Description of the helper view, so screen readers (and the agent) can recognise and skip it. */
    public static final String TAG = "yf:keep-screen-on (Your Friend helper, not part of the screen)";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View view;

    public static boolean isHelper(CharSequence description) {
        return description != null && TAG.contentEquals(description);
    }

    /** Puts the helper window up; false when not allowed (no overlay permission). Any thread. */
    public static boolean show(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) return false;
        main.post(() -> {
            if (view != null) return;
            try {
                View v = new View(app);
                v.setContentDescription(TAG);
                v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                v.setKeepScreenOn(true);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = 0;
                lp.y = 0;
                lp.setTitle("YourFriend keep-screen-on");
                ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).addView(v, lp);
                view = v;
            } catch (RuntimeException e) {
                view = null;
            }
        });
        return true;
    }

    /** Removes the helper window (task over, or the setting was switched off). Any thread. */
    public static void hide(Context ctx) {
        Context app = ctx.getApplicationContext();
        main.post(() -> {
            if (view == null) return;
            try {
                ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
            } catch (RuntimeException ignored) {
            }
            view = null;
        });
    }
}
