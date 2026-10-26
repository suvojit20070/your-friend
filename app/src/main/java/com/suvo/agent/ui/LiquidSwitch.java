package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.CompoundButton;

/**
 * Your Friend — Original project by Suvo.
 *
 * The liquid toggle: a 64 x 28 dp capsule track (surface ↔ primary) with a wide 39 x 24 dp pill
 * thumb. At rest the thumb is solid white with a tiny shadow. While it is held or dragged it swells
 * to 1.5x and turns into glass: the white drains away, the track shows through it magnified (drawn
 * at 1/0.7 scale inside the thumb, like a lens), a rim highlight and an inner shadow fade in.
 * Dragging follows the finger with overshoot (-0.3 … 1.3) and a haptic tick when crossing the middle;
 * on release it springs to the nearest side (damping 0.5, stiffness 300). A tap toggles.
 */
public final class LiquidSwitch extends CompoundButton {

    private final float d;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private Palette pal;
    private float pos;      // 0 = off, 1 = on (may overshoot while dragging)
    private float press;    // 0..1 glass progress
    private Fx.Spring posSpring, pressSpring;
    private boolean dragging, crossed;
    private float downX, downPos;
    private final int slop;

    public LiquidSwitch(Context c) {
        super(c, null, 0);
        d = c.getResources().getDisplayMetrics().density;
        pal = Palette.of(c);
        setButtonDrawable(null);
        setBackground(null);
        setClickable(true);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        shadow.setColor(Palette.alpha(Color.BLACK, 0.12f));
        if (Build.VERSION.SDK_INT >= 28) shadow.setMaskFilter(new BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.5f * d));
        inner.setColor(Palette.alpha(Color.BLACK, 0.18f));
        if (Build.VERSION.SDK_INT >= 28) inner.setMaskFilter(new BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL));
        posSpring = new Fx.Spring(0f, 300f, 0.5f, v -> {
            pos = v;
            invalidate();
        });
        // No overshoot below 0 on release: a bouncing glass/white thumb looked like a flicker.
        pressSpring = new Fx.Spring(0f, 300f, 0.9f, v -> {
            press = Math.max(0f, v);
            invalidate();
        });
        setContentDescription(null);
    }

    @Override
    protected void onMeasure(int w, int h) {
        // Room around the track for the 1.5x thumb swell (so it is never cut at the sides).
        setMeasuredDimension(Math.round(96 * d), Math.round(44 * d));
    }

    @Override
    public void setChecked(boolean checked) {
        boolean was = isChecked();
        super.setChecked(checked);
        if (posSpring == null) return;
        if (!isAttachedToWindow() || getWidth() == 0) {
            posSpring.snapTo(checked ? 1f : 0f);
        } else if (was != checked || !dragging) {
            posSpring.animateTo(checked ? 1f : 0f);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pal = Palette.of(getContext());
        if (posSpring != null) posSpring.snapTo(isChecked() ? 1f : 0f);
    }

    private float trackW() {
        return 64 * d;
    }

    private float travel() {
        return trackW() - 4 * d - 39 * d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downPos = pos;
                dragging = false;
                crossed = pos >= 0.5f;
                pressSpring.animateTo(1f);
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > slop) {
                    dragging = true;
                    ViewParent p = getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    float np = Math.max(-0.3f, Math.min(1.3f, downPos + dx / travel()));
                    posSpring.snapTo(np);
                    boolean now = np >= 0.5f;
                    if (now != crossed) {
                        crossed = now;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                pressSpring.animateTo(0f);
                if (dragging) {
                    dragging = false;
                    boolean on = pos >= 0.5f;
                    if (on != isChecked()) setChecked(on);
                    else posSpring.animateTo(on ? 1f : 0f);
                } else {
                    performClick(); // toggles
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pressSpring.animateTo(0f);
                posSpring.animateTo(isChecked() ? 1f : 0f);
                return true;
            default:
                return false;
        }
    }

    private int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void drawTrack(Canvas c, float left, float top) {
        int off = pal.surfaceHighest;
        int col = isEnabled() ? lerpColor(off, pal.primary, pos) : Palette.alpha(pal.onSurface, 0.12f);
        track.setColor(col);
        r.set(left, top, left + trackW(), top + 28 * d);
        c.drawRoundRect(r, 14 * d, 14 * d, track);
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = (getWidth() - trackW()) / 2f;
        float top = (getHeight() - 28 * d) / 2f;
        drawTrack(c, left, top);
        // Thumb
        float p = Math.max(0f, Math.min(1.3f, press));
        float tw = 39 * d, th = 24 * d;
        float cx = left + 2 * d + tw / 2f + travel() * pos;
        float cy = top + 14 * d;
        float s = 1f + 0.5f * Math.min(1f, p);
        float w2 = tw * s / 2f, h2 = th * s / 2f;
        r.set(cx - w2, cy - h2, cx + w2, cy + h2);
        float rr = h2;
        // shadow (4dp, 5%)
        shadow.setAlpha(Math.round(255 * 0.05f / 0.12f * 0.12f * (1f - 0.5f * p)));
        c.drawRoundRect(r.left, r.top + 1.5f * d, r.right, r.bottom + 1.5f * d, rr, rr, shadow);
        // glass: the track magnified inside the thumb
        if (p > 0.01f) {
            c.save();
            path.reset();
            path.addRoundRect(r, rr, rr, Path.Direction.CW);
            c.clipPath(path);
            c.save();
            c.scale(1f / 0.7f, 1f / 0.7f, cx, cy);
            drawTrack(c, left, top);
            c.restore();
            if (Build.VERSION.SDK_INT >= 28) {
                inner.setAlpha(Math.round(0.18f * 255 * Math.min(1f, p)));
                Path shifted = new Path();
                shifted.addRoundRect(r.left, r.top + 4 * d, r.right, r.bottom + 4 * d, rr, rr, Path.Direction.CW);
                Path ring = new Path(path);
                ring.op(shifted, Path.Op.DIFFERENCE);
                c.drawPath(ring, inner);
            }
            c.restore();
        }
        thumb.setColor(Palette.alpha(Color.WHITE, Math.max(0f, 1f - Math.min(1f, p))));
        c.drawRoundRect(r, rr, rr, thumb);
        if (p > 0.01f) {
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, Palette.alpha(Color.WHITE, 0.8f * Math.min(1f, p)),
                    Palette.alpha(Color.WHITE, 0.15f * Math.min(1f, p)), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rr, rr, rim);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Switch.class.getName();
    }
}
