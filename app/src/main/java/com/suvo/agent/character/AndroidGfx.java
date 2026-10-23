package com.suvo.agent.character;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.suvo.agent.core.character.Gfx;

/** {@link Gfx} on android.graphics.Canvas (hardware accelerated). */
final class AndroidGfx implements Gfx {

    private Canvas canvas;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean low;

    AndroidGfx() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void attach(Canvas c) {
        canvas = c;
    }

    private static final class P implements Gfx.Path {
        final android.graphics.Path p = new android.graphics.Path();

        @Override
        public Gfx.Path moveTo(float x, float y) {
            p.moveTo(x, y);
            return this;
        }

        @Override
        public Gfx.Path lineTo(float x, float y) {
            p.lineTo(x, y);
            return this;
        }

        @Override
        public Gfx.Path quadTo(float cx, float cy, float x, float y) {
            p.quadTo(cx, cy, x, y);
            return this;
        }

        @Override
        public Gfx.Path cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
            p.cubicTo(c1x, c1y, c2x, c2y, x, y);
            return this;
        }

        @Override
        public Gfx.Path close() {
            p.close();
            return this;
        }

        @Override
        public Gfx.Path oval(float cx, float cy, float rx, float ry) {
            p.addOval(cx - rx, cy - ry, cx + rx, cy + ry, android.graphics.Path.Direction.CW);
            return this;
        }
    }

    @Override
    public Gfx.Path path() {
        return new P();
    }

    @Override
    public void save() {
        canvas.save();
    }

    @Override
    public void restore() {
        canvas.restore();
    }

    @Override
    public void translate(float dx, float dy) {
        canvas.translate(dx, dy);
    }

    @Override
    public void rotate(float degrees, float px, float py) {
        canvas.rotate(degrees, px, py);
    }

    @Override
    public void scale(float sx, float sy, float px, float py) {
        canvas.scale(sx, sy, px, py);
    }

    @Override
    public void clip(Gfx.Path p) {
        canvas.clipPath(((P) p).p);
    }

    @Override
    public void fill(Gfx.Path p, int argb) {
        fill.setShader(null);
        fill.setColor(argb);
        canvas.drawPath(((P) p).p, fill);
    }

    @Override
    public void fillLinear(Gfx.Path p, float x0, float y0, float x1, float y1, int c0, int c1) {
        if (x0 == x1 && y0 == y1) {
            fill(p, c0);
            return;
        }
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP));
        canvas.drawPath(((P) p).p, fill);
        fill.setShader(null);
    }

    @Override
    public void fillRadial(Gfx.Path p, float cx, float cy, float r, int c0, int c1) {
        fill.setColor(0xFFFFFFFF);
        fill.setShader(new RadialGradient(cx, cy, Math.max(1f, r), c0, c1, Shader.TileMode.CLAMP));
        canvas.drawPath(((P) p).p, fill);
        fill.setShader(null);
    }

    @Override
    public void stroke(Gfx.Path p, int argb, float width) {
        stroke.setColor(argb);
        stroke.setStrokeWidth(width);
        canvas.drawPath(((P) p).p, stroke);
    }

    @Override
    public boolean lowQuality() {
        return low;
    }
}
