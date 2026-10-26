package com.suvo.agent.ui;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * Your Friend — Original project by Suvo.
 *
 * Continuous-curvature ("G2", squircle) rounded rectangles. A plain rounded corner jumps from a
 * straight edge straight into a circle, which the eye sees as a small kink; here the edge eases
 * into the curve with two cubic segments around a shorter circular arc (corner smoothing 0.6, the
 * same construction design tools use for iOS-style corners). When the radius is half the short
 * side the shape becomes a capsule.
 */
public final class Squircle {

    public static final float SMOOTHING = 0.6f;

    private Squircle() {
    }

    /**
     * @param radii per corner: top-left, top-right, bottom-right, bottom-left (px; 0 = square)
     */
    public static Path path(RectF r, float[] radii, float smoothing) {
        Path p = new Path();
        build(p, r, radii, smoothing);
        return p;
    }

    public static void build(Path p, RectF r, float[] radii, float smoothing) {
        p.reset();
        float w = r.width(), h = r.height();
        if (w <= 0 || h <= 0) return;
        float budget = Math.min(w, h) / 2f;
        // Corner order around the shape (clockwise): TR, BR, BL, TL. in = direction along the edge
        // arriving at the corner, out = direction of the edge leaving it.
        float[][] corners = {
                {r.right, r.top, 1, 0, 0, 1, radii[1]},
                {r.right, r.bottom, 0, 1, -1, 0, radii[2]},
                {r.left, r.bottom, -1, 0, 0, -1, radii[3]},
                {r.left, r.top, 0, -1, 1, 0, radii[0]},
        };
        boolean first = true;
        for (float[] c : corners) {
            float cx = c[0], cy = c[1], ix = c[2], iy = c[3], ox = c[4], oy = c[5];
            float rad = Math.max(0f, c[6]);
            if (rad < 0.5f) {
                if (first) p.moveTo(cx, cy);
                else p.lineTo(cx, cy);
                first = false;
                continue;
            }
            rad = Math.min(rad, budget);
            float s = Math.max(0f, Math.min(smoothing, budget / rad - 1f));
            float len = Math.min(budget, (1f + s) * rad);
            double arcMeasure = 90.0 * (1 - s);
            float arcSection = (float) (Math.sin(Math.toRadians(arcMeasure / 2)) * rad * Math.sqrt(2));
            double angleAlpha = (90 - arcMeasure) / 2;
            float p3p4 = (float) (rad * Math.tan(Math.toRadians(angleAlpha / 2)));
            double angleBeta = 45 * s;
            float cc = (float) (p3p4 * Math.cos(Math.toRadians(angleBeta)));
            float d = (float) (cc * Math.tan(Math.toRadians(angleBeta)));
            float b = (len - arcSection - cc - d) / 3f;
            float a = 2 * b;
            // Point = corner - x * in + y * out
            float sx = cx - len * ix, sy = cy - len * iy;
            if (first) p.moveTo(sx, sy);
            else p.lineTo(sx, sy);
            first = false;
            float x1 = len - a, x2 = len - a - b, x3 = len - a - b - cc;
            p.cubicTo(cx - x1 * ix, cy - x1 * iy, cx - x2 * ix, cy - x2 * iy, cx - x3 * ix + d * ox, cy - x3 * iy + d * oy);
            // Circular arc around the corner's centre.
            float ccx = cx - rad * ix + rad * ox, ccy = cy - rad * iy + rad * oy;
            float ax = cx - x3 * ix + d * ox, ay = cy - x3 * iy + d * oy;
            float start = (float) Math.toDegrees(Math.atan2(ay - ccy, ax - ccx));
            if (arcMeasure > 0.01) {
                p.arcTo(new RectF(ccx - rad, ccy - rad, ccx + rad, ccy + rad), start, (float) arcMeasure, false);
            }
            // Mirror of the first cubic.
            p.cubicTo(cx + x2 * ox, cy + x2 * oy, cx + x1 * ox, cy + x1 * oy, cx + len * ox, cy + len * oy);
        }
        p.close();
    }
}
