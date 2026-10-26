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
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The liquid slider: 6dp capsule track (surfaceContainerHighest, filled part primary) and a
 * 40 x 24 dp pill thumb. At rest the thumb is solid white (#AFAFAF in dark mode). Held, it swells to
 * 1.35x and turns into glass (the track shows through it magnified, a rim light and a shadow) and
 * squashes / stretches with its horizontal speed. Whole-number steps give a haptic tick.
 * API: the subset of Material's Slider the app uses.
 */
public final class LiquidSlider extends View {

    public interface OnChangeListener {
        void onValueChange(LiquidSlider slider, float value, boolean fromUser);
    }

    public interface OnSliderTouchListener {
        void onStartTrackingTouch(LiquidSlider slider);

        void onStopTrackingTouch(LiquidSlider slider);
    }

    private final float d;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final List<OnChangeListener> changes = new ArrayList<>();
    private final List<OnSliderTouchListener> touches = new ArrayList<>();
    private Palette pal;
    private float from = 0, to = 100, step = 1, value = 0;
    private float shown;          // animated position (value spring, damping 1 / stiffness 1000)
    private float press;          // glass progress
    private float speed;          // smoothed horizontal speed for squash/stretch
    private float lastX;
    private long lastT;
    private final Fx.Spring valueSpring, pressSpring, speedSpring;

    public LiquidSlider(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        pal = Palette.of(c);
        setClickable(true);
        shadow.setColor(Palette.alpha(Color.BLACK, pal.dark ? 0.08f : 0.12f));
        if (Build.VERSION.SDK_INT >= 28) shadow.setMaskFilter(new BlurMaskFilter(4 * d, BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.5f * d));
        valueSpring = new Fx.Spring(0f, 1000f, 1f, v -> {
            shown = v;
            invalidate();
        });
        pressSpring = new Fx.Spring(0f, 300f, 0.5f, v -> {
            press = Math.max(0f, v);
            invalidate();
        });
        speedSpring = new Fx.Spring(0f, 300f, 0.5f, v -> {
            speed = v;
            invalidate();
        });
    }

    public void setValueFrom(float f) {
        from = f;
        invalidate();
    }

    public void setValueTo(float t) {
        to = t;
        invalidate();
    }

    public void setStepSize(float s) {
        step = s;
    }

    public float getValue() {
        return value;
    }

    public void setValue(float v) {
        set(v, false);
        valueSpring.snapTo(frac(value));
    }

    public void addOnChangeListener(OnChangeListener l) {
        changes.add(l);
    }

    public void addOnSliderTouchListener(OnSliderTouchListener l) {
        touches.add(l);
    }

    private float frac(float v) {
        return to == from ? 0 : (v - from) / (to - from);
    }

    private void set(float v, boolean fromUser) {
        float c = Math.max(from, Math.min(to, v));
        if (step > 0) c = from + Math.round((c - from) / step) * step;
        if (c == value) return;
        value = c;
        if (fromUser && step > 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        for (OnChangeListener l : changes) l.onValueChange(this, value, fromUser);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(44 * d));
    }

    private float left() {
        return 26 * d;
    }

    private float right() {
        return getWidth() - 26 * d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                pressSpring.animateTo(1f);
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                for (OnSliderTouchListener l : touches) l.onStartTrackingTouch(this);
                lastX = x;
                lastT = e.getEventTime();
                track(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                long t = e.getEventTime();
                if (t > lastT) {
                    float v = (x - lastX) / (t - lastT) / d; // dp per ms
                    speedSpring.animateTo(Math.max(-1f, Math.min(1f, v)));
                }
                lastX = x;
                lastT = t;
                track(x);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressSpring.animateTo(0f);
                speedSpring.animateTo(0f);
                for (OnSliderTouchListener l : touches) l.onStopTrackingTouch(this);
                return true;
            default:
                return false;
        }
    }

    private void track(float x) {
        float f = (x - left()) / Math.max(1f, right() - left());
        set(from + Math.max(0f, Math.min(1f, f)) * (to - from), true);
        valueSpring.animateTo(frac(value));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f;
        float l = left(), rr = right();
        float th = 6 * d;
        track.setColor(isEnabled() ? pal.surfaceHighest : Palette.alpha(pal.onSurface, 0.12f));
        fillP.setColor(isEnabled() ? pal.primary : Palette.alpha(pal.onSurface, 0.38f));
        r.set(l - th / 2, cy - th / 2, rr + th / 2, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, track);
        float x = l + (rr - l) * Math.max(0f, Math.min(1f, shown));
        r.set(l - th / 2, cy - th / 2, x, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, fillP);
        // Thumb with velocity squash / stretch.
        float p = Math.min(1f, press);
        float sc = 1f + 0.35f * p;
        float sx = sc / (1f - Math.max(-0.2f, Math.min(0.2f, speed * 0.75f)));
        float sy = sc * (1f - Math.max(-0.2f, Math.min(0.2f, Math.abs(speed) * 0.25f)));
        float w2 = 20 * d * sx, h2 = 12 * d * sy;
        r.set(x - w2, cy - h2, x + w2, cy + h2);
        float rad = h2;
        c.drawRoundRect(r.left, r.top + 2 * d, r.right, r.bottom + 2 * d, rad, rad, shadow);
        if (p > 0.01f) {
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.scale(1.25f, 1.25f, x, cy);
            r.set(l - th / 2, cy - th / 2, rr + th / 2, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, track);
            r.set(l - th / 2, cy - th / 2, x, cy + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, fillP);
            c.restore();
            r.set(x - w2, cy - h2, x + w2, cy + h2);
        }
        int base = pal.dark ? 0xFFAFAFAF : Color.WHITE;
        thumb.setColor(Palette.alpha(base, 1f - 0.85f * p));
        c.drawRoundRect(r, rad, rad, thumb);
        if (p > 0.01f) {
            rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, Palette.alpha(Color.WHITE, 0.8f * p),
                    Palette.alpha(Color.WHITE, 0.15f * p), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, rim);
        }
    }
}
