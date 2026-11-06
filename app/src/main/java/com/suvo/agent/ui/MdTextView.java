package com.suvo.agent.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/**
 * Your Friend — Original project by Suvo.
 *
 * The TextView of chat markdown. It draws two things a plain span cannot:
 * <ul>
 * <li>{@link Pill}: inline code / highlighted text on a rounded background that is a little
 * shorter than the line (not a hard box from line top to line bottom), also across wrapped lines.</li>
 * <li>{@link Spoiler}: ||hidden text|| on the same rounded background with the words blurred;
 * a tap reveals them (softly) and they can then be selected like any text.</li>
 * </ul>
 * In a plain TextView the same spans still show as an ordinary background (and a grey block for a
 * spoiler), so text rendered elsewhere stays readable.
 */
public class MdTextView extends TextView {

    /** True while an MdTextView draws its text (the spans then leave the backgrounds to it). */
    static boolean drawing;

    private final RectF rect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint blur;

    public MdTextView(Context context, int styleRes) {
        super(context, null, 0, styleRes);
    }

    /** Inline code / highlight: rounded, a bit shorter than the text line. */
    public static final class Pill extends BackgroundColorSpan {
        public Pill(int color) {
            super(color);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            if (!drawing) super.updateDrawState(tp);
        }
    }

    /** ||Spoiler||: hidden (blurred) until tapped. */
    public static final class Spoiler extends ClickableSpan {
        final int color;
        /** 0 = hidden, 1 = revealed (animated). */
        float shown;
        private boolean revealing;

        public Spoiler(int color) {
            this.color = color;
        }

        boolean hidden() {
            return shown < 1f;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setUnderlineText(false);
            if (shown >= 1f) return;
            if (drawing) {
                // The view draws the blurred words; the real ones fade in as it opens.
                tp.setColor((tp.getColor() & 0x00FFFFFF) | (Math.round(255 * shown * (tp.getColor() >>> 24) / 255f) << 24));
            } else {
                tp.setColor(0x00000000);
                tp.bgColor = color;
            }
        }

        @Override
        public void onClick(View widget) {
            if (revealing || shown >= 1f) return;
            revealing = true;
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(380);
            a.setInterpolator(new DecelerateInterpolator(1.4f));
            a.addUpdateListener(x -> {
                shown = (Float) x.getAnimatedValue();
                widget.invalidate();
            });
            a.start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout l = getLayout();
        CharSequence t = getText();
        Pill[] pills = null;
        Spoiler[] spoilers = null;
        if (l != null && t instanceof Spanned) {
            Spanned sp = (Spanned) t;
            pills = sp.getSpans(0, sp.length(), Pill.class);
            spoilers = sp.getSpans(0, sp.length(), Spoiler.class);
            if (pills.length > 0 || spoilers.length > 0) {
                canvas.save();
                canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
                for (Pill p : pills) {
                    fill.setColor(p.getBackgroundColor());
                    ranges(canvas, l, sp, sp.getSpanStart(p), sp.getSpanEnd(p), null);
                }
                for (Spoiler s : spoilers) {
                    if (s.shown >= 1f) {
                        fill.setColor(s.color);
                        fill.setAlpha(Math.round((s.color >>> 24) * 0.55f));
                    } else {
                        fill.setColor(s.color);
                    }
                    ranges(canvas, l, sp, sp.getSpanStart(s), sp.getSpanEnd(s), null);
                }
                canvas.restore();
            }
        }
        drawing = true;
        try {
            super.onDraw(canvas);
        } finally {
            drawing = false;
        }
        if (spoilers != null && spoilers.length > 0) {
            Spanned sp = (Spanned) t;
            canvas.save();
            canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
            for (Spoiler s : spoilers) {
                if (!s.hidden()) continue;
                ranges(canvas, l, sp, sp.getSpanStart(s), sp.getSpanEnd(s), s);
            }
            canvas.restore();
        }
    }

    /** Draws the rounded background of [start, end) line by line, or (blurOf != null) the blurred words. */
    private void ranges(Canvas c, Layout l, Spanned sp, int start, int end, Spoiler blurOf) {
        if (start < 0 || end <= start) return;
        float d = getResources().getDisplayMetrics().density;
        Paint.FontMetrics fm = getPaint().getFontMetrics();
        int first = l.getLineForOffset(start), last = l.getLineForOffset(end);
        for (int line = first; line <= last && line < l.getLineCount(); line++) {
            int ls = Math.max(start, l.getLineStart(line));
            int le = Math.min(end, l.getLineEnd(line));
            while (le > ls && Character.isWhitespace(sp.charAt(le - 1))) le--;
            if (le <= ls) continue;
            float x1 = l.getPrimaryHorizontal(ls);
            // At the end of a wrapped line the offset belongs to the next line: use the line's right edge.
            float x2 = le >= l.getLineEnd(line) && line < l.getLineCount() - 1 ? l.getLineRight(line) : l.getPrimaryHorizontal(le);
            if (x2 < x1) {
                float tmp = x1;
                x1 = x2;
                x2 = tmp;
            }
            float base = l.getLineBaseline(line);
            if (blurOf == null) {
                // Shorter than the line: from a bit under the ascent to a bit over the descent.
                rect.set(x1 - 3 * d, base + fm.ascent * 0.80f, x2 + 3 * d, base + fm.descent * 0.95f);
                float r = Math.min(6 * d, rect.height() / 2f);
                c.drawRoundRect(rect, r, r, fill);
            } else {
                drawBlurred(c, sp, ls, le, x1, base, blurOf);
            }
        }
    }

    /** The words smeared out: drawn many times around their place, faint (works with hardware drawing). */
    private void drawBlurred(Canvas c, Spanned sp, int s, int e, float x, float base, Spoiler sp0) {
        if (blur == null) blur = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        blur.set(getPaint());
        int col = getCurrentTextColor();
        float d = getResources().getDisplayMetrics().density;
        float k = 1f - sp0.shown;
        String str = sp.subSequence(s, e).toString();
        float r = 2.6f * d;
        int n = 10;
        blur.setColor((col & 0x00FFFFFF) | (Math.round(255 * 0.16f * k) << 24));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            c.drawText(str, x + (float) Math.cos(a) * r, base + (float) Math.sin(a) * r * 0.8f, blur);
        }
        blur.setColor((col & 0x00FFFFFF) | (Math.round(255 * 0.10f * k) << 24));
        c.drawText(str, x, base, blur);
    }
}
