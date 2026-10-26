package com.suvo.agent.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Glass popover menu that grows OUT OF the button that opened it: at the start it is exactly the
 * button (same size and position, fully round), then over 240 ms (FastOutSlowIn) its width,
 * height and corner radius morph to the menu (radius 20dp), keeping the button's top edge and its
 * end edge (BottomEnd for a right button, BottomStart for a left one, centred for a title chip).
 * The rows fade in after the first quarter. Closing runs the morph back in 180 ms.
 * Lives in the overlay host and blurs the real page behind it.
 */
public final class GlassPopover extends Overlay {

    private final View anchor;
    private final FrameLayout panel;
    private final View content;
    private final GlassDrawable glass;
    private final int targetW, targetH;
    private int ax, ay, aw, ah;
    private int align; // -1 start, 0 center, 1 end
    private ValueAnimator anim;

    private GlassPopover(Activity a, View anchor, View content, int width, int height) {
        super(a, 0f);
        this.anchor = anchor;
        this.content = content;
        this.targetW = width;
        this.targetH = height;
        panel = new FrameLayout(a);
        panel.setClickable(true);
        glass = BackdropDrawable.apply(panel, contentRoot(a), GlassDrawable.Preset.POPOVER);
        GlassDrawable.shadow(panel, GlassDrawable.Preset.POPOVER);
        panel.setClipToOutline(true);
        panel.addView(content, new FrameLayout.LayoutParams(width, height));
        layer.addView(panel, new FrameLayout.LayoutParams(1, 1));
        panel.setVisibility(View.INVISIBLE);
    }

    /** Builds and shows the standard menu. */
    public static GlassPopover menu(Activity a, View anchor, CharSequence title, List<Fx.Item> items, Fx.Pick pick) {
        Palette pal = Palette.of(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        final GlassPopover[] self = new GlassPopover[1];
        if (title != null) {
            TextView t = new TextView(a, null, 0, R.style.YF_Caption);
            t.setText(title);
            t.setTextColor(Palette.alpha(pal.onSurfaceVariant, 0.8f));
            t.setPadding(Ui.dp(a, 18), Ui.dp(a, 8), Ui.dp(a, 18), Ui.dp(a, 4));
            box.addView(t);
        }
        for (int i = 0; i < items.size(); i++) {
            Fx.Item it = items.get(i);
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 44));
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
            int base = it.checked ? Palette.alpha(pal.dark ? Color.WHITE : Color.BLACK, 0.12f) : 0;
            row.setBackground(new TonalDrawable(base, Ui.dp(a, 16)).pressable(pal.pressed()));
            row.setTag(R.id.fx_press, "row");
            if (it.icon != 0) {
                ImageView iv = new ImageView(a);
                iv.setImageResource(it.icon);
                iv.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20));
                ip.setMarginEnd(Ui.dp(a, 10));
                row.addView(iv, ip);
            }
            TextView t = new TextView(a, null, 0, R.style.YF_Body);
            t.setText(it.label);
            t.setTextSize(15f);
            t.setTextColor(pal.onSurface);
            t.setMaxLines(2);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (it.checked) {
                ImageView ck = new ImageView(a);
                ck.setImageResource(R.drawable.ic_check);
                ck.setImageTintList(ColorStateList.valueOf(pal.primary));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18));
                cp.setMarginStart(Ui.dp(a, 10));
                row.addView(ck, cp);
            }
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                if (self[0] != null) self[0].dismiss();
                Fx.later(() -> pick.onPick(idx), 190);
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.setMargins(Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2));
            box.addView(row, rl);
        }
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(Ui.dp(a, 180), Math.min(Ui.dp(a, 280), screenW - Ui.dp(a, 88)));
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = box.getMeasuredHeight();
        View content = box;
        int maxH = Ui.dp(a, 256 + 28);
        if (h > maxH) {
            // ≈ 5.5 rows visible: the half row peeking out says "scroll".
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(box);
            content = sv;
            h = maxH;
        }
        GlassPopover p = new GlassPopover(a, anchor, content, width, h);
        self[0] = p;
        p.show();
        return p;
    }

    @Override
    protected void onInsets(int left, int top, int right, int bottom) {
        int[] host = new int[2], loc = new int[2];
        layer.getLocationInWindow(host);
        if (anchor != null && anchor.isAttachedToWindow()) {
            anchor.getLocationInWindow(loc);
            ax = loc[0] - host[0];
            ay = loc[1] - host[1];
            aw = Math.max(1, Math.round(anchor.getWidth() * anchor.getScaleX()));
            ah = Math.max(1, Math.round(anchor.getHeight() * anchor.getScaleY()));
        } else {
            int sw = activity.getResources().getDisplayMetrics().widthPixels;
            ax = sw - dp(60);
            ay = top + dp(12);
            aw = ah = dp(48);
        }
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        float cx = ax + aw / 2f;
        align = cx > screenW * 0.62f ? 1 : cx < screenW * 0.38f ? -1 : 0;
    }

    private void layout(float p) {
        int w = Math.round(aw + (targetW - aw) * p);
        int h = Math.round(ah + (targetH - ah) * p);
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int left = align > 0 ? ax + aw - w : align < 0 ? ax : Math.round(ax + aw / 2f - w / 2f);
        left = Math.max(dp(8), Math.min(screenW - w - dp(8), left));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = left;
        lp.topMargin = ay;
        lp.gravity = Gravity.TOP | Gravity.START;
        panel.setLayoutParams(lp);
        glass.setRadius(ah / 2f + (dp(20) - ah / 2f) * p);
        panel.invalidateOutline();
        // Rows keep their final layout; the panel reveals them from the button's corner.
        content.setTranslationX(align > 0 ? w - targetW : align < 0 ? 0 : (w - targetW) / 2f);
        content.setAlpha(Math.max(0f, (p - 0.25f) / 0.75f));
    }

    private void run(float from, float to, long ms, Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(ms);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(a -> layout((float) a.getAnimatedValue()));
        if (end != null) {
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    end.run();
                }
            });
        }
        anim.start();
    }

    @Override
    protected void animateIn() {
        layout(0f);
        panel.setVisibility(View.VISIBLE);
        run(0f, 1f, 240, null);
    }

    @Override
    protected void animateOut(Runnable done) {
        float from = anim != null && anim.isRunning() ? (float) anim.getAnimatedValue() : 1f;
        run(from, 0f, 180, () -> {
            panel.animate().alpha(0f).setDuration(60).withEndAction(done).start();
        });
    }
}
