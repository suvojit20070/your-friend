package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * The "liquid glass" surface: a semi-transparent dark tint (rgba(25,30,40,0.65) in the dark theme),
 * a faint top sheen, and a 1px specular border that is brighter at the top edge — like light
 * catching the rim of a pane of glass. Used on its own, or on top of a live blur by
 * {@link BackdropDrawable}. Provides a rounded outline so window background blur and view clipping
 * follow the same corners.
 */
public class GlassDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float radius;
    private final float strokeWidth;
    private final int fillColor;
    private final int strokeTop;
    private final int strokeBottom;
    private final int sheenColor;
    private int alpha = 255;
    /** Corners: top-left, top-right, bottom-right, bottom-left (true = rounded). */
    private boolean[] corners = {true, true, true, true};

    public GlassDrawable(Context ctx, float radiusPx) {
        this(ctx, radiusPx, ctx.getColor(R.color.glass_fill));
    }

    public GlassDrawable(Context ctx, float radiusPx, int fillColor) {
        this.radius = radiusPx;
        this.fillColor = fillColor;
        this.strokeWidth = Math.max(1f, ctx.getResources().getDisplayMetrics().density);
        this.strokeTop = ctx.getColor(R.color.glass_rim_top);
        this.strokeBottom = ctx.getColor(R.color.glass_stroke);
        this.sheenColor = ctx.getColor(R.color.glass_sheen);
        fill.setStyle(Paint.Style.FILL);
        sheen.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidth);
    }

    /** Only round the top corners (bottom sheets). */
    public GlassDrawable topOnly() {
        corners = new boolean[]{true, true, false, false};
        return this;
    }

    public float radius() {
        return radius;
    }

    Path path() {
        return path;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        float half = strokeWidth / 2f;
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        float[] radii = new float[8];
        for (int i = 0; i < 4; i++) {
            float v = corners[i] ? r : 0;
            radii[i * 2] = v;
            radii[i * 2 + 1] = v;
        }
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        int h = Math.max(1, b.height());
        stroke.setShader(new LinearGradient(0, b.top, 0, b.top + h, strokeTop, strokeBottom, Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(h, (int) (radius * 2.2f + 24)),
                sheenColor, sheenColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        fill.setColor(fillColor);
        fill.setAlpha(Math.round(((fillColor >>> 24) & 0xFF) * alpha / 255f));
        c.drawPath(path, fill);
        sheen.setAlpha(alpha);
        c.drawPath(path, sheen);
        stroke.setAlpha(alpha);
        c.drawPath(path, stroke);
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        if (corners[2] || corners[3]) {
            outline.setRoundRect(b, Math.min(radius, Math.min(b.width(), b.height()) / 2f));
        } else {
            // Top-rounded only: extend below the bounds so the bottom corners are square.
            outline.setRoundRect(b.left, b.top, b.right, b.bottom + (int) radius, radius);
        }
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
