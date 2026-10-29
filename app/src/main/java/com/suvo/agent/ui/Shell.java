package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * The screen shell of the Zafiro look: the page content scrolls UNDER a floating top bar.
 * At the top of the page the bar is invisible — only the round glass buttons and the title float
 * over the page. As soon as content scrolls under it, a progressive glass fades in (280 ms): the
 * content behind is blurred and tinted most strongly at the status bar and fades out smoothly
 * toward the bar's bottom, so there is no hard edge.
 *
 * Works on the existing layouts: a vertical root with the top bar (id top_bar) first and the
 * scrolling area (a ScrollView / list, or a frame whose first child is one) second. Other screens
 * keep their layout and only get the glass buttons.
 */
final class Shell {

    private Shell() {
    }

    /**
     * @return the scrolling view the glass blurs if the shell was installed (the shell then handles
     * the window insets itself), or null when the screen keeps its own layout.
     */
    static View install(Activity a, View root) {
        if (!(root instanceof LinearLayout) || ((LinearLayout) root).getOrientation() != LinearLayout.VERTICAL) return null;
        LinearLayout column = (LinearLayout) root;
        View bar = column.findViewById(R.id.top_bar);
        if (bar == null) return null;
        if (column.indexOfChild(bar) != 0 || column.getChildCount() < 2) {
            glassButtons(a, bar, null);
            return null;
        }
        View scroller = scrollerOf(column.getChildAt(1));
        if (scroller == null) {
            glassButtons(a, bar, null);
            return null;
        }
        ViewGroup content = a.findViewById(android.R.id.content);
        content.removeView(column);
        column.removeView(bar);
        FrameLayout frame = new FrameLayout(a);
        frame.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View barBg = new View(a);
        ProgressiveBar pb = new ProgressiveBar(a, barBg, scroller);
        barBg.setBackground(pb);
        barBg.setAlpha(0f);
        barBg.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(barBg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ViewGroup.LayoutParams old = bar.getLayoutParams();
        frame.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, old.height));
        bar.setElevation(0);
        barBg.setTranslationZ(1f);
        bar.setTranslationZ(2f);
        content.addView(frame, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glassButtons(a, bar, scroller);

        // Insets: the column keeps left / right / bottom (and keyboard); the top goes to the bar and
        // into the scroller's padding, so content starts below the bar but can scroll under it.
        final int colL = column.getPaddingLeft(), colR = column.getPaddingRight(), colB = column.getPaddingBottom();
        final int scT = scroller.getPaddingTop();
        final int barH = old.height > 0 ? old.height : Ui.dp(a, 72);
        if (scroller instanceof ViewGroup) ((ViewGroup) scroller).setClipToPadding(false);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            column.setPadding(colL + left, 0, colR + right, colB + bottom);
            FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) bar.getLayoutParams();
            bl.topMargin = top;
            bl.leftMargin = left;
            bl.rightMargin = right;
            bar.setLayoutParams(bl);
            FrameLayout.LayoutParams gl = (FrameLayout.LayoutParams) barBg.getLayoutParams();
            gl.height = top + barH + Ui.dp(a, 8);
            barBg.setLayoutParams(gl);
            pb.setSolidUntil(top + barH / 2f);
            pb.setStatus(top);
            scroller.setPadding(scroller.getPaddingLeft(), scT + top + barH, scroller.getPaddingRight(), scroller.getPaddingBottom());
            return insets;
        });
        frame.requestApplyInsets();

        // Show the bar's glass only when content is under it.
        final boolean[] shown = {false};
        ViewTreeObserver.OnScrollChangedListener l = () -> {
            boolean under = scroller.canScrollVertically(-1);
            if (under == shown[0]) return;
            shown[0] = under;
            barBg.animate().alpha(under ? 1f : 0f).setDuration(280)
                    .setInterpolator(new android.view.animation.PathInterpolator(0f, 0f, 0.2f, 1f)).start();
        };
        scroller.getViewTreeObserver().addOnScrollChangedListener(l);
        scroller.getViewTreeObserver().addOnGlobalLayoutListener(l::onScrollChanged);
        return scroller;
    }

    /** First scrolling view: {@code v} itself, or the first child of a frame (chat list). */
    private static View scrollerOf(View v) {
        if (v instanceof ScrollView || v instanceof AbsListView) return v;
        if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() > 0) {
            View c = ((FrameLayout) v).getChildAt(0);
            if (c instanceof ScrollView || c instanceof AbsListView) return c;
        }
        return null;
    }

    /** Round corner buttons become live-blur glass (blurring the page under them) that swell when pressed. */
    static void glassButtons(Activity a, View bar, View source) {
        if (!(bar instanceof ViewGroup)) return;
        walk(a, (ViewGroup) bar, source);
    }

    private static void walk(Activity a, ViewGroup g, View source) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageButton && c.getTag() == null) cornerButton(c, source);
            else if (c instanceof ViewGroup) walk(a, (ViewGroup) c, source);
        }
    }

    static void cornerButton(View b, View source) {
        if (b == null) return;
        b.setBackgroundTintList(null);
        BackdropDrawable.apply(b, source, GlassDrawable.Preset.BUTTON);
        GlassDrawable.shadow(b, GlassDrawable.Preset.BUTTON);
        LiquidPress.attach(b, LiquidPress.CORNER);
        b.setTag("corner");
        if (b instanceof ImageButton) {
            ((ImageButton) b).setImageTintList(android.content.res.ColorStateList.valueOf(Palette.of(b.getContext()).onSurface));
        }
    }

    /** Progressive glass behind the top bar: live blur + tint, solid at the top, fading to nothing. */
    static final class ProgressiveBar extends Drawable implements ViewTreeObserver.OnPreDrawListener {
        private static final float SCALE = 0.25f;
        private final View host;
        private final View source;
        private final Palette pal;
        private final Paint tint = new Paint();
        private final Paint mask = new Paint();
        private final float blurPx;
        private float solidUntil;
        private Object node;
        private Object effect;
        private boolean ready, capturing;
        private final int[] hostLoc = new int[2], srcLoc = new int[2];

        ProgressiveBar(Context ctx, View host, View source) {
            this.host = host;
            this.source = source;
            this.pal = Palette.of(ctx);
            this.blurPx = 16 * ctx.getResources().getDisplayMetrics().density * SCALE * 1.6f;
            tint.setColor(Palette.alpha(pal.surfaceHigh, pal.dark ? 0.72f : 0.65f));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().addOnPreDrawListener(ProgressiveBar.this);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(ProgressiveBar.this);
                    ready = false;
                }
            });
        }

        private float statusH;
        private final Paint solid = new Paint();

        void setStatus(float h) {
            statusH = h;
            invalidateSelf();
        }

        void setSolidUntil(float y) {
            solidUntil = y;
            updateMask();
            invalidateSelf();
        }

        private void updateMask() {
            float h = Math.max(1, getBounds().height());
            float start = Math.min(solidUntil, h - 1);
            // Smoothstep-like fade from the middle of the button row to the bar's bottom.
            mask.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Color.BLACK, Color.BLACK, Palette.alpha(Color.BLACK, 0.85f), Palette.alpha(Color.BLACK, 0.5f),
                            Palette.alpha(Color.BLACK, 0.15f), Color.TRANSPARENT},
                    new float[]{0f, start / h, (start + (h - start) * 0.25f) / h, (start + (h - start) * 0.5f) / h,
                            (start + (h - start) * 0.75f) / h, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            updateMask();
        }

        @Override
        public boolean onPreDraw() {
            if (Build.VERSION.SDK_INT < 31 || host.getAlpha() <= 0.01f || host.getWidth() == 0 || capturing) return true;
            int w = Math.max(1, Math.round(host.getWidth() * SCALE));
            int h = Math.max(1, Math.round(host.getHeight() * SCALE));
            RenderNode n = (RenderNode) node;
            if (n == null) {
                n = new RenderNode("topbar-backdrop");
                node = n;
            }
            host.getLocationInWindow(hostLoc);
            source.getLocationInWindow(srcLoc);
            n.setPosition(0, 0, w, h);
            RecordingCanvas rc = n.beginRecording(w, h);
            capturing = true;
            try {
                rc.drawColor(pal.background);
                rc.scale(SCALE, SCALE);
                rc.translate(srcLoc[0] - hostLoc[0], srcLoc[1] - hostLoc[1]);
                source.draw(rc);
            } catch (RuntimeException ignored) {
            } finally {
                capturing = false;
                n.endRecording();
            }
            if (effect == null) {
                android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
                sat.setSaturation(1.5f);
                effect = RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(sat),
                        RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
            }
            n.setRenderEffect((RenderEffect) effect);
            // The bar draws this node by reference: re-recording it is enough for this frame.
            // Invalidating every time asked for a new frame from inside each frame (endless redraw).
            if (!ready) host.invalidate();
            ready = true;
            return true;
        }

        @Override
        public void draw(Canvas c) {
            if (capturing) return;
            android.graphics.Rect b = getBounds();
            int save = c.saveLayer(b.left, b.top, b.right, b.bottom, null);
            if (Build.VERSION.SDK_INT >= 31 && ready && node != null && c.isHardwareAccelerated()) {
                c.save();
                c.scale(1f / SCALE, 1f / SCALE);
                c.drawRenderNode((RenderNode) node);
                c.restore();
                c.drawRect(b, tint);
            } else {
                // No live blur: tonal fallback, a little more opaque.
                Paint p = new Paint();
                p.setColor(Palette.alpha(pal.surfaceHigh, 0.94f));
                c.drawRect(b, p);
            }
            // Fully solid inside the status bar, easing into the glass tint below it.
            if (statusH > 0) {
                solid.setShader(new LinearGradient(0, b.top, 0, b.top + statusH * 1.6f, pal.surfaceHigh,
                        Palette.alpha(pal.surfaceHigh, 0f), Shader.TileMode.CLAMP));
                c.drawRect(b.left, b.top, b.right, b.top + statusH * 1.6f, solid);
            }
            c.drawRect(b, mask);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
