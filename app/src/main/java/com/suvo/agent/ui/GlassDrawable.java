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
import android.view.View;

/**
 * Your Friend — Original project by Suvo.
 *
 * Liquid-glass surface in squircle (G2) shape, drawn in the order of the glass recipe:
 * (blurred, 1.5x saturated backdrop — by {@link BackdropDrawable}) → primary-container tint with
 * the HUE blend mode → the preset's surface fill → a thin ambient rim light, brighter at the top.
 * Without a live blur it is the fallback: the same surface 20% more opaque plus a 1px top inner
 * highlight, so text stays readable and the shape still reads as glass.
 *
 * {@link Preset} holds the spec's values per component; {@link #shadow} gives a host view the
 * preset's soft outer shadow.
 */
public class GlassDrawable extends Drawable {

    /** Glass presets. Radii in dp (-1 = capsule / circle). */
    public enum Preset {
        //        radius blurL blurD  hue   rimL  rimD  shadowR shadowL shadowD
        // Rims are kept faint (Zafiro look): a bright white ring around every round button looked harsh.
        BUTTON(-1, 8, 8, 0f, 0f, 0.12f, 12, 0.16f, 0.08f),
        POPOVER(20, 24, 20, 0f, 0.15f, 0.25f, 20, 0.20f, 0.08f),
        COMPOSER(36, 16, 12, 0.32f, 0.1f, 0.15f, 20, 0.12f, 0.06f),
        SCROLL(-1, 16, 12, 0f, 0.1f, 0.15f, 20, 0.12f, 0.06f),
        PILL(-1, 16, 12, 0.32f, 0.1f, 0.15f, 20, 0.12f, 0.06f),
        TEXTFIELD(36, 2, 2, 0.32f, 0.5f, 0.5f, 0, 0f, 0f),
        CAPSULE(-1, 2, 2, 0f, 0.15f, 0.25f, 0, 0f, 0f),
        DIALOG(48, 12, 8, 0.18f, 0f, 0f, 16, 0.24f, 0.16f),
        SHEET(32, 12, 8, 0.18f, 0f, 0f, 24, 0.20f, 0.14f),
        TOPBAR(0, 16, 16, 0f, 0f, 0f, 0, 0f, 0f),
        TILE(20, 6, 6, 0f, 0.12f, 0.2f, 0, 0f, 0f);

        final float radiusDp, blurLightDp, blurDarkDp, hueTint, rimLight, rimDark, shadowDp, shadowLight, shadowDark;

        Preset(float radiusDp, float blurLightDp, float blurDarkDp, float hueTint, float rimLight, float rimDark,
               float shadowDp, float shadowLight, float shadowDark) {
            this.radiusDp = radiusDp;
            this.blurLightDp = blurLightDp;
            this.blurDarkDp = blurDarkDp;
            this.hueTint = hueTint;
            this.rimLight = rimLight;
            this.rimDark = rimDark;
            this.shadowDp = shadowDp;
            this.shadowLight = shadowLight;
            this.shadowDark = shadowDark;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;
    private float radius;
    protected final Preset preset;
    protected final Palette pal;
    private int fixedFill;
    private final int tintColor;
    private int alpha = 255;
    private boolean topOnly;
    private boolean disabled;
    protected boolean live; // a real blur is drawn underneath (by BackdropDrawable or the window)

    public GlassDrawable(Context ctx, Preset preset) {
        this(ctx, preset, -2);
    }

    /** @param radiusPx -2 = the preset's radius, -1 = capsule */
    public GlassDrawable(Context ctx, Preset preset, float radiusPx) {
        this.preset = preset;
        this.pal = Palette.of(ctx);
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.radius = radiusPx == -2 ? (preset.radiusDp < 0 ? -1 : preset.radiusDp * density) : radiusPx;
        this.tintColor = Palette.alpha(pal.primaryContainer, preset.hueTint);
        fill.setStyle(Paint.Style.FILL);
        tint.setStyle(Paint.Style.FILL);
        if (Build.VERSION.SDK_INT >= 29) tint.setBlendMode(BlendMode.HUE);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.5f * density));
        topLine.setStyle(Paint.Style.STROKE);
        topLine.setStrokeWidth(Math.max(1f, density));
    }

    /** Legacy: popover glass with a fixed radius. */
    public GlassDrawable(Context ctx, float radiusPx) {
        this(ctx, Preset.POPOVER, radiusPx);
    }

    /** Legacy: popover glass with a fixed radius and fill colour. */
    public GlassDrawable(Context ctx, float radiusPx, int fillColor) {
        this(ctx, Preset.POPOVER, radiusPx);
        this.fixedFill = fillColor;
    }

    /** The preset's surface over a live blur. */
    protected int surface() {
        switch (preset) {
            case BUTTON:
            case POPOVER:
            case TILE:
                return pal.glassButton();
            case COMPOSER:
            case SCROLL:
            case PILL:
                return Palette.alpha(pal.surfaceHigh, pal.dark ? 0.72f : 0.64f);
            case TEXTFIELD:
                return Palette.alpha(pal.surfaceHigh, 0.64f);
            case CAPSULE:
                return Palette.alpha(pal.primary, 0.18f);
            case DIALOG:
            case SHEET:
                return Palette.alpha(pal.surfaceHigh, 0.76f);
            case TOPBAR:
            default:
                return Palette.alpha(pal.surfaceHigh, pal.dark ? 0.72f : 0.65f);
        }
    }

