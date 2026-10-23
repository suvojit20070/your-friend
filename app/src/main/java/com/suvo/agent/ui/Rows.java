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
import android.widget.SeekBar;
import android.widget.Switch;
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
    private LinearLayout group;

    public Rows(Activity a, LinearLayout parent) {
        this.a = a;
        this.parent = parent;
        this.settings = Settings.get(a);
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
            group.setBackgroundResource(R.drawable.group_card);
            group.setClipToOutline(true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = Ui.dp(a, 10);
            parent.addView(group, p);
        }
        return group;
    }

    private void addToGroup(View row, boolean insetDivider) {
        LinearLayout g = group();
        if (g.getChildCount() > 0) {
            View div = new View(a);
            div.setBackgroundColor(a.getColor(R.color.divider));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 1)));
            dp.setMarginStart(Ui.dp(a, insetDivider ? 56 : 16));
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
        p.topMargin = Ui.dp(a, 22);
        p.setMarginStart(Ui.dp(a, 8));
        parent.addView(t, p);
        return t;
    }

    public TextView note(CharSequence text) {
        group = null;
        TextView t = new TextView(a, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 2));
        t.setLineSpacing(Ui.dp(a, 2), 1f);
        parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private ImageView icon(int iconRes) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.text_secondary)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22));
        ip.setMarginEnd(Ui.dp(a, 18));
        iv.setLayoutParams(ip);
        return iv;
    }

    private LinearLayout baseRow(int iconRes) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(a.getResources().getDimensionPixelSize(R.dimen.row_min_height));
        row.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        row.setBackgroundResource(R.drawable.row_bg);
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
        s.setPadding(0, Ui.dp(a, 2), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.setMarginStart(Ui.dp(a, 10));
            row.addView(trailing, tp);
        }
        if (click != null) row.setOnClickListener(click);
        else {
            row.setClickable(false);
            row.setBackground(null);
        }
        addToGroup(row, iconRes != 0);
        return s;
    }

    private ImageView chevron() {
        ImageView c = new ImageView(a);
        c.setImageResource(R.drawable.ic_back);
        c.setRotation(180);
        c.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.text_secondary)));
        c.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));
        return c;
    }

    public Switch switchRow(int iconRes, int titleRes, int subtitleRes, String key, boolean def, Change<Boolean> change) {
        Switch sw = Widgets.styleSwitch(new Switch(a));
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
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 8));
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
        SeekBar sb = Widgets.styleSeekBar(new SeekBar(a));
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, settings.integer(key, def)));
        sb.setProgress(cur - min);
        value.setText(cur + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                value.setText((p + min) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                int val = s.getProgress() + min;
                settings.put(key, val);
                if (change != null) change.onChange(val);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 44));
        sp.setMarginStart(iconRes != 0 ? Ui.dp(a, 34) : 0);
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
        if (primary) {
            b.setTag("accent");
            b.setBackgroundTintList(ColorStateList.valueOf(Ui.accent(a)));
        }
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 54));
        p.topMargin = Ui.dp(a, 12);
        parent.addView(b, p);
        return b;
    }

    /** Status chip view to use as a trailing element. */
    public TextView chip(CharSequence text, int color) {
        TextView c = new TextView(a, null, 0, R.style.YF_Chip);
        c.setText(text);
        c.setBackgroundResource(R.drawable.chip);
        c.setBackgroundTintList(ColorStateList.valueOf(com.suvo.agent.core.character.Appearance.alpha(color, 0.18f)));
        c.setTextColor(color);
        return c;
    }
}
