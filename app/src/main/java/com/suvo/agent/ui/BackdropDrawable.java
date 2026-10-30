package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Frosted glass with a real-time Gaussian blur of whatever is behind the view (Android 12+, hardware
 * rendering). Before each frame that draws anyway it records the screen content under the host into a small
 * RenderNode, blurs it on the GPU with a RenderEffect and draws it clipped to the glass shape, then
 * the glass tint and specular rim on top. Content is recorded at a reduced scale, so the blur is
 * cheap. On older Android it is plain {@link GlassDrawable} glass (slightly more opaque).
 *
 * Safety: the recorded RenderNode references the source views' display lists live (not a copy).
 * If the source contained the glass view itself, the glass would draw a node that draws the glass
 * again — an endless loop on the render thread (a native crash). So the source must never be the
 * glass view or one of its ancestors; in that case the glass is drawn without the live blur.
 */
public final class BackdropDrawable extends GlassDrawable implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {

    private boolean capturing;
    private Object effect; // RenderEffect, created once
    private static final float SCALE = 0.25f;

    private final View host;
    private final View source;
    private final float blurPx;
    private final int bgColor;
    private Object node; // RenderNode (API 29+), kept as Object so older devices never load the class
    private boolean ready;
    private final int[] hostLoc = new int[2];
    private final int[] srcLoc = new int[2];

    /** @param source the view whose content shows through (usually the activity's content root). */
    public static void apply(View host, View source, float radiusPx) {
        apply(host, source, GlassDrawable.Preset.COMPOSER, radiusPx);
    }

    public static GlassDrawable apply(View host, View source, GlassDrawable.Preset preset) {
        return apply(host, source, preset, -2);
    }

    /**
     * Live-blur glass on API 31+, tonal glass otherwise (and whenever the source would contain the
     * host). Returns the drawable set as background.
     */
    public static GlassDrawable apply(View host, View source, GlassDrawable.Preset preset, float radiusPx) {
        Context c = host.getContext();
        GlassDrawable d;
        if (Build.VERSION.SDK_INT >= 31 && source != null && !isSelfOrAncestor(source, host)) {
            d = new BackdropDrawable(c, host, source, preset, radiusPx);
        } else {
            d = new GlassDrawable(c, preset, radiusPx);
        }
        host.setBackground(d);
        // No outline clipping: buttons inside may pop out past the edge when pressed.
        return d;
    }

    /** True if {@code v} is {@code host} or contains it. */
    static boolean isSelfOrAncestor(View v, View host) {
        for (Object p = host; p instanceof View; p = ((View) p).getParent()) {
            if (p == v) return true;
        }
        return false;
    }

    private BackdropDrawable(Context ctx, View host, View source, GlassDrawable.Preset preset, float radiusPx) {
        super(ctx, preset, radiusPx);
        this.host = host;
        this.source = source;
        // The spec's blur radii are for full resolution; the content is recorded at SCALE.
        this.blurPx = Math.max(1f, blurPx() * SCALE * 1.6f);
        this.bgColor = pal.background;
        host.addOnAttachStateChangeListener(this);
        if (host.isAttachedToWindow()) onViewAttachedToWindow(host);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        v.getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        v.getViewTreeObserver().removeOnPreDrawListener(this);
        if (Build.VERSION.SDK_INT >= 29 && node != null) ((RenderNode) node).discardDisplayList();
        ready = false;
        live = false;
    }

    @Override
    public boolean onPreDraw() {
        if (Build.VERSION.SDK_INT < 31 || !host.isShown() || host.getWidth() == 0 || capturing) return true;
        if (isSelfOrAncestor(source, host)) return true; // re-parented since: never record ourselves
        int w = Math.max(1, Math.round(host.getWidth() * SCALE));
        int h = Math.max(1, Math.round(host.getHeight() * SCALE));
        RenderNode n = (RenderNode) node;
        if (n == null) {
            n = new RenderNode("glass-backdrop");
            node = n;
        }
        int ohx = hostLoc[0], ohy = hostLoc[1], osx = srcLoc[0], osy = srcLoc[1];
        host.getLocationInWindow(hostLoc);
        source.getLocationInWindow(srcLoc);
        boolean moved = ohx != hostLoc[0] || ohy != hostLoc[1] || osx != srcLoc[0] || osy != srcLoc[1];
        n.setPosition(0, 0, w, h);
        RecordingCanvas rc = n.beginRecording(w, h);
        capturing = true;
        try {
            rc.drawColor(bgColor);
            rc.scale(SCALE, SCALE);
            rc.translate(srcLoc[0] - hostLoc[0], srcLoc[1] - hostLoc[1]);
            source.draw(rc);
        } catch (RuntimeException ignored) {
            // A view that cannot be recorded this frame: keep the previous blur.
        } finally {
            capturing = false;
            n.endRecording();
        }
        if (effect == null) {
            // Blur, then "vibrancy": colours behind the glass get 1.5x saturation, like real glass.
            android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
            sat.setSaturation(1.5f);
            effect = RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(sat),
                    RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
        }
        n.setRenderEffect((RenderEffect) effect);
        boolean first = !ready;
        ready = true;
        live = true;
        // The host's display list draws this node by reference, so a re-recorded node shows on
        // this frame without redrawing the host. Invalidating the host every time scheduled
        // another frame from inside every frame: the screen never stopped redrawing (and
        // re-recording the whole page), which made long lists and switches lag.
        // Redraw the glass only when what is behind it changed this frame: the source (or anything
        // inside it) was invalidated — scrolling, streaming text — or the glass moved. The source
        // never contains the host, so this can not loop.
        if (first || moved || source.isDirty()) host.invalidate();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        if (capturing) return; // never part of its own backdrop
        if (Build.VERSION.SDK_INT >= 31 && ready && node != null && c.isHardwareAccelerated()) {
            c.save();
            c.clipPath(path());
            c.scale(1f / SCALE, 1f / SCALE);
            c.drawRenderNode((RenderNode) node);
            c.restore();
        }
        super.draw(c);
    }
}
