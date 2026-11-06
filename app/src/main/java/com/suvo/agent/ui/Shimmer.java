package com.suvo.agent.ui;

import android.animation.ValueAnimator;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * A soft light that sweeps across a status text again and again ("Thinking…", "Using browser…"),
 * like a live draft status. The text keeps its normal colour; a brighter band moves over it from
 * left to right. Runs only while the text is on screen; nothing moves with reduced motion.
 */
public final class Shimmer {

    private Shimmer() {
    }

    private static final class State implements View.OnAttachStateChangeListener, ValueAnimator.AnimatorUpdateListener {
        final TextView t;
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        final Matrix m = new Matrix();
        int base, light, width = -1;
        LinearGradient shader;

        State(TextView t) {
            this.t = t;
            anim.setDuration(1700);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(this);
        }

        @Override
        public void onAnimationUpdate(ValueAnimator a) {
            int w = t.getWidth();
            if (w <= 0) return;
            float textW = Math.min(w, t.getPaint().measureText(t.getText().toString()) + t.getTotalPaddingLeft());
            int band = Math.max(60, (int) (textW * 0.45f));
            if (shader == null || width != band) {
                width = band;
                shader = new LinearGradient(0, 0, band, 0, new int[]{base, light, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            }
            // From fully left of the text to fully right of it, then a short rest.
            float f = (Float) a.getAnimatedValue();
            float x = -band + (textW + band * 1.6f) * f;
            m.setTranslate(x, 0);
            shader.setLocalMatrix(m);
            t.getPaint().setShader(shader);
            t.invalidate();
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (!anim.isStarted()) anim.start();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            anim.cancel();
        }
    }

    /** Starts (or recolours) the sweep on t: base = its normal colour, light = the moving highlight. */
    public static void start(TextView t, int base, int light) {
        if (t == null) return;
        if (reduced()) {
            stop(t);
            t.setTextColor(base);
            return;
        }
        Object o = t.getTag(R.id.shimmer);
        State s;
        if (o instanceof State) {
            s = (State) o;
            if (s.base != base || s.light != light) s.shader = null;
        } else {
            s = new State(t);
            t.setTag(R.id.shimmer, s);
            t.addOnAttachStateChangeListener(s);
        }
        s.base = base;
        s.light = light;
        t.setTextColor(base);
        if (t.isAttachedToWindow() && !s.anim.isStarted()) s.anim.start();
    }

    private static boolean reduced() {
        try {
            com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
            return g != null && g.settings.bool(com.suvo.agent.data.Settings.REDUCED_MOTION, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Stops the sweep and gives t its plain colour back. */
    public static void stop(TextView t) {
        if (t == null) return;
        Object o = t.getTag(R.id.shimmer);
        if (!(o instanceof State)) return;
        State s = (State) o;
        s.anim.cancel();
        t.removeOnAttachStateChangeListener(s);
        t.setTag(R.id.shimmer, null);
        t.getPaint().setShader(null);
        t.invalidate();
    }
}
