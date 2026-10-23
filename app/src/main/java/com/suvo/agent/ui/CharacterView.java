package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A built-in animated 2D character, drawn with Canvas (no external model needed).
 * Two original styles: GIRL and BOY (anime-ish, generic — not any existing character).
 *
 * Features: idle breathing, blinking, and mouth-open driven by {@link #setAmplitude}
 * (hook this to the voice amplitude for lip-sync). Later, imported Live2D/VRM models
 * can replace this view; it's the fallback "body" that always works.
 */
public class CharacterView extends View {

    public enum Style { GIRL, BOY }

    private Style style = Style.GIRL;
    private int accent = Color.parseColor("#B692FF");
    private int accent2 = Color.parseColor("#58E6D9");

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mouth = 0f;        // 0..1 target mouth open
    private float mouthCur = 0f;     // smoothed
    private float breath = 0f;       // 0..1 breathing phase
    private float blink = 1f;        // 1 = open, 0 = closed
    private long lastBlink = 0;
    private boolean blinking = false;
    private float blinkT = 0;

    public CharacterView(Context c) { super(c); init(); }
    public CharacterView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);
        postOnAnimation(ticker);
    }

    public void setStyle(Style s) { style = s; invalidate(); }
    public void setColors(int a, int b) { accent = a; accent2 = b; invalidate(); }

    /** 0..1 loudness for lip-sync. */
    public void setAmplitude(float amp) { mouth = Math.max(0f, Math.min(1f, amp)); }

    /** Briefly animate talking (used when no audio amplitude is available yet). */
    public void pulseTalk() { mouth = 0.9f; }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            breath = (float) ((Math.sin(now / 1400.0) + 1) / 2);

            // mouth smoothing (and natural decay when no new amplitude set)
            float a = mouth > mouthCur ? 0.5f : 0.25f;
            mouthCur += (mouth - mouthCur) * a;
            mouth *= 0.85f;  // decay target so it closes between sounds

            // blink every ~3-5s
            if (!blinking && now - lastBlink > 3000 + (long) (Math.random() * 2500)) {
                blinking = true; blinkT = 0;
            }
            if (blinking) {
                blinkT += 0.18f;
                blink = (float) Math.abs(Math.cos(blinkT * Math.PI));
                if (blinkT >= 1f) { blinking = false; blink = 1f; lastBlink = now; }
            }

            invalidate();
            postOnAnimationDelayed(this, 16);
        }
    };

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float bob = (breath - 0.5f) * h * 0.012f;   // subtle breathing
        c.save();
        c.translate(0, bob);

        boolean girl = style == Style.GIRL;
        int hairColor = girl ? Color.parseColor("#E9A7E6") : Color.parseColor("#6E79C7");
        int hairDark  = girl ? Color.parseColor("#C877C9") : Color.parseColor("#454E8E");
        int skin      = Color.parseColor("#FDE8D6");

        float headR = w * 0.22f;
        float headCy = h * 0.34f;

        // ---- hair back ----
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, headCy - headR, 0, h, hairColor, hairDark, Shader.TileMode.CLAMP));
        Path hairBack = new Path();
        if (girl) {
            hairBack.moveTo(cx - headR * 1.25f, headCy);
            hairBack.cubicTo(cx - headR * 1.5f, h * 0.75f, cx - headR * 0.9f, h * 0.9f, cx - headR * 0.5f, h * 0.9f);
            hairBack.lineTo(cx + headR * 0.5f, h * 0.9f);
            hairBack.cubicTo(cx + headR * 0.9f, h * 0.9f, cx + headR * 1.5f, h * 0.75f, cx + headR * 1.25f, headCy);
            hairBack.cubicTo(cx + headR * 1.3f, headCy - headR, cx - headR * 1.3f, headCy - headR, cx - headR * 1.25f, headCy);
        } else {
            hairBack.addCircle(cx, headCy, headR * 1.12f, Path.Direction.CW);
        }
        c.drawPath(hairBack, p);

        // ---- body / dress ----
        p.setShader(new LinearGradient(0, h * 0.55f, 0, h, accent, accent2, Shader.TileMode.CLAMP));
        Path body = new Path();
        float neckY = headCy + headR * 0.85f;
        if (girl) {
            body.moveTo(cx - headR * 0.45f, neckY);
            body.lineTo(cx + headR * 0.45f, neckY);
            body.lineTo(cx + headR * 1.25f, h);
            body.lineTo(cx - headR * 1.25f, h);
            body.close();
        } else {
            body.moveTo(cx - headR * 0.6f, neckY);
            body.lineTo(cx + headR * 0.6f, neckY);
            body.lineTo(cx + headR * 0.85f, h);
            body.lineTo(cx - headR * 0.85f, h);
            body.close();
        }
        c.drawPath(body, p);
        p.setShader(null);

        // ---- neck ----
        p.setColor(skin);
        c.drawRect(cx - headR * 0.18f, headCy + headR * 0.6f, cx + headR * 0.18f, neckY + 4, p);

        // ---- face ----
        p.setColor(skin);
        RectF face = new RectF(cx - headR, headCy - headR * 1.05f, cx + headR, headCy + headR * 1.05f);
        c.drawOval(face, p);

        // ---- hair front / bangs ----
        p.setColor(hairColor);
        Path bangs = new Path();
        bangs.moveTo(cx - headR, headCy - headR * 0.1f);
        bangs.cubicTo(cx - headR, headCy - headR * 1.3f, cx + headR, headCy - headR * 1.3f, cx + headR, headCy - headR * 0.1f);
        bangs.cubicTo(cx + headR * 0.5f, headCy - headR * 0.55f, cx + headR * 0.2f, headCy - headR * 0.5f, cx + headR * 0.15f, headCy - headR * 0.2f);
        bangs.cubicTo(cx, headCy - headR * 0.5f, cx, headCy - headR * 0.5f, cx - headR * 0.15f, headCy - headR * 0.2f);
        bangs.cubicTo(cx - headR * 0.2f, headCy - headR * 0.5f, cx - headR * 0.5f, headCy - headR * 0.55f, cx - headR, headCy - headR * 0.1f);
        bangs.close();
        c.drawPath(bangs, p);

        if (girl) {
            // ahoge (hair antenna)
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(headR * 0.08f);
            p.setStrokeCap(Paint.Cap.ROUND);
            Path ah = new Path();
            ah.moveTo(cx, headCy - headR * 1.25f);
            ah.cubicTo(cx + headR * 0.2f, headCy - headR * 1.6f, cx - headR * 0.25f, headCy - headR * 1.65f, cx - headR * 0.05f, headCy - headR * 1.4f);
            c.drawPath(ah, p);
            p.setStyle(Paint.Style.FILL);
        }

        // ---- eyes ----
        float eyeY = headCy + headR * 0.1f;
        float eyeDx = headR * 0.42f;
        float eyeW = headR * 0.2f, eyeH = headR * 0.28f * blink;
        p.setColor(Color.parseColor("#3A2E6B"));
        c.drawOval(new RectF(cx - eyeDx - eyeW, eyeY - eyeH, cx - eyeDx + eyeW, eyeY + eyeH), p);
        c.drawOval(new RectF(cx + eyeDx - eyeW, eyeY - eyeH, cx + eyeDx + eyeW, eyeY + eyeH), p);
        if (blink > 0.5f) {
            p.setColor(Color.WHITE);
            float g = headR * 0.06f;
            c.drawCircle(cx - eyeDx + g, eyeY - g, g, p);
            c.drawCircle(cx + eyeDx + g, eyeY - g, g, p);
        }

        // ---- blush (girl) ----
        if (girl) {
            p.setColor(Color.parseColor("#55FBB6C6"));
            c.drawOval(new RectF(cx - headR * 0.75f, eyeY + headR * 0.25f, cx - headR * 0.4f, eyeY + headR * 0.45f), p);
            c.drawOval(new RectF(cx + headR * 0.4f, eyeY + headR * 0.25f, cx + headR * 0.75f, eyeY + headR * 0.45f), p);
        }

        // ---- mouth (lip-sync) ----
        p.setColor(Color.parseColor("#C0566E"));
        float mY = headCy + headR * 0.55f;
        float open = mouthCur * headR * 0.3f;
        if (open < headR * 0.03f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(headR * 0.05f);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx - headR * 0.12f, mY, cx + headR * 0.12f, mY, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            c.drawOval(new RectF(cx - headR * 0.14f, mY - open * 0.4f, cx + headR * 0.14f, mY + open), p);
        }

        c.restore();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ticker);
    }
}
