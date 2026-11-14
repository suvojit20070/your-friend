package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * A 3×3 dot grid to draw the phone's lock pattern once, so it can be saved (encrypted) for
 * "unlock the phone". Dots are numbered 1..9 row by row; the result is like "1-5-9-6".
 */
public final class PatternPadView extends View {

    public interface Listener {
        void onPattern(String order);
    }

    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> picked = new ArrayList<>();
    private float fingerX = -1, fingerY = -1;
    private Listener listener;

    public PatternPadView(Context c) {
        super(c);
        Palette p = Palette.of(c);
        dot.setColor(Palette.alpha(p.onSurfaceVariant, 0.5f));
        on.setColor(p.primary);
        line.setColor(Palette.alpha(p.primary, 0.7f));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 5));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void clear() {
        picked.clear();
        fingerX = fingerY = -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    private float cx(int i) {
        return getWidth() / 6f * (2 * (i % 3) + 1);
    }

    private float cy(int i) {
        return getHeight() / 6f * (2 * (i / 3) + 1);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!picked.isEmpty()) {
            Path p = new Path();
            p.moveTo(cx(picked.get(0)), cy(picked.get(0)));
            for (int i = 1; i < picked.size(); i++) p.lineTo(cx(picked.get(i)), cy(picked.get(i)));
            if (fingerX >= 0) p.lineTo(fingerX, fingerY);
            c.drawPath(p, line);
        }
        float r = Ui.dp(getContext(), 7);
        for (int i = 0; i < 9; i++) c.drawCircle(cx(i), cy(i), picked.contains(i) ? r * 1.6f : r, picked.contains(i) ? on : dot);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clear();
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                fingerX = e.getX();
                fingerY = e.getY();
                float hit = getWidth() / 7f;
                for (int i = 0; i < 9; i++) {
                    if (picked.contains(i)) continue;
                    if (Math.hypot(fingerX - cx(i), fingerY - cy(i)) < hit) {
                        // A dot passed over between two picked dots is picked too (as Android does).
                        if (!picked.isEmpty()) {
                            int last = picked.get(picked.size() - 1);
                            if ((last % 3 + i % 3) % 2 == 0 && (last / 3 + i / 3) % 2 == 0) {
                                int mid = ((last / 3 + i / 3) / 2) * 3 + (last % 3 + i % 3) / 2;
                                if (!picked.contains(mid)) picked.add(mid);
                            }
                        }
                        picked.add(i);
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                fingerX = fingerY = -1;
                invalidate();
                if (listener != null && picked.size() >= 4) {
                    StringBuilder sb = new StringBuilder();
                    for (int d : picked) sb.append(sb.length() == 0 ? "" : "-").append(d + 1);
                    listener.onPattern(sb.toString());
                } else if (listener != null) {
                    listener.onPattern("");
                }
                return true;
            default:
                return true;
        }
    }
}
