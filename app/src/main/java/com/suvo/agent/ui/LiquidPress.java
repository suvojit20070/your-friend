package com.suvo.agent.ui;

import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * The liquid press, as a foreground drawable so it never steals touches (the view's own click,
 * long-press and scroll handling stay untouched — the framework already reports the pressed state
 * and the finger position to foreground drawables).
 *
 * <ul>
 *   <li>LIQUID: {@code pressProgress} springs 0 → 1 (damping 0.5, stiffness 300). The view swells by
 *   {@code pressScale / height}, leans toward the finger ({@code minDim * tanh(damping * offset /
 *   minDim)}), stretches along the drag direction, and on release wobbles back to where the finger
 *   started. A white wash (8%) and a radial glow under the finger (15%, radius 1.5 x min side) are
 *   added with the PLUS blend; corner buttons also get a soft inner shadow at the top edge.</li>
 *   <li>SQUISH: scale to 0.92 / 0.95 / 0.88 / 0.985 (damping 0.5, stiffness 200).</li>
 *   <li>ROW: the background fades to onSurface 8% (500 ms), no ripple.</li>
 * </ul>
 * Every press gives a light CONTEXT_CLICK haptic.
 */
public final class LiquidPress extends Drawable {

    public static final class Style {
        final int mode; // 0 liquid, 1 squish, 2 row
        final float pressScaleDp, maxDragDp, damping, squish;
        final boolean glow, innerShadow;

        Style(int mode, float pressScaleDp, float maxDragDp, float damping, boolean glow, boolean innerShadow, float squish) {
            this.mode = mode;
            this.pressScaleDp = pressScaleDp;
            this.maxDragDp = maxDragDp;
            this.damping = damping;
            this.glow = glow;
            this.innerShadow = innerShadow;
            this.squish = squish;
        }
    }

    public static final Style CORNER = new Style(0, 24, 4.8f, 0.05f, true, true, 1);
    public static final Style CAPSULE = new Style(0, 4, 4, 0.05f, true, false, 1);
    public static final Style COMPOSER = new Style(0, 3, 3, 0.05f, true, false, 1);
    public static final Style FIELD = new Style(0, 3, 3, 0.05f, true, false, 1);
    public static final Style POPOVER = new Style(0, 6, 6, 0.08f, false, false, 1);
    public static final Style CIRCLE = new Style(1, 0, 0, 0, false, false, 0.92f);
    public static final Style TEXT = new Style(1, 0, 0, 0, false, false, 0.95f);
    public static final Style ICON = new Style(1, 0, 0, 0, false, false, 0.88f);
    public static final Style LARGE = new Style(1, 0, 0, 0, false, false, 0.985f);
    public static final Style ROW = new Style(2, 0, 0, 0, false, false, 1);

    /** Gives {@code v} the press of {@code s} (replaces any previous one). */
    public static void attach(View v, Style s) {
        if (v == null) return;
        v.setTag(R.id.fx_press, Boolean.TRUE);
        v.setForeground(new LiquidPress(v, s));
    }

    private final View view;
    private final Style style;
    private final float density;
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint radial = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private Path innerPath;
    private Fx.Spring press, backX, backY;
    private boolean pressed;
    private float progress, startX, startY, curX, curY, hotX, hotY;
    private float baseTx, baseTy;
    private boolean based;

