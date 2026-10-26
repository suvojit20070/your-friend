package com.suvo.agent.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Centered glass dialog that lives in the screen's overlay host and blurs the real page behind
 * it: 48dp squircle, Dialog glass preset, 24dp padding, a 24sp title, and at most two equal-width
 * liquid capsule buttons (the positive one is the single solid primary). A third ("neutral")
 * action is shown as a quiet text action below them. It settles in (fade 120 ms, scale 1.04 → 1
 * in 240 ms) and leaves the same way in 180 ms.
 *
 * {@link Builder} mirrors the parts of AlertDialog.Builder the app uses. A plain list of items or a
 * single choice opens as a {@link GlassSheet} instead (thumb reach, scrolls), as the design asks.
 */
public final class GlassDialog extends Overlay {

    private final LinearLayout panel;
    private final FrameLayout center;

    private GlassDialog(Activity a) {
        super(a, 0.42f);
        center = new FrameLayout(a);
        center.setClipChildren(false);
        center.setClipToPadding(false);
        layer.addView(center, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(24), dp(24), dp(20));
        panel.setClickable(true); // taps on the panel do not reach the scrim
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setAlpha(0f); // nothing shows until the enter animation runs (no first-frame flash)
        BackdropDrawable.apply(panel, contentRoot(a), GlassDrawable.Preset.DIALOG);
        GlassDrawable.shadow(panel, GlassDrawable.Preset.DIALOG);
        int w = Math.min(a.getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(dp(200), w), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        center.addView(panel, lp);
    }

    @Override
    protected void onInsets(int left, int top, int right, int bottom) {
        center.setPadding(left, top + dp(24), right, bottom + dp(24));
    }

    @Override
    protected void animateIn() {
        scrim.animate().alpha(1f).setDuration(120).setInterpolator(new LinearInterpolator()).start();
        panel.setAlpha(0f);
        panel.setScaleX(1.04f);
        panel.setScaleY(1.04f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f)).start();
    }

    @Override
    protected void animateOut(Runnable done) {
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(180).setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(done).start();
    }

    // ===================================================================================== builder

    public interface OnMultiChoice extends DialogInterface.OnMultiChoiceClickListener {
    }

    /** Same calls as AlertDialog.Builder (the subset the app uses). */
    public static final class Builder {
        final Context ctx;
        CharSequence title, message;
        View view;
        CharSequence[] items;
        DialogInterface.OnClickListener itemsListener;
        int checkedItem = -2;
        boolean[] multiChecked;
        DialogInterface.OnMultiChoiceClickListener multiListener;
        CharSequence posText, negText, neuText;
        DialogInterface.OnClickListener pos, neg, neu;
        boolean cancelable = true;
        DialogInterface.OnDismissListener dismissListener;

        public Builder(Context ctx) {
            this.ctx = ctx;
        }

        public Context getContext() {
            return ctx;
        }

        public Builder setTitle(int res) {
            title = ctx.getString(res);
            return this;
        }

        public Builder setTitle(CharSequence t) {
            title = t;
            return this;
        }

        public Builder setMessage(int res) {
            message = ctx.getString(res);
            return this;
        }

        public Builder setMessage(CharSequence m) {
            message = m;
            return this;
        }

        public Builder setView(View v) {
            view = v;
            return this;
        }

        public Builder setItems(CharSequence[] it, DialogInterface.OnClickListener l) {
            items = it;
            itemsListener = l;
            checkedItem = -2;
            return this;
        }

        public Builder setSingleChoiceItems(CharSequence[] it, int checked, DialogInterface.OnClickListener l) {
            items = it;
            itemsListener = l;
            checkedItem = checked;
            return this;
        }

        public Builder setMultiChoiceItems(CharSequence[] it, boolean[] checked, DialogInterface.OnMultiChoiceClickListener l) {
            items = it;
            multiChecked = checked;
            multiListener = l;
            return this;
        }

        public Builder setPositiveButton(int res, DialogInterface.OnClickListener l) {
            return setPositiveButton(ctx.getString(res), l);
        }

        public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) {
            posText = t;
            pos = l;
            return this;
        }

        public Builder setNegativeButton(int res, DialogInterface.OnClickListener l) {
            return setNegativeButton(ctx.getString(res), l);
        }

