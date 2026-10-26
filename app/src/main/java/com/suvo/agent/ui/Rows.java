package com.suvo.agent.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

/**
 * Builds settings-style screens: consecutive rows are grouped into one rounded card with thin
 * dividers (like the system Settings app), sections start a new group, and switches and sliders use
 * the Material 3 look from {@link Widgets}.
 *
 * Screens may add their own views to the same container between rows; a row added after such a
 * view starts a new group automatically, so the order on screen always matches the code.
 */
public final class Rows {

    public interface Change<T> {
        void onChange(T value);
    }

    private final Activity a;
    private final LinearLayout parent;
    private final Settings settings;
    private final Palette pal;
    private LinearLayout group;

    public Rows(Activity a, LinearLayout parent) {
        this.a = a;
        this.parent = parent;
        this.settings = Settings.get(a);
        this.pal = Palette.of(a);
    }

    /** The container; the current group is closed so views added next appear after it. */
    public LinearLayout parent() {
        group = null;
        return parent;
    }

    private LinearLayout group() {
        boolean stale = group == null || parent.getChildCount() == 0 || parent.getChildAt(parent.getChildCount() - 1) != group;
        if (stale) {
            group = new LinearLayout(a);
            group.setOrientation(LinearLayout.VERTICAL);
            // Card-28: tonal surfaceContainerLow squircle, no border, no shadow.
            group.setBackground(new TonalDrawable(pal.surfaceLow, Ui.dp(a, 28)));
            group.setClipToOutline(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(a, 8);
            p.bottomMargin = Ui.dp(a, 4);
            parent.addView(group, p);
        }
        return group;
    }

    private void addToGroup(View row, boolean insetDivider) {
        LinearLayout g = group();
        if (g.getChildCount() > 0) {
            // Ultra-subtle separation: 0.5dp outlineVariant at 35%, inset to where the text starts.
            View div = new View(a);
            div.setBackgroundColor(Palette.alpha(pal.outlineVariant, 0.35f));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Math.round(0.5f * a.getResources().getDisplayMetrics().density)));
            dp.setMarginStart(Ui.dp(a, insetDivider ? 60 : 20));
            dp.setMarginEnd(Ui.dp(a, 16));
            g.addView(div, dp);
        }
        g.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public TextView section(int titleRes) {
        return section(a.getString(titleRes));
    }

    public TextView section(CharSequence title) {
        group = null;
        TextView t = new TextView(a, null, 0, R.style.YF_Section);
        t.setText(title);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(a, 24);
        p.bottomMargin = Ui.dp(a, 0);
        p.setMarginStart(Ui.dp(a, 20));
        p.setMarginEnd(Ui.dp(a, 20));
        parent.addView(t, p);
        return t;
    }

