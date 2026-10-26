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
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    /** Single choice stored as a string setting. labels and values are parallel arrays. */
    public TextView choiceRow(int iconRes, int titleRes, String key, String def, String[] labels, String[] values, Change<String> change) {
        final TextView[] sub = new TextView[1];
        sub[0] = row(iconRes, a.getString(titleRes), labelFor(settings.str(key, def), labels, values), chevron(), v -> {
            String cur = settings.str(key, def);
            int idx = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
            Ui.show(Ui.dialog(a).setTitle(titleRes).setSingleChoiceItems(labels, idx, (d, which) -> {
                settings.put(key, values[which]);
                sub[0].setText(labels[which]);
                sub[0].setVisibility(View.VISIBLE);
                d.dismiss();
                if (change != null) change.onChange(values[which]);
            }).setNegativeButton(android.R.string.cancel, null));
        });
        return sub[0];
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
     * Integer slider stored in settings, shown inline: title and value on top, the slider below.
     * The value is saved when the finger lifts. Returns the value label.
     */
    public TextView sliderRow(int iconRes, int titleRes, String key, int def, int min, int max, String unit, Change<Integer> change) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 8));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (iconRes != 0) top.addView(icon(iconRes));
        TextView t = new TextView(a, null, 0, R.style.YF_RowTitle);
        t.setText(titleRes);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(a, null, 0, R.style.YF_Caption);
        value.setTextColor(Ui.accent(a));
        value.setTextSize(14f);
        top.addView(value);
        box.addView(top);
        int cur = Math.max(min, Math.min(max, settings.integer(key, def)));
        com.google.android.material.slider.Slider sb = Widgets.newSlider(a, min, max, cur);
        value.setText(cur + unit);
        sb.addOnChangeListener((s, v, fromUser) -> value.setText(Math.round(v) + unit));
        sb.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(com.google.android.material.slider.Slider s) {
            }

            @Override
            public void onStopTrackingTouch(com.google.android.material.slider.Slider s) {
                int val = Widgets.value(s);
                settings.put(key, val);
                if (change != null) change.onChange(val);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMarginStart(iconRes != 0 ? Ui.dp(a, 32) : 0);
        box.addView(sb, sp);
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
