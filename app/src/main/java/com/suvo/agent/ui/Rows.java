package com.suvo.agent.ui;

import android.app.Activity;
import android.graphics.Typeface;
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

/** Builds consistent glass rows (sections, switches, choices, links, sliders) into a container. */
public final class Rows {

    public interface Change<T> {
        void onChange(T value);
    }

    private final Activity a;
    private final LinearLayout parent;
    private final Settings settings;

    public Rows(Activity a, LinearLayout parent) {
        this.a = a;
        this.parent = parent;
        this.settings = Settings.get(a);
    }

    public LinearLayout parent() {
        return parent;
    }

    public TextView section(int titleRes) {
        TextView t = new TextView(a, null, 0, R.style.YF_Section);
        t.setText(titleRes);
        parent.addView(t, lp(Ui.dp(a, 20)));
        return t;
    }

    public TextView note(CharSequence text) {
        TextView t = new TextView(a, null, 0, R.style.YF_Caption);
        t.setText(text);
        t.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 2));
        parent.addView(t, lp(0));
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top == 0 ? Ui.dp(a, 8) : top;
        return p;
    }

    /** A clickable row with title, subtitle and optional trailing view. Returns the subtitle view. */
    public TextView row(int iconRes, CharSequence title, CharSequence subtitle, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(a, null, 0, R.style.YF_Row);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (iconRes != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(iconRes);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24));
            ip.setMarginEnd(Ui.dp(a, 14));
            row.addView(iv, ip);
        }
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(a, null, 0, R.style.YF_Body);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);
        TextView s = new TextView(a, null, 0, R.style.YF_Caption);
        s.setText(subtitle == null ? "" : subtitle);
        s.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        s.setMaxLines(4);
        s.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) row.addView(trailing);
        if (click != null) row.setOnClickListener(click);
        else row.setClickable(false);
        parent.addView(row, lp(0));
        return s;
    }

    public Switch switchRow(int iconRes, int titleRes, int subtitleRes, String key, boolean def, Change<Boolean> change) {
        Switch sw = new Switch(a);
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
        sub[0] = row(iconRes, a.getString(titleRes), labelFor(settings.str(key, def), labels, values), null, v -> {
            String cur = settings.str(key, def);
            int idx = 0;
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
            Ui.show(Ui.dialog(a).setTitle(titleRes).setSingleChoiceItems(labels, idx, (d, which) -> {
                settings.put(key, values[which]);
                sub[0].setText(labels[which]);
                sub[0].setVisibility(View.VISIBLE);
                d.dismiss();
                if (change != null) change.onChange(values[which]);
            }));
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
        sub[0] = row(iconRes, a.getString(titleRes), cur.isEmpty() ? a.getString(emptyRes) : cur, null,
                v -> Ui.prompt(a, titleRes, settings.str(key, def), multiline, text -> {
                    settings.put(key, text.trim());
                    sub[0].setText(text.trim().isEmpty() ? a.getString(emptyRes) : text.trim());
                    sub[0].setVisibility(View.VISIBLE);
                    if (change != null) change.onChange(text.trim());
                }));
        return sub[0];
    }

    /** Integer slider stored in settings. */
    public TextView sliderRow(int iconRes, int titleRes, String key, int def, int min, int max, String unit, Change<Integer> change) {
        final TextView[] sub = new TextView[1];
        sub[0] = row(iconRes, a.getString(titleRes), settings.integer(key, def) + unit, null, v -> {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), 0);
            TextView value = new TextView(a, null, 0, R.style.YF_Title);
            SeekBar sb = new SeekBar(a);
            sb.setTag("accent");
            sb.setMax(max - min);
            sb.setProgress(settings.integer(key, def) - min);
            value.setText((sb.getProgress() + min) + unit);
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
                }
            });
            box.addView(value);
            box.addView(sb);
            Ui.show(Ui.dialog(a).setTitle(titleRes).setView(box).setPositiveButton(android.R.string.ok, (d, w) -> {
                int val = sb.getProgress() + min;
                settings.put(key, val);
                sub[0].setText(val + unit);
                if (change != null) change.onChange(val);
            }).setNegativeButton(android.R.string.cancel, null));
        });
        return sub[0];
    }

    public TextView link(int iconRes, int titleRes, int subtitleRes, Class<? extends Activity> target) {
        ImageView chevron = new ImageView(a);
        chevron.setImageResource(R.drawable.ic_back);
        chevron.setRotation(180);
        chevron.setAlpha(0.5f);
        return row(iconRes, a.getString(titleRes), subtitleRes == 0 ? null : a.getString(subtitleRes), chevron,
                v -> a.startActivity(new android.content.Intent(a, target)));
    }

    public Button button(int textRes, boolean primary, View.OnClickListener l) {
        Button b = new Button(a, null, 0, primary ? R.style.YF_Button : R.style.YF_Button_Ghost);
        b.setText(textRes);
        if (primary) b.setTag("accent");
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = lp(Ui.dp(a, 10));
        parent.addView(b, p);
        return b;
    }

    /** Status chip view to use as a trailing element. */
    public TextView chip(CharSequence text, int color) {
        TextView c = new TextView(a, null, 0, R.style.YF_Chip);
        c.setText(text);
        c.setBackgroundTintList(android.content.res.ColorStateList.valueOf(com.suvo.agent.core.character.Appearance.alpha(color, 0.22f)));
        c.setTextColor(color);
        return c;
    }
}