    public TextView note(CharSequence text) {
        group = null;
        TextView t = new TextView(a, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 4));
        t.setTextSize(14f);
        t.setLineSpacing(Ui.dp(a, 4), 1f);
        parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private ImageView icon(int iconRes) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24));
        ip.setMarginEnd(Ui.dp(a, 16));
        iv.setLayoutParams(ip);
        return iv;
    }

    private LinearLayout baseRow(int iconRes) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(a.getResources().getDimensionPixelSize(R.dimen.row_min_height));
        row.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
        // Press = the whole row fades to onSurface 8% (no ripple, no squish) + a light haptic.
        row.setBackground(new TonalDrawable(0, 0).pressable(pal.pressed()));
        row.setTag(R.id.fx_press, "row");
        if (iconRes != 0) row.addView(icon(iconRes));
        return row;
    }

    /** A clickable row with title, subtitle and optional trailing view. Returns the subtitle view. */
    public TextView row(int iconRes, CharSequence title, CharSequence subtitle, View trailing, View.OnClickListener click) {
        LinearLayout row = baseRow(iconRes);
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a, null, 0, R.style.YF_RowTitle);
        t.setText(title);
        texts.addView(t);
        TextView s = new TextView(a, null, 0, R.style.YF_Caption);
        s.setText(subtitle == null ? "" : subtitle);
        s.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        s.setMaxLines(4);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setPadding(Ui.dp(a, 2), Ui.dp(a, 2), 0, 0);
        s.setTextSize(13f);
        s.setTextColor(Palette.alpha(pal.onSurfaceVariant, 0.82f));
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            // Keep a size the trailing view already has (e.g. a colour swatch); a bare View would
            // otherwise grow to fill the row.
            ViewGroup.LayoutParams own = trailing.getLayoutParams();
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(own != null ? own.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    own != null ? own.height : ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.setMarginStart(Ui.dp(a, 12));
            row.addView(trailing, tp);
        }
        if (click != null) row.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            click.onClick(v);
        });
        else {
            row.setClickable(false);
            row.setBackground(null);
        }
        addToGroup(row, iconRes != 0);
        return s;
    }

    private ImageView chevron() {
        ImageView c = new ImageView(a);
        c.setImageResource(R.drawable.ic_chevron);
        c.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        c.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        return c;
    }

    /** On / off: title + summary + liquid toggle; tapping the row toggles too. */
    public com.google.android.material.materialswitch.MaterialSwitch switchRow(int iconRes, int titleRes, int subtitleRes, String key, boolean def, Change<Boolean> change) {
        com.google.android.material.materialswitch.MaterialSwitch sw = Widgets.newSwitch(a);
        sw.setChecked(settings.bool(key, def));
        sw.setOnCheckedChangeListener((b, v) -> {
            settings.put(key, v);
            if (change != null) change.onChange(v);
        });
        row(iconRes, a.getString(titleRes), subtitleRes == 0 ? null : a.getString(subtitleRes), sw, v -> sw.toggle());
        return sw;
    }

    /**
     * Single choice stored as a string setting (labels and values are parallel arrays).
     * 2–4 short options: an inline segmented selector. More: a row with the current value that
     * opens a glass sheet of options. Returns the value preview (or the title for segments).
     */
    public TextView choiceRow(int iconRes, int titleRes, String key, String def, String[] labels, String[] values, Change<String> change) {
        boolean shortLabels = true;
        for (String l : labels) if (l.length() > 12) shortLabels = false;
        if (values.length >= 2 && values.length <= 4 && shortLabels) return segmentedRow(iconRes, titleRes, key, def, labels, values, change);
        final TextView[] sub = new TextView[1];
        sub[0] = preview(labelFor(settings.str(key, def), labels, values));
        LinearLayout trailing = new LinearLayout(a);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        trailing.addView(sub[0]);
        ImageView chev = chevron();
        ((LinearLayout.LayoutParams) chev.getLayoutParams()).setMarginStart(Ui.dp(a, 6));
        trailing.addView(chev);
        row(iconRes, a.getString(titleRes), null, trailing, v -> {
            String cur = settings.str(key, def);
            int idx = -1;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
            GlassSheet.options(a, a.getString(titleRes), labels, idx, null).setOnPick(which -> {
                settings.put(key, values[which]);
                sub[0].setText(labels[which]);
                if (change != null) change.onChange(values[which]);
            }).show();
        });
        return sub[0];
    }

    /** Segmented selector inside the card: title on top, capsules below; the selection pill slides. */
    private TextView segmentedRow(int iconRes, int titleRes, String key, String def, String[] labels, String[] values, Change<String> change) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (iconRes != 0) top.addView(icon(iconRes));
        TextView t = new TextView(a, null, 0, R.style.YF_RowTitle);
        t.setText(titleRes);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(top);
        android.widget.FrameLayout track = new android.widget.FrameLayout(a);
        View pill = new View(a);
        pill.setBackground(new TonalDrawable(Palette.alpha(pal.primary, 0.12f), -1));
        track.addView(pill, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout opts = new LinearLayout(a);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        track.addView(opts, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 40)));
        TextView[] cells = new TextView[values.length];
        final int[] sel = {0};
        String cur = settings.str(key, def);
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) sel[0] = i;
        final Fx.Spring[] slide = new Fx.Spring[1];
        Runnable paint = () -> {
            for (int i = 0; i < cells.length; i++) cells[i].setTextColor(i == sel[0] ? pal.primary : pal.onSurface);
        };
        for (int i = 0; i < values.length; i++) {
            final int idx = i;
            TextView c = new TextView(a, null, 0, R.style.YF_Body);
            c.setText(labels[i]);
            c.setGravity(Gravity.CENTER);
            c.setMaxLines(1);
            c.setEllipsize(TextUtils.TruncateAt.END);
            c.setTextSize(14.5f);
            c.setClickable(true);
            c.setTag(R.id.fx_press, "row");
            c.setOnClickListener(v -> {
                if (sel[0] == idx) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                sel[0] = idx;
                paint.run();
                if (slide[0] != null) slide[0].animateTo(v.getLeft());
                settings.put(key, values[idx]);
                if (change != null) change.onChange(values[idx]);
            });
            cells[i] = c;
            opts.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        paint.run();
        opts.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (cells.length == 0) return;
            int w = cells[0].getWidth();
            android.widget.FrameLayout.LayoutParams pl = (android.widget.FrameLayout.LayoutParams) pill.getLayoutParams();
            if (pl.width != w) {
                pl.width = w;
                pill.setLayoutParams(pl);
            }
            if (slide[0] == null) {
                slide[0] = new Fx.Spring(cells[sel[0]].getLeft(), 400f, 0.8f, pill::setTranslationX);
                slide[0].snapTo(cells[sel[0]].getLeft());
            }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 40));
        tp.topMargin = Ui.dp(a, 12);
        box.addView(track, tp);
        addToGroup(box, iconRes != 0);
        return t;
    }

    /** Trailing value preview: 14sp, onSurfaceVariant, at most 20% of the width. */
    private TextView preview(CharSequence text) {
        TextView v = new TextView(a, null, 0, R.style.YF_Caption);
        v.setText(text);
        v.setTextSize(14f);
        v.setTextColor(Palette.alpha(pal.onSurfaceVariant, 0.82f));
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setMaxWidth(Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.22f));
        return v;
    }

    static String labelFor(String value, String[] labels, String[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return labels[i];
        return value;
    }

    public TextView textRow(int iconRes, int titleRes, String key, String def, boolean multiline, int emptyRes, Change<String> change) {
        String cur = settings.str(key, def);
        final TextView[] sub = new TextView[1];
        sub[0] = row(iconRes, a.getString(titleRes), cur.isEmpty() ? a.getString(emptyRes) : cur, chevron(),
                v -> Ui.prompt(a, titleRes, settings.str(key, def), multiline, text -> {
                    settings.put(key, text.trim());
                    sub[0].setText(text.trim().isEmpty() ? a.getString(emptyRes) : text.trim());
                    sub[0].setVisibility(View.VISIBLE);
                    if (change != null) change.onChange(text.trim());
                }));
        return sub[0];
    }

    /**
     * Integer setting as an expandable slider row: collapsed it shows the value as a preview;
     * a tap opens a liquid slider with min / max labels (chevron turns, 320 ms). The value is
     * saved when the finger lifts. Returns the value label.
     */
    public TextView sliderRow(int iconRes, int titleRes, String key, int def, int min, int max, String unit, Change<Integer> change) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16));
        top.setBackground(new TonalDrawable(0, 0).pressable(pal.pressed()));
        top.setTag(R.id.fx_press, "row");
        if (iconRes != 0) top.addView(icon(iconRes));
        TextView t = new TextView(a, null, 0, R.style.YF_RowTitle);
        t.setText(titleRes);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(a, null, 0, R.style.YF_Caption);
        value.setTextColor(pal.primary);
        value.setTextSize(14f);
        top.addView(value);
        ImageView chev = chevron();
        LinearLayout.LayoutParams cl = (LinearLayout.LayoutParams) chev.getLayoutParams();
        cl.setMarginStart(Ui.dp(a, 8));
        top.addView(chev, cl);
        box.addView(top);
        int cur = Math.max(min, Math.min(max, settings.integer(key, def)));
        LiquidSlider sb = Widgets.newSlider(a, min, max, cur);
        value.setText(cur + unit);
        sb.addOnChangeListener((s, v, fromUser) -> value.setText(Math.round(v) + unit));
        sb.addOnSliderTouchListener(new LiquidSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(LiquidSlider s) {
            }

            @Override
            public void onStopTrackingTouch(LiquidSlider s) {
                int val = Widgets.value(s);
                settings.put(key, val);
                if (change != null) change.onChange(val);
            }
        });
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), Ui.dp(a, 12));
        panel.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout ends = new LinearLayout(a);
        ends.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 18), 0);
        TextView lo = new TextView(a, null, 0, R.style.YF_Caption);
        lo.setText(min + unit);
        TextView hi = new TextView(a, null, 0, R.style.YF_Caption);
        hi.setText(max + unit);
        hi.setGravity(Gravity.END);
        ends.addView(lo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ends.addView(hi, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(ends);
        panel.setVisibility(View.GONE);
        box.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.setClickable(true);
        top.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            boolean open = panel.getVisibility() != View.VISIBLE;
            Fx.chevron(chev, open);
            if (open) Fx.expand(panel);
            else Fx.collapse(panel);
        });
        addToGroup(box, iconRes != 0);
        return value;
    }

    public TextView link(int iconRes, int titleRes, int subtitleRes, Class<? extends Activity> target) {
        return row(iconRes, a.getString(titleRes), subtitleRes == 0 ? null : a.getString(subtitleRes), chevron(),
                v -> a.startActivity(new android.content.Intent(a, target)));
    }

    public Button button(int textRes, boolean primary, View.OnClickListener l) {
        group = null;
        Button b = new Button(a, null, 0, primary ? R.style.YF_Button : R.style.YF_Button_Ghost);
        b.setText(textRes);
        // Capsules: the one solid primary action, or a quiet surfaceContainerHighest one.
        b.setBackground(new TonalDrawable(primary ? pal.primary : pal.surfaceHighest, -1));
        b.setTextColor(primary ? pal.onPrimary : pal.onSurface);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56));
        p.topMargin = Ui.dp(a, 12);
        parent.addView(b, p);
        return b;
    }

    /** Status chip view to use as a trailing element. */
    public TextView chip(CharSequence text, int color) {
        TextView c = new TextView(a, null, 0, R.style.YF_Chip);
        c.setText(text);
        c.setBackground(new TonalDrawable(Palette.alpha(color, 0.14f), -1));
        c.setTextColor(color);
        return c;
    }
}