    /** Without blur: surfaceContainerHigh at the preset alpha + 20%. */
    protected int fallbackSurface() {
        float a;
        switch (preset) {
            case BUTTON:
            case POPOVER:
            case TILE:
                a = pal.dark ? 0.72f : 0.78f;
                break;
            case CAPSULE:
                return Palette.alpha(pal.primary, 0.26f);
            case DIALOG:
            case SHEET:
                a = 0.96f;
                break;
            case TEXTFIELD:
                a = 0.84f;
                break;
            default:
                a = pal.dark ? 0.92f : 0.84f;
        }
        return Palette.alpha(pal.surfaceHigh, a);
    }

    float blurPx() {
        return (pal.dark ? preset.blurDarkDp : preset.blurLightDp) * density;
    }

    /** Disabled glass: surfaceContainer 42%, no hue tint. */
    public GlassDrawable setDisabled(boolean d) {
        disabled = d;
        invalidateSelf();
        return this;
    }

    /** Draw the lighter "over blur" surface (a real blur is underneath, e.g. a window blur). */
    public GlassDrawable setLive(boolean on) {
        live = on;
        invalidateSelf();
        return this;
    }

    /** Only round the top corners (bottom sheets). */
    public GlassDrawable topOnly() {
        topOnly = true;
        onBoundsChange(getBounds());
        return this;
    }

    public float radius() {
        return radius;
    }

    /** Changes the corner radius (popover morph). */
    public void setRadius(float px) {
        radius = px;
        onBoundsChange(getBounds());
        invalidateSelf();
    }

    Path path() {
        return path;
    }

    /** Soft outer shadow of the preset on {@code host} (spot + ambient colour carry the alpha). */
    public static void shadow(View host, Preset p) {
        if (p.shadowDp <= 0) return;
        Palette pal = Palette.of(host.getContext());
        float a = pal.dark ? p.shadowDark : p.shadowLight;
        float d = host.getResources().getDisplayMetrics().density;
        host.setElevation(p.shadowDp * d * 0.5f);
        if (Build.VERSION.SDK_INT >= 28) {
            host.setOutlineSpotShadowColor(Palette.alpha(Color.BLACK, Math.min(1f, a * 1.6f)));
            host.setOutlineAmbientShadowColor(Palette.alpha(Color.BLACK, a));
        }
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        rect.set(b);
        float r = radius < 0 ? Math.min(rect.width(), rect.height()) / 2f : Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        float[] radii = topOnly ? new float[]{r, r, 0, 0} : new float[]{r, r, r, r};
        Squircle.build(path, rect, radii, Squircle.SMOOTHING);
        int h = Math.max(1, b.height());
        float rimA = pal.dark ? preset.rimDark : preset.rimLight;
        // A hairline that catches a little light at the top and fades out: present, never a ring.
        int top = Palette.alpha(Color.WHITE, Math.min(0.32f, 0.4f * rimA + (pal.dark ? 0.06f : 0.14f)));
        int bottom = Palette.alpha(Color.WHITE, 0.05f * rimA);
        rim.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(h, 64 * density), top, bottom, Shader.TileMode.CLAMP));
        int hl = Palette.alpha(Color.WHITE, pal.dark ? 0.08f : 0.15f);
        topLine.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[]{0, hl, hl, 0}, new float[]{0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        int f = fixedFill != 0 ? fixedFill : disabled ? Palette.alpha(pal.surfaceHigh, 0.42f) : live ? surface() : fallbackSurface();
        if (preset.hueTint > 0 && !disabled && Build.VERSION.SDK_INT >= 29) {
            tint.setColor(tintColor);
            tint.setAlpha(Math.round(Color.alpha(tintColor) * alpha / 255f));
            c.drawPath(path, tint);
        }
        fill.setColor(f);
        fill.setAlpha(Math.round(Color.alpha(f) * alpha / 255f));
        c.drawPath(path, fill);
        if (preset != Preset.TOPBAR) {
            rim.setAlpha(alpha);
            c.drawPath(path, rim);
            if (!live) {
                // Fallback: a 1px inner highlight along the top edge.
                Rect b = getBounds();
                float r = radius < 0 ? b.height() / 2f : Math.min(radius, b.height() / 2f);
                float y = b.top + density;
                topLine.setAlpha(alpha);
                c.drawLine(b.left + r * 0.6f, y, b.right - r * 0.6f, y, topLine);
            }
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        float r = radius < 0 ? Math.min(b.width(), b.height()) / 2f : Math.min(radius, Math.min(b.width(), b.height()) / 2f);
        if (topOnly) outline.setRoundRect(b.left, b.top, b.right, b.bottom + (int) r, r);
        else outline.setRoundRect(b, r);
        // Translucent glass: a lighter outline keeps the shadow from darkening the glass itself.
        outline.setAlpha(0.6f);
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
