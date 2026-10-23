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
 * rendering). Before each frame it records the screen content under the host into a small
 * RenderNode, blurs it on the GPU with a RenderEffect and draws it clipped to the glass shape, then
 * the glass tint and specular rim on top. Content is recorded at a reduced scale, so the blur is
 * cheap. On older Android it is plain {@link GlassDrawable} glass (slightly more opaque).
 *
 * While the content is being recorded every backdrop draws nothing, so glass never blurs itself.
 */
public final class BackdropDrawable extends GlassDrawable implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {

    private static int capturing;
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
        Context c = host.getContext();
        if (Build.VERSION.SDK_INT >= 31 && source != null) {
            host.setBackground(new BackdropDrawable(c, host, source, radiusPx));
        } else {
            host.setBackground(new GlassDrawable(c, radiusPx, c.getColor(R.color.glass_fill_strong)));
        }
        host.setClipToOutline(true);
    }

    private BackdropDrawable(Context ctx, View host, View source, float radiusPx) {
        super(ctx, radiusPx);
        this.host = host;
        this.source = source;
        this.blurPx = 26f * ctx.getResources().getDisplayMetrics().density * SCALE;
        this.bgColor = ctx.getColor(R.color.bg_bottom);
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
    }

    @Override
    public boolean onPreDraw() {
        if (Build.VERSION.SDK_INT < 31 || !host.isShown() || host.getWidth() == 0 || capturing > 0) return true;
        int w = Math.max(1, Math.round(host.getWidth() * SCALE));
        int h = Math.max(1, Math.round(host.getHeight() * SCALE));
        RenderNode n = (RenderNode) node;
        if (n == null) {
            n = new RenderNode("glass-backdrop");
            node = n;
        }
        host.getLocationInWindow(hostLoc);
        source.getLocationInWindow(srcLoc);
        n.setPosition(0, 0, w, h);
        RecordingCanvas rc = n.beginRecording(w, h);
        capturing++;
        try {
            rc.drawColor(bgColor);
            rc.scale(SCALE, SCALE);
            rc.translate(srcLoc[0] - hostLoc[0], srcLoc[1] - hostLoc[1]);
            source.draw(rc);
        } catch (RuntimeException ignored) {
            // A view that cannot be recorded this frame: keep the previous blur.
        } finally {
            capturing--;
            n.endRecording();
        }
        n.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
        ready = true;
        host.invalidate();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        if (capturing > 0) return; // never part of its own backdrop
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
