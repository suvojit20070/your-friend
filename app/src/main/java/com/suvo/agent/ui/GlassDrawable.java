package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.os.Build;

/**
 * Your Friend — Original project by Suvo.
 *
 * Liquid-glass surface in squircle (G2) shape. On its own it is the tonal fallback (a stronger
 * surface tint, so text stays readable without blur); {@link BackdropDrawable} draws the live
 * blur of what is behind first and then this lighter tint on top.
 *
 * Each {@link Preset} carries the values of the Zafiro glass spec: shape radius, how much the
 * background is blurred (light / dark), the surface tint, an optional primary-container tint
 * blended by hue (gives the glass a faint colour of the theme without darkening it), and a thin
 * specular rim that is brighter along the top edge.
 */
public class GlassDrawable extends Drawable {

    /** Glass looks from the design spec. Radii in dp (CAPSULE = half the short side). */
    public enum Preset {
        BUTTON(-1, 4, 4, 0f),
        POPOVER(20, 24, 20, 0f),
        COMPOSER(36, 16, 12, 0.32f),
        PILL(-1, 16, 12, 0.32f),
        DIALOG(48, 12, 8, 0.18f),
        SHEET(32, 24, 20, 0.18f),
        TOPBAR(0, 16, 16, 0f),
        TILE(20, 6, 6, 0f);

        final float radiusDp;
        final float blurLightDp, blurDarkDp;
        final float hueTint;

        Preset(float radiusDp, float blurLightDp, float blurDarkDp, float hueTint) {
            this.radiusDp = radiusDp;
            this.blurLightDp = blurLightDp;
            this.blurDarkDp = blurDarkDp;
            this.hueTint = hueTint;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;
    private final float radius;
    protected final Preset preset;
    protected final Palette pal;
    private int fillColor;
    private final int tintColor;
    private int alpha = 255;
    private boolean topOnly;
    protected boolean live; // set by BackdropDrawable when a real blur is drawn underneath

    public GlassDrawable(Context ctx, Preset preset) {
        this(ctx, preset, -2);
    }

    /** @param radiusPx -2 = the preset's radius, -1 = capsule */
    public GlassDrawable(Context ctx, Preset preset, float radiusPx) {
        this.preset = preset;
        this.pal = Palette.of(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.radius = radiusPx == -2 ? (preset.radiusDp < 0 ? -1 : preset.radiusDp * density) : radiusPx;
        this.fillColor = surface(false);
        this.tintColor = Palette.alpha(pal.primaryContainer, preset.hueTint);
        fill.setStyle(Paint.Style.FILL);
        tint.setStyle(Paint.Style.FILL);
        if (Build.VERSION.SDK_INT >= 29) tint.setBlendMode(BlendMode.HUE);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.5f * density));
    }

    /** Legacy: tonal glass with a fixed radius (popover look). */
    public GlassDrawable(Context ctx, float radiusPx) {
        this(ctx, Preset.POPOVER, radiusPx);
    }

    /** Legacy: tonal glass with a fixed radius and fill colour. */
    public GlassDrawable(Context ctx, float radiusPx, int fillColor) {
        this(ctx, Preset.POPOVER, radiusPx);
        this.fillColor = fillColor;
    }

    /** Surface tint: light over a live blur, stronger when it has to work alone. */
    protected int surface(boolean overBlur) {
        switch (preset) {
            case BUTTON:
            case TILE:
                return overBlur ? pal.glassButton() : Palette.alpha(pal.surfaceHigh, pal.dark ? 0.78f : 0.86f);
            case POPOVER:
                return overBlur ? Palette.alpha(pal.surfaceHigh, pal.dark ? 0.62f : 0.55f) : Palette.alpha(pal.surfaceHigh, 0.96f);
            case COMPOSER:
            case PILL:
                return overBlur ? Palette.alpha(pal.surfaceHigh, pal.dark ? 0.72f : 0.64f) : Palette.alpha(pal.surfaceHigh, 0.94f);
            case DIALOG:
            case SHEET:
                return overBlur ? Palette.alpha(pal.surfaceHigh, 0.76f) : Palette.alpha(pal.surfaceHigh, 0.97f);
            case TOPBAR:
            default:
                return Palette.alpha(pal.surfaceHigh, pal.dark ? 0.72f : 0.65f);
        }
    }

    float blurPx() {
        return (pal.dark ? preset.blurDarkDp : preset.blurLightDp) * density;
    }

    /** Only round the top corners (bottom sheets). */
    public GlassDrawable topOnly() {
        topOnly = true;
        onBoundsChange(getBounds());
        return this;
    }

    /** Draw the lighter "over blur" tint (a real blur is underneath, e.g. a window background blur). */
    public GlassDrawable setLive(boolean on) {
        live = on;
        invalidateSelf();
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
        rect.set(b);
        float r = radius < 0 ? Math.min(rect.width(), rect.height()) / 2f : radius;
        float[] radii = topOnly ? new float[]{r, r, 0, 0} : new float[]{r, r, r, r};
        Squircle.build(path, rect, radii, Squircle.SMOOTHING);
        int h = Math.max(1, b.height());
        int top = pal.dark ? Palette.alpha(Color.WHITE, 0.22f) : Palette.alpha(Color.WHITE, 0.70f);
        int bottom = pal.dark ? Palette.alpha(Color.WHITE, 0.05f) : Palette.alpha(Color.WHITE, 0.18f);
        rim.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(h, 64 * density), top, bottom, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        int f = live ? surface(true) : fillColor;
        fill.setColor(f);
        fill.setAlpha(Math.round(Color.alpha(f) * alpha / 255f));
        c.drawPath(path, fill);
        if (preset.hueTint > 0 && Build.VERSION.SDK_INT >= 29) {
            tint.setColor(tintColor);
            tint.setAlpha(Math.round(Color.alpha(tintColor) * alpha / 255f));
            c.drawPath(path, tint);
        }
        if (preset != Preset.TOPBAR) {
            rim.setAlpha(alpha);
            c.drawPath(path, rim);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        float r = radius < 0 ? Math.min(b.width(), b.height()) / 2f : Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        if (topOnly) outline.setRoundRect(b.left, b.top, b.right, b.bottom + (int) r, r);
        else outline.setRoundRect(b, r);
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
