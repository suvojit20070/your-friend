package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Your Friend — Original project by Suvo.
 *
 * Flat tonal surface in squircle (G2) shape: settings group cards (28dp, surfaceContainerLow),
 * list rows, code blocks, chips, and the user's chat bubble — which has its bottom-end corner
 * tucked in with a small rounded tail pointing at the right edge.
 */
public final class TonalDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii;
    private final float tailW, tailH;
    private int color;
    private int pressedOverlay;
    private boolean pressed;
    private float pressT;
    private android.animation.ValueAnimator anim;

    /** All corners {@code radiusPx}; -1 = capsule. */
    public TonalDrawable(int color, float radiusPx) {
        this(color, new float[]{radiusPx, radiusPx, radiusPx, radiusPx}, 0, 0);
    }

    /** @param radii top-left, top-right, bottom-right, bottom-left; tail size 0 = no tail */
    public TonalDrawable(int color, float[] radii, float tailW, float tailH) {
        this.color = color;
        this.radii = radii;
        this.tailW = tailW;
        this.tailH = tailH;
        paint.setStyle(Paint.Style.FILL);
    }

    /** The user's chat bubble: 24dp corners, bottom-end 0 with a 5 x 8 dp tail. */
    public static TonalDrawable userBubble(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        float r = 24 * d;
        return new TonalDrawable(Palette.of(ctx).userBubble(), new float[]{r, r, 0, r}, 5 * d, 8 * d);
    }

    /** Row background that fades to onSurface 8% while pressed (500 ms), instead of a ripple. */
    public TonalDrawable pressable(int overlay) {
        this.pressedOverlay = overlay;
        return this;
    }

    public void setColor(int c) {
        color = c;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return pressedOverlay != 0;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (pressedOverlay == 0) return false;
        boolean p = false;
        for (int s : state) if (s == android.R.attr.state_pressed) p = true;
        if (p == pressed) return false;
        pressed = p;
        if (anim != null) anim.cancel();
        anim = android.animation.ValueAnimator.ofFloat(pressT, p ? 1f : 0f);
        anim.setDuration(p ? 120 : 500);
        anim.addUpdateListener(a -> {
            pressT = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        anim.start();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        rect.set(b.left, b.top, b.right - tailW, b.bottom);
        float budget = Math.min(rect.width(), rect.height()) / 2f;
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) r[i] = radii[i] < 0 ? budget : radii[i];
        Squircle.build(path, rect, r, Squircle.SMOOTHING);
        if (tailW > 0 && tailH > 0) {
            // A small hook from the square bottom-end corner out to the right edge.
            Path tail = new Path();
            float x = rect.right, y = rect.bottom;
            tail.moveTo(x - 1, y - tailH);
            tail.cubicTo(x, y - tailH * 0.35f, x + tailW * 0.4f, y, x + tailW, y);
            tail.lineTo(x - 1, y);
            tail.close();
            path.op(tail, Path.Op.UNION);
        }
    }

    @Override
    public void draw(Canvas c) {
        paint.setColor(color);
        c.drawPath(path, paint);
        if (pressT > 0.001f && pressedOverlay != 0) {
            paint.setColor(Palette.alpha(pressedOverlay, (pressedOverlay >>> 24) / 255f * pressT));
            c.drawPath(path, paint);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        float r = radii[0] < 0 ? Math.min(b.width(), b.height()) / 2f : radii[0];
        outline.setRoundRect(b.left, b.top, b.right - (int) tailW, b.bottom, r);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