    private LiquidPress(View v, Style s) {
        view = v;
        style = s;
        density = v.getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 29) {
            wash.setBlendMode(BlendMode.PLUS);
            radial.setBlendMode(BlendMode.PLUS);
        }
        inner.setColor(Palette.alpha(Color.BLACK, 0.20f));
        if (Build.VERSION.SDK_INT >= 28) inner.setMaskFilter(new BlurMaskFilter(4 * density, BlurMaskFilter.Blur.NORMAL));
        rowPaint.setColor(Palette.of(v.getContext()).pressed());
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public void setHotspot(float x, float y) {
        // Remember the finger; only while pressed does it move the element. (The framework keeps
        // reporting moves after release — using those would leave the element shifted.)
        hotX = x;
        hotY = y;
        if (pressed) {
            curX = x;
            curY = y;
            apply();
        }
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean p = false, enabled = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) p = true;
            if (s == android.R.attr.state_enabled) enabled = true;
        }
        if (!enabled) p = false;
        if (p == pressed) return false;
        pressed = p;
        if (press == null) {
            float stiff = style.mode == 1 ? 200f : style.mode == 2 ? 60f : 300f;
            float damp = style.mode == 2 ? 1f : 0.5f;
            press = new Fx.Spring(0f, stiff, damp, v -> {
                progress = v;
                apply();
            });
        }
        if (p) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            if (!based || progress < 0.001f) {
                baseTx = view.getTranslationX();
                baseTy = view.getTranslationY();
                based = true;
            }
            if (backX != null) backX.cancel();
            if (backY != null) backY.cancel();
            startX = curX = hotX;
            startY = curY = hotY;
            if (style.mode == 0) Fx.unclipParents(view, 3);
            press.animateTo(1f);
        } else {
            press.animateTo(0f);
            if (style.mode == 0) {
                // Wobble back to where the finger started.
                backX = new Fx.Spring(curX, 300f, 0.5f, v -> {
                    curX = v;
                    apply();
                });
                backY = new Fx.Spring(curY, 300f, 0.5f, v -> {
                    curY = v;
                    apply();
                });
                backX.animateTo(startX);
                backY.animateTo(startY);
                backX.onEnd(this::settle);
            }
            press.onEnd(() -> {
                if (!pressed) settle();
            });
        }
        return true;
    }

    /** At rest: exactly back to where the element was (no drift from rounding or a cut spring). */
    private void settle() {
        if (pressed || progress > 0.002f) return;
        if (backX != null && backX.value() != startX) return;
        curX = startX;
        curY = startY;
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (style.mode == 0) {
            view.setTranslationX(baseTx);
            view.setTranslationY(baseTy);
        }
        invalidateSelf();
    }

    private void apply() {
        float w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        float p = Math.max(0f, progress);
        if (style.mode == 1) {
            float s = 1f + (style.squish - 1f) * p;
            view.setScaleX(s);
            view.setScaleY(s);
        } else if (style.mode == 0) {
            float min = Math.min(w, h), max = Math.max(w, h);
            float ox = curX - startX, oy = curY - startY;
            float scale = 1f + (style.pressScaleDp * density / h) * p;
            view.setTranslationX(baseTx + min * (float) Math.tanh(style.damping * ox / min));
            view.setTranslationY(baseTy + min * (float) Math.tanh(style.damping * oy / min));
            double a = Math.atan2(oy, ox);
            float ds = style.maxDragDp * density / h;
            view.setScaleX(scale + ds * Math.abs((float) Math.cos(a) * ox / max) * Math.min(w / h, 1f));
            view.setScaleY(scale + ds * Math.abs((float) Math.sin(a) * oy / max) * Math.min(h / w, 1f));
        }
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        float r;
        Drawable bg = view.getBackground();
        if (bg instanceof GlassDrawable) {
            float gr = ((GlassDrawable) bg).radius();
            r = gr < 0 ? Math.min(b.width(), b.height()) / 2f : gr;
        } else {
            r = Math.min(Math.min(b.width(), b.height()) / 2f, 20 * density);
        }
        Squircle.build(shape, new RectF(b), new float[]{r, r, r, r}, Squircle.SMOOTHING);
        if (style.innerShadow) {
            Path shifted = new Path(shape);
            shifted.offset(0, 4 * density);
            innerPath = new Path(shape);
            innerPath.op(shifted, Path.Op.DIFFERENCE);
        }
    }

    @Override
    public void draw(Canvas c) {
        float p = progress;
        if (p <= 0.002f) return;
        Rect b = getBounds();
        if (style.mode == 2) {
            rowPaint.setAlpha(Math.round(Color.alpha(Palette.of(view.getContext()).pressed()) * Math.min(1f, p)));
            c.drawPath(shape, rowPaint);
            return;
        }
        if (style.mode != 0 || (!style.glow && !style.innerShadow)) return;
        c.save();
        c.clipPath(shape);
        if (style.innerShadow && innerPath != null && Build.VERSION.SDK_INT >= 28) {
            inner.setAlpha(Math.round(0.20f * 255 * Math.min(1f, p)));
            c.drawPath(innerPath, inner);
        }
        if (style.glow) {
            float min = Math.min(b.width(), b.height());
            if (Build.VERSION.SDK_INT >= 29) {
                wash.setColor(Palette.alpha(Color.WHITE, 0.08f * Math.min(1f, p)));
                c.drawRect(b, wash);
                float rad = Math.max(1f, min * 1.5f);
                float cx = Math.max(b.left, Math.min(b.right, curX)), cy = Math.max(b.top, Math.min(b.bottom, curY));
                int col = Palette.alpha(Color.WHITE, 0.15f * Math.min(1f, p));
                radial.setShader(new RadialGradient(cx, cy, rad, new int[]{col, col, Color.TRANSPARENT}, new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(b, radial);
            } else {
                wash.setColor(Palette.alpha(Color.WHITE, 0.12f * Math.min(1f, p)));
                c.drawRect(b, wash);
            }
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
