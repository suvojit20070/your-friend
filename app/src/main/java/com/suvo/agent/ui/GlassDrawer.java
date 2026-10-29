package com.suvo.agent.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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
 * Side drawer in the screen's overlay host, with the same glass as the dialogs: a floating panel
 * that blurs the real page behind it (Dialog preset, 36dp squircle, soft shadow) over a 42% scrim.
 * It slides in from the start edge (300 ms, FastOutSlowIn) and can be dragged or flung back to
 * close; a tap on the scrim or Back closes it too.
 *
 * Rows are 52dp with a 20dp highlight; the current screen sits on primaryContainer. Picking a row
 * first slides the drawer out, then acts, so the next screen never opens under a moving panel.
 */
public final class GlassDrawer extends Overlay {

    private final DrawerPanel panel;
    private final LinearLayout list;
    private final Palette pal;
    private final int width;
    private boolean rtl;

    public GlassDrawer(Activity a, CharSequence title, CharSequence subtitle) {
        super(a, 0.42f);
        pal = Palette.of(a);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        width = Math.min(dp(320), Math.round(screenW * 0.84f));

        panel = new DrawerPanel(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true); // taps on the panel do not reach the scrim
        panel.setTag(R.id.fx_press, Boolean.TRUE); // no press squish on the whole drawer (it drags)
        // The list must be clipped to the panel: unclipped, scrolled rows were drawn over the header.
        panel.setClipChildren(true);
        panel.setClipToOutline(true);
        BackdropDrawable.apply(panel, contentRoot(a), GlassDrawable.Preset.DIALOG, dp(36));
        GlassDrawable.shadow(panel, GlassDrawable.Preset.DIALOG);
        panel.setVisibility(View.INVISIBLE);

        // Header: app mark, title and a short line under it.
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(a);
        mark.setImageResource(R.drawable.ic_character);
        mark.setImageTintList(ColorStateList.valueOf(pal.primary));
        mark.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.14f), -1));
        int mp = dp(10);
        mark.setPadding(mp, mp, mp, mp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        mlp.setMarginEnd(dp(14));
        head.addView(mark, mlp);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a, null, 0, R.style.YF_Title);
        t.setText(title);
        t.setTextSize(20f);
        t.setTextColor(pal.onSurface);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(a, null, 0, R.style.YF_Caption);
            s.setText(subtitle);
            s.setTextColor(pal.onSurfaceVariant);
            s.setMaxLines(1);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            texts.addView(s, slp);
        }
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(dp(20), dp(22), dp(20), dp(10));
        panel.addView(head, hlp);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setClipToPadding(true);
        scroll.setClipChildren(true);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(20));
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sclp.bottomMargin = dp(12);
        panel.addView(scroll, sclp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        layer.addView(panel, plp);
        installDrag();
    }

    /** A small section title inside the drawer. */
    public GlassDrawer section(CharSequence title) {
        TextView t = new TextView(activity, null, 0, R.style.YF_Section);
        t.setText(title);
        t.setTextColor(Palette.alpha(pal.onSurfaceVariant, 0.85f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(24), dp(list.getChildCount() == 0 ? 6 : 18), dp(24), dp(6));
        list.addView(t, lp);
        return this;
    }

    /** A row; {@code current} marks the screen you are on. {@code action} runs after the drawer closed. */
    public GlassDrawer item(int icon, CharSequence label, boolean current, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(new TonalDrawable(current ? pal.primaryContainer : 0, dp(20)).pressable(pal.pressed()));
        row.setTag(R.id.fx_press, "row");
        int fg = current ? pal.onPrimaryContainer : pal.onSurface;
        if (icon != 0) {
            ImageView iv = new ImageView(activity);
            iv.setImageResource(icon);
            iv.setImageTintList(ColorStateList.valueOf(current ? pal.onPrimaryContainer : pal.onSurfaceVariant));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
            ip.setMarginEnd(dp(16));
            row.addView(iv, ip);
        }
        TextView t = new TextView(activity, null, 0, R.style.YF_Body);
        t.setText(label);
        t.setTextSize(15.5f);
        t.setTextColor(fg);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (current) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            setOnDismissListener(null);
            dismiss();
            if (action != null) Fx.later(action, 200);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(1), dp(10), dp(1));
        list.addView(row, lp);
        return this;
    }

    @Override
    protected void onInsets(int left, int top, int right, int bottom) {
        // The panel floats: its own margins keep it off the system bars and the screen edges.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.setMarginStart(dp(12) + (rtl() ? right : left));
        lp.topMargin = top + dp(12);
        lp.bottomMargin = bottom + dp(12);
        panel.setLayoutParams(lp);
    }

    private boolean rtl() {
        rtl = activity.getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        return rtl;
    }

    /** Fully hidden position (off the start edge). */
    private float hidden() {
        float off = width + dp(40);
        return rtl ? off : -off;
    }

    @Override
    protected void animateIn() {
        rtl();
        scrim.animate().alpha(1f).setDuration(300).start();
        panel.setTranslationX(hidden());
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationX(0).setDuration(300).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f)).start();
    }

    @Override
    protected void animateOut(Runnable done) {
        scrim.animate().alpha(0f).setDuration(220).start();
        panel.animate().translationX(hidden()).setDuration(220).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(done).start();
    }

    /** Drag towards the edge to close: follows the finger, closes past 35% or on a fling. */
    private void installDrag() {
        final float[] start = new float[3];
        final boolean[] dragging = {false};
        final VelocityTracker[] vt = new VelocityTracker[1];
        final int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        panel.drag = e -> handleDrag(e, start, dragging, vt, slop);
    }

    private boolean handleDrag(MotionEvent e, float[] start, boolean[] dragging, VelocityTracker[] vt, int slop) {
        float dir = rtl ? 1f : -1f; // closing direction
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                start[0] = e.getRawX();
                start[1] = e.getRawY();
                start[2] = panel.getTranslationX();
                dragging[0] = false;
                if (vt[0] != null) vt[0].recycle();
                vt[0] = VelocityTracker.obtain();
                vt[0].addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (vt[0] != null) vt[0].addMovement(e);
                float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                if (!dragging[0]) {
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f && dx * dir > 0) {
                        dragging[0] = true;
                        panel.animate().cancel();
                        start[0] = e.getRawX();
                    } else {
                        return false;
                    }
                }
                float d = e.getRawX() - start[0];
                float x = d * dir > 0 ? d : -(float) (dp(12) * (1 - Math.exp(-Math.abs(d) * 0.5f / dp(12)))) * dir;
                panel.setTranslationX(start[2] + x);
                scrim.setAlpha(Math.max(0f, 1f - Math.abs(panel.getTranslationX()) / Math.max(1, width)));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = dragging[0];
                dragging[0] = false;
                float vx = 0;
                if (vt[0] != null) {
                    vt[0].addMovement(e);
                    vt[0].computeCurrentVelocity(1000);
                    vx = vt[0].getXVelocity();
                    vt[0].recycle();
                    vt[0] = null;
                }
                if (!was) return false;
                if (cancelable && (Math.abs(panel.getTranslationX()) > width * 0.35f || vx * dir > dp(900))) {
                    dismiss();
                } else {
                    scrim.animate().alpha(1f).setDuration(200).start();
                    new Fx.Spring(panel.getTranslationX(), 400f, 1.0f, panel::setTranslationX).animateTo(0f);
                }
                return true;
            }
            default:
                return dragging[0];
        }
    }

    /**
     * The panel sees horizontal drags even when they start on a row or the list (it intercepts
     * them once they clearly go sideways); taps and vertical scrolls stay with the children.
     */
    static final class DrawerPanel extends LinearLayout {
        interface Drag {
            boolean handle(MotionEvent e);
        }

        Drag drag;

        DrawerPanel(android.content.Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return (drag != null && drag.handle(e)) || super.onInterceptTouchEvent(e);
        }

        @Override
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            if (drag != null && drag.handle(e)) return true;
            super.onTouchEvent(e);
            return true; // the panel itself never lets touches fall through to the scrim
        }
    }
}
