package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * Your Friend — Original project by Suvo.
 *
 * The assistant without a card: a soft glow of the theme's colours rising from the bottom edge of
 * the screen. It breathes while waiting, swells with your voice while listening, swirls while the
 * agent thinks or works, ripples while it speaks, and sinks away when it is done.
 */
final class AssistGlow extends View {

    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, DONE = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;
    private int state = IDLE;
    /** 0..1: how high the glow stands (eased toward the state's level every frame). */
    private float level, target = 0.55f;
    /** Voice loudness while listening (0..1, eased). */
    private float voice, voiceTarget;
    private final long start = System.currentTimeMillis();
    private boolean running = true;

    AssistGlow(Context c, Palette pal) {
        super(c);
        // Primary, tertiary, secondary and a lighter primary: four lights that drift past each other.
        colors = new int[]{pal.primary, pal.tertiary, pal.secondaryContainer, lighten(pal.primary)};
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] *= 0.7f;
        hsv[2] = Math.min(1f, hsv[2] * 1.25f + 0.1f);
        return Color.HSVToColor(hsv);
    }

    void setState(int s) {
        state = s;
        target = s == LISTENING ? 0.8f : s == THINKING ? 0.7f : s == SPEAKING ? 0.85f : s == DONE ? 0f : 0.55f;
        invalidate();
    }

    /** The microphone's loudness in dB (SpeechRecognizer's rms value, about -2..10). */
    void setVoice(float rmsDb) {
        voiceTarget = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
    }

    void stop() {
        running = false;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float t = (System.currentTimeMillis() - start) / 1000f;
        level += (target - level) * 0.06f;
        voice += (voiceTarget - voice) * 0.25f;
        voiceTarget *= 0.92f;
        float speed = state == THINKING ? 1.9f : state == SPEAKING ? 1.3f : 0.7f;
        float base = h * (0.18f + 0.5f * level);
        for (int i = 0; i < 4; i++) {
            float ph = t * speed * (0.55f + i * 0.17f) + i * 1.7f;
            float x = w * (0.5f + 0.42f * (float) Math.sin(ph));
            float swell = 1f + 0.18f * (float) Math.sin(t * 2.2f + i) + (state == LISTENING ? voice * 0.9f : 0f)
                    + (state == SPEAKING ? 0.22f * (float) Math.abs(Math.sin(t * 6f + i)) : 0f);
            float r = base * (1.05f + 0.25f * (i % 2)) * swell;
            int col = colors[i];
            float alpha = (state == THINKING ? 0.62f : 0.55f) * Math.min(1f, level * 1.6f + 0.05f);
            int a = Math.round(alpha * 255);
            paint.setShader(new RadialGradient(x, h + r * 0.25f, Math.max(1f, r), (col & 0xFFFFFF) | (a << 24), col & 0xFFFFFF,
                    Shader.TileMode.CLAMP));
            c.drawCircle(x, h + r * 0.25f, r, paint);
        }
        // A thin bright line on the very edge, like light under a door.
        float edge = Math.min(1f, level * 1.4f);
        paint.setShader(new android.graphics.LinearGradient(0, h, w, h, new int[]{colors[0], colors[1], colors[3], colors[0]}, null,
                Shader.TileMode.MIRROR));
        paint.setAlpha(Math.round(200 * edge));
        c.drawRect(0, h - Math.max(2f, h * 0.012f), w, h, paint);
        paint.setAlpha(255);
        paint.setShader(null);
        if (running && (level > 0.01f || target > 0)) postInvalidateOnAnimation();
    }
}
