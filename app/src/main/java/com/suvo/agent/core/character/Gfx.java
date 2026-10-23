package com.suvo.agent.core.character;

/**
 * Minimal 2D drawing surface used by {@link PuppetRenderer}. Implemented with android.graphics.Canvas
 * in the app; a Java2D implementation renders previews in tests and tooling.
 */
public interface Gfx {

    interface Path {
        Path moveTo(float x, float y);

        Path lineTo(float x, float y);

        Path quadTo(float cx, float cy, float x, float y);

        Path cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);

        Path close();

        Path oval(float cx, float cy, float rx, float ry);
    }

    Path path();

    void save();

    void restore();

    void translate(float dx, float dy);

    void rotate(float degrees, float px, float py);

    void scale(float sx, float sy, float px, float py);

    void clip(Path p);

    void fill(Path p, int argb);

    void fillLinear(Path p, float x0, float y0, float x1, float y1, int c0, int c1);

    void fillRadial(Path p, float cx, float cy, float r, int c0, int c1);

    void stroke(Path p, int argb, float width);

    /** Whether cheap mode is on (low animation quality): skip gradients and small details. */
    boolean lowQuality();
}