        public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) {
            negText = t;
            neg = l;
            return this;
        }

        public Builder setNeutralButton(int res, DialogInterface.OnClickListener l) {
            return setNeutralButton(ctx.getString(res), l);
        }

        public Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) {
            neuText = t;
            neu = l;
            return this;
        }

        public Builder setCancelable(boolean c) {
            cancelable = c;
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener l) {
            dismissListener = l;
            return this;
        }

        /** Builds and shows. Lists without buttons / view open as a sheet. */
        public Overlay show() {
            Activity a = activityOf(ctx);
            if (a == null) {
                showSystem();
                return null;
            }
            boolean listOnly = items != null && multiChecked == null && view == null && message == null
                    && posText == null && neuText == null;
            if (listOnly) {
                GlassSheet s = GlassSheet.options(a, title, items, checkedItem >= 0 ? checkedItem : -1, null);
                s.setOnPick(which -> {
                    if (itemsListener != null) itemsListener.onClick(s, which);
                });
                s.setCancelable(cancelable);
                if (dismissListener != null) s.setOnDismissListener(dismissListener);
                s.show();
                return s;
            }
            GlassDialog d = new GlassDialog(a);
            d.build(this);
            d.setCancelable(cancelable);
            if (dismissListener != null) d.setOnDismissListener(dismissListener);
            d.show();
            return d;
        }

        /** Without an activity (rare): the platform dialog, styled as before. */
        private void showSystem() {
            AlertDialog.Builder b = new AlertDialog.Builder(ctx, R.style.Theme_YourFriend_Dialog);
            if (title != null) b.setTitle(title);
            if (message != null) b.setMessage(message);
            if (view != null) b.setView(view);
            if (items != null && multiChecked != null) b.setMultiChoiceItems(items, multiChecked, multiListener);
            else if (items != null && checkedItem >= -1 && checkedItem != -2) b.setSingleChoiceItems(items, checkedItem, itemsListener);
            else if (items != null) b.setItems(items, itemsListener);
            if (posText != null) b.setPositiveButton(posText, pos);
            if (negText != null) b.setNegativeButton(negText, neg);
            if (neuText != null) b.setNeutralButton(neuText, neu);
            b.setCancelable(cancelable);
            b.show();
        }
    }

    // ===================================================================================== content

    private void build(Builder b) {
        Palette pal = Palette.of(activity);
        if (b.title != null) {
            TextView t = new TextView(activity, null, 0, R.style.YF_DialogTitle);
            t.setText(b.title);
            t.setTextSize(24f);
            t.setTextColor(pal.onSurface);
            panel.addView(t, wrap(0));
        }
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        if (b.message != null) {
            TextView m = new TextView(activity, null, 0, R.style.YF_Body);
            m.setText(b.message);
            m.setTextColor(pal.onSurfaceVariant);
            m.setTextIsSelectable(true);
            m.setMovementMethod(LinkMovementMethod.getInstance());
            body.addView(m, wrap(0));
        }
        if (b.items != null) {
            for (int i = 0; i < b.items.length; i++) body.addView(itemRow(b, i, pal), wrap(i == 0 && b.message == null ? 0 : 2));
        }
        if (b.view != null) {
            if (b.view.getParent() instanceof ViewGroup) ((ViewGroup) b.view.getParent()).removeView(b.view);
            body.addView(b.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (body.getChildCount() > 0 && b.view != null && hasScroller(b.view)) {
            // The view scrolls by itself (a list): no outer scroll view, it would steal its gestures.
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = b.title != null ? dp(12) : 0;
            int maxBody = activity.getResources().getDisplayMetrics().heightPixels - dp(48 + 24 + 24 + 64 + 120);
            panel.addView(new MaxHeightFrame(activity, maxBody, body, reserve(b)), sp);
        } else if (body.getChildCount() > 0) {
            // Long content scrolls inside the panel (max height = screen - 48dp, minus title and buttons).
            ScrollView sv = new ScrollView(activity);
            sv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(body);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = b.title != null ? dp(12) : 0;
            int maxBody = activity.getResources().getDisplayMetrics().heightPixels - dp(48 + 24 + 24 + 64 + 120);
            panel.addView(new MaxHeightFrame(activity, maxBody, sv, reserve(b)), sp);
        }
        int count = (b.posText != null ? 1 : 0) + (b.negText != null ? 1 : 0);
        if (count > 0) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(16);
            if (b.negText != null) row.addView(button(b.negText, false, DialogInterface.BUTTON_NEGATIVE, b.neg, pal), weight(row.getChildCount() > 0));
            if (b.posText != null) row.addView(button(b.posText, true, DialogInterface.BUTTON_POSITIVE, b.pos, pal), weight(row.getChildCount() > 0));
            panel.addView(row, rp);
        }
        if (b.neuText != null) {
            // Never three capsules: the extra action is a quiet text action under them.
            TextView n = new TextView(activity, null, 0, R.style.YF_Body);
            n.setText(b.neuText);
            n.setTextColor(pal.primary);
            n.setGravity(Gravity.CENTER);
            n.setPadding(dp(16), dp(12), dp(16), dp(8));
            n.setBackground(new TonalDrawable(0, -1).pressable(pal.pressed()));
            n.setTag(R.id.fx_press, "row");
            n.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                dismiss();
                if (b.neu != null) Fx.later(() -> b.neu.onClick(this, DialogInterface.BUTTON_NEUTRAL), 60);
            });
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(count > 0 ? 6 : 16);
            panel.addView(n, np);
        }
    }

    /** Height kept free below the body for the buttons, so they never leave the screen. */
    private int reserve(Builder b) {
        int r = dp(20); // panel bottom padding
        if (b.posText != null || b.negText != null) r += dp(16 + 64);
        if (b.neuText != null) r += dp(6 + 48);
        return r;
    }

    private static boolean hasScroller(View v) {
        if (v instanceof android.widget.AbsListView || v instanceof ScrollView) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasScroller(g.getChildAt(i))) return true;
        }
        return false;
    }

    private LinearLayout.LayoutParams wrap(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams weight(boolean gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(64), 1f);
        if (gap) p.setMarginStart(dp(12));
        return p;
    }

    /** Liquid capsule; 64dp tall leaves room for the press swell of the 56dp capsule. */
    private View button(CharSequence text, boolean primary, int which, DialogInterface.OnClickListener l, Palette pal) {
        FrameLayout slot = new FrameLayout(activity);
        slot.setClipChildren(false);
        Button btn = new Button(activity, null, 0, R.style.YF_Button);
        btn.setText(text);
        btn.setTextColor(primary ? pal.onPrimary : pal.onSurface);
        btn.setBackground(new TonalDrawable(primary ? pal.primary : pal.surfaceHighest, -1));
        btn.setMaxLines(1);
        btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setOnClickListener(v -> {
            dismiss();
            if (l != null) Fx.later(() -> l.onClick(this, which), 60);
        });
        LiquidPress.attach(btn, LiquidPress.CAPSULE);
        slot.addView(btn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.CENTER));
        return slot;
    }

    /** Multi-choice row (check) or plain item row inside a dialog that also has buttons or text. */
    private View itemRow(Builder b, int i, Palette pal) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(52));
        row.setBackground(new TonalDrawable(0, dp(20)).pressable(pal.pressed()));
        row.setTag(R.id.fx_press, "row");
        TextView t = new TextView(activity, null, 0, R.style.YF_Body);
        t.setText(b.items[i]);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (b.multiChecked != null || b.checkedItem >= 0) {
            ImageView ck = new ImageView(activity);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(ColorStateList.valueOf(pal.primary));
            boolean on = b.multiChecked != null ? b.multiChecked[i] : b.checkedItem == i;
            ck.setAlpha(on ? 1f : 0f);
            row.addView(ck, new LinearLayout.LayoutParams(dp(18), dp(18)));
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                if (b.multiChecked != null) {
                    b.multiChecked[i] = !b.multiChecked[i];
                    ck.animate().alpha(b.multiChecked[i] ? 1f : 0f).setDuration(160).start();
                    if (b.multiListener != null) b.multiListener.onClick(this, i, b.multiChecked[i]);
                } else if (b.itemsListener != null) {
                    b.itemsListener.onClick(this, i);
                }
            });
        } else {
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                dismiss();
                if (b.itemsListener != null) Fx.later(() -> b.itemsListener.onClick(this, i), 60);
            });
        }
        return row;
    }

    /** A frame that never grows taller than {@code max} px. */
    static final class MaxHeightFrame extends FrameLayout {
        private final int max;
        private final int reserve;

        MaxHeightFrame(Context c, int max, View child) {
            this(c, max, child, 0);
        }

        /** @param reserve space that must stay free for views below this one (dialog buttons) */
        MaxHeightFrame(Context c, int max, View child, int reserve) {
            super(c);
            this.max = Math.max(1, max);
            this.reserve = reserve;
            addView(child);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int mode = MeasureSpec.getMode(h);
            int size = MeasureSpec.getSize(h);
            int limit = mode == MeasureSpec.UNSPECIFIED ? max : Math.max(Math.min(size, max) - reserve, (int) (48 * getResources().getDisplayMetrics().density));
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
