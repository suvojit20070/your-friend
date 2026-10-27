package com.suvo.agent.device;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import com.suvo.agent.AppGraph;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Your Friend — Original project by Suvo.
 *
 * Shows the user where the agent is about to touch: a cursor glides to the point, then a ripple
 * marks the tap (a trail for swipes). The overlay never takes touches, so gestures pass through.
 *
 * It uses the accessibility service's own overlay type when the service is on (no extra permission),
 * and falls back to "Display over other apps". With neither, it does nothing.
 */
public final class TapIndicator {

    public static final String SETTING = "show_tap_cursor";

    private static final long MOVE_MS = 260;
    private static final long LINGER_MS = 2200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static CursorView view;
    private static WindowManager viewWm;
    private static final Runnable REMOVE = TapIndicator::removeNow;

    private TapIndicator() {
    }

    /** Shows a tap at (x, y) and waits until the cursor has arrived, so the user sees it first. */
    public static void tap(int x, int y, boolean longPress) {
        show(x, y, x, y, longPress ? CursorView.LONG : CursorView.TAP);
    }

    public static void swipe(int x1, int y1, int x2, int y2) {
        show(x1, y1, x2, y2, CursorView.SWIPE);
    }

    /** Removes the cursor right away (before a screenshot, so the agent sees the real screen). */
    public static void hideForCapture() {
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            MAIN.removeCallbacks(REMOVE);
            removeNow();
            done.countDown();
        });
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                done.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean enabled() {
        try {
            return AppGraph.get().settings.bool(SETTING, true);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void show(int x1, int y1, int x2, int y2, int kind) {
        com.suvo.agent.character.FloatingCompanionService.lookAtScreen(x2, y2);
        if (!enabled()) return;
        CountDownLatch arrived = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                if (!ensureView()) {
                    arrived.countDown();
                    return;
                }
                MAIN.removeCallbacks(REMOVE);
                view.animateTo(x1, y1, x2, y2, kind, arrived::countDown);
                MAIN.postDelayed(REMOVE, MOVE_MS + LINGER_MS);
            } catch (RuntimeException e) {
                arrived.countDown();
            }
        });
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                arrived.await(MOVE_MS + 400, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean ensureView() {
        if (view != null) return true;
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        Context ctx;
        int type;
        if (svc != null) {
            ctx = svc;
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else {
            ctx = AppGraph.get().ctx;
            if (!Settings.canDrawOverlays(ctx)) return false;
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        CursorView v = new CursorView(ctx);
        wm.addView(v, lp);
        view = v;
        viewWm = wm;
        return true;
    }

    private static void removeNow() {
        if (view != null && viewWm != null) {
            try {
                viewWm.removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        viewWm = null;
    }

    /** Draws the cursor, ripple and swipe trail. Coordinates are screen pixels. */
    static final class CursorView extends View {
        static final int TAP = 0, LONG = 1, SWIPE = 2;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.drawable.Drawable cursorDrawable;
        private final float d;
        private final int[] loc = new int[2];

        private float cx = -1, cy = -1;
        private float sx, sy, ex, ey;
        private int kind;
        private float ripple = -1f;
        private ValueAnimator anim;

        CursorView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            cursorDrawable = androidx.core.content.ContextCompat.getDrawable(c, com.suvo.agent.R.drawable.ic_cursor);
            fill.setColor(Color.WHITE);
            stroke.setColor(0xFF1B1B2F);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.6f * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3f * d);
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeCap(Paint.Cap.ROUND);
            trail.setStrokeWidth(6f * d);
            trail.setColor(0x997C5CFF);
        }

        void animateTo(int x1, int y1, int x2, int y2, int kind, Runnable onArrived) {
            if (anim != null) anim.cancel();
            this.kind = kind;
            this.sx = x1;
            this.sy = y1;
            this.ex = x2;
            this.ey = y2;
            final float fromX = cx < 0 ? x1 : cx;
            final float fromY = cy < 0 ? y1 - 120 * d : cy;
            ripple = -1f;
            final boolean swipe = kind == SWIPE;
            ValueAnimator move = ValueAnimator.ofFloat(0f, 1f);
            move.setDuration(MOVE_MS);
            move.setInterpolator(new DecelerateInterpolator());
            move.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                cx = fromX + (sx - fromX) * t;
                cy = fromY + (sy - fromY) * t;
                invalidate();
            });
            move.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    onArrived.run();
                    ValueAnimator act = ValueAnimator.ofFloat(0f, 1f);
                    act.setDuration(swipe ? 420 : kind == LONG ? 800 : 520);
                    act.addUpdateListener(b -> {
                        float t = (float) b.getAnimatedValue();
                        ripple = t;
                        if (swipe) {
                            cx = sx + (ex - sx) * t;
                            cy = sy + (ey - sy) * t;
                        }
                        invalidate();
                    });
                    anim = act;
                    act.start();
                }
            });
            anim = move;
            move.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cx < 0) return;
            // Window coordinates may be offset from the screen (status bar, cutout).
            getLocationOnScreen(loc);
            c.save();
            c.translate(-loc[0], -loc[1]);
            if (kind == SWIPE && ripple >= 0f) {
                c.drawLine(sx, sy, cx, cy, trail);
            }
            if (ripple >= 0f && kind != SWIPE) {
                float r = (14 + 30 * ripple) * d;
                int alpha = (int) (230 * (1f - ripple));
                ring.setColor(Color.argb(alpha, 124, 92, 255));
                c.drawCircle(sx, sy, r, ring);
                fill.setColor(Color.argb((int) (120 * (1f - ripple)), 124, 92, 255));
                c.drawCircle(sx, sy, 10 * d, fill);
                fill.setColor(Color.WHITE);
            }
            c.save();

            float size = 30 * d;
            float scale = size / 128f;

            float left = cx - 53.82f * scale;
            float top = cy - 52.31f * scale;

            cursorDrawable.setBounds(
               Math.round(left),
               Math.round(top),
               Math.round(left + size),
               Math.round(top + size)
            );

            cursorDrawable.draw(c);
            c.restore();
            c.restore();
        }
    }
}
