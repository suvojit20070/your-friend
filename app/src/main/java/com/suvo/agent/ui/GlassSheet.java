package com.suvo.agent.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Glass bottom sheet in the screen's overlay host: blurs the page behind it, 32dp squircle top
 * corners, a 36 x 4 dp handle. Drag it: it follows the finger, rubber-bands above the open
 * position, and closes when let go below 30% of its height or flung down; otherwise it springs
 * back. In 300 ms, out 220 ms (FastOutSlowIn), scrim 32%.
 *
 * {@link #options} builds the standard option list: 64dp rows, 20dp highlight, the selected one on
 * primaryContainer with a check. Picking an option first animates the sheet out, then acts.
 */
public final class GlassSheet extends Overlay {

    public interface OnPick {
        void onPick(int which);
    }

    private final LinearLayout panel;
    private final LinearLayout body;
    private final ScrollView scroll;
    private OnPick onPick;
    private int navInset;

    public GlassSheet(Activity a, CharSequence title, View content) {
        super(a, 0.32f);
        Palette pal = Palette.of(a);
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        GlassDrawable bg = BackdropDrawable.apply(panel, contentRoot(a), GlassDrawable.Preset.SHEET);
        bg.topOnly();
        GlassDrawable.shadow(panel, GlassDrawable.Preset.SHEET);
        View handle = new View(a);
        handle.setBackground(new TonalDrawable(Palette.alpha(pal.onSurfaceVariant, 0.4f), -1));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(12);
        hp.bottomMargin = dp(8);
        panel.addView(handle, hp);
        if (title != null) {
            TextView t = new TextView(a, null, 0, R.style.YF_Title);
            t.setText(title);
            t.setTextSize(18f);
            t.setTextColor(pal.primary);
            t.setPadding(dp(24), dp(8), dp(24), dp(8));
            panel.addView(t);
        }
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(dp(16), dp(4), dp(16), 0);
            body.addView(content, cp);
        }
        scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(body);
        int max = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.78f);
        panel.addView(new GlassDialog.MaxHeightFrame(a, max, scroll),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The panel reaches 40dp below the screen, so pulling it up never shows a gap under it.
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        plp.bottomMargin = -dp(40);
        layer.addView(panel, plp);
        panel.setVisibility(View.INVISIBLE);
        installDrag();
    }

    /** Option list sheet. {@code selected} -1 = none. */
    public static GlassSheet options(Activity a, CharSequence title, CharSequence[] labels, int selected, int[] icons) {
        Palette pal = Palette.of(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        GlassSheet s = new GlassSheet(a, title, list);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 52));
            row.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
            row.setBackground(new TonalDrawable(sel ? pal.primaryContainer : 0, Ui.dp(a, 20)).pressable(pal.pressed()));
            row.setTag(R.id.fx_press, "row");
            if (icons != null && i < icons.length && icons[i] != 0) {
                ImageView iv = new ImageView(a);
                iv.setImageResource(icons[i]);
                iv.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24));
                ip.setMarginEnd(Ui.dp(a, 16));
                row.addView(iv, ip);
            }
            TextView t = new TextView(a, null, 0, R.style.YF_Body);
            t.setText(labels[i]);
            t.setTextColor(sel ? onContainer(pal) : pal.onSurface);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (sel) {
                ImageView ck = new ImageView(a);
                ck.setImageResource(R.drawable.ic_check);
                ck.setImageTintList(ColorStateList.valueOf(onContainer(pal)));
                row.addView(ck, new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
            }
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                s.pick(idx);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(Ui.dp(a, -4), Ui.dp(a, 2), Ui.dp(a, -4), 0);
            list.addView(row, rp);
        }
        return s;
    }

    static int onContainer(Palette pal) {
        return pal.onPrimaryContainer;
    }

    public GlassSheet setOnPick(OnPick p) {
        onPick = p;
        return this;
    }

    /** Animates out first, then runs the pick. */
    void pick(int which) {
        OnPick p = onPick;
        setOnDismissListener(null);
        dismiss();
        if (p != null) Fx.later(() -> p.onPick(which), 230);
    }

    @Override
    protected void onInsets(int left, int top, int right, int bottom) {
        navInset = bottom;
        panel.setPadding(left, 0, right, bottom + dp(24) + dp(40));
    }

    @Override
    protected void animateIn() {
        scrim.animate().alpha(1f).setDuration(300).start();
        panel.setTranslationY(panel.getHeight() + dp(24));
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationY(0).setDuration(300).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f)).start();
    }

    @Override
    protected void animateOut(Runnable done) {
        scrim.animate().alpha(0f).setDuration(220).start();
        panel.animate().translationY(panel.getHeight() + dp(24)).setDuration(220).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(done).start();
    }

    /** Follow the finger; rubber band above the open position; close below 30% or on a fling down. */
    private void installDrag() {
        final float[] start = new float[2];
        final VelocityTracker[] vt = new VelocityTracker[1];
        View.OnTouchListener drag = (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawY();
                    start[1] = panel.getTranslationY();
                    panel.animate().cancel();
                    vt[0] = VelocityTracker.obtain();
                    vt[0].addMovement(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (vt[0] != null) vt[0].addMovement(e);
                    float d = start[1] + e.getRawY() - start[0];
                    if (d < 0) {
                        // Only a small rubber band upward (at most 16dp), strongly resisted.
                        float max = dp(16);
                        d = -(float) (max * (1 - Math.exp(-(-d) * 0.52f / max)));
                    }
                    panel.setTranslationY(d);
                    scrim.setAlpha(Math.max(0f, 1f - Math.max(0, d) / Math.max(1, panel.getHeight())));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float vy = 0;
                    if (vt[0] != null) {
                        vt[0].addMovement(e);
                        vt[0].computeCurrentVelocity(1000);
                        vy = vt[0].getYVelocity();
                        vt[0].recycle();
                        vt[0] = null;
                    }
                    if (cancelable && (panel.getTranslationY() > panel.getHeight() * 0.3f || vy > dp(900))) {
                        dismiss();
                    } else {
                        scrim.animate().alpha(1f).setDuration(200).start();
                        new Fx.Spring(panel.getTranslationY(), 400f, 1.0f, panel::setTranslationY).animateTo(0f);
                    }
                    return true;
                }
                default:
                    return false;
            }
        };
        panel.setOnTouchListener(drag);
    }

    public LinearLayout body() {
        return body;
    }
}
