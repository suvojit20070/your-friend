package com.suvo.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.data.UsageStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configured OpenAI-compatible providers, and how each one has been doing: requests, failures by
 * type, tokens in / out (counted by the app itself) and speed.
 */
public final class ProvidersActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.section_providers);
        topAction(R.drawable.ic_add, R.string.provider_add, v -> startActivity(new Intent(this, ProviderEditorActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        LinearLayout box = findViewById(R.id.rows);
        box.removeAllViews();
        Rows r = new Rows(this, box);
        r.note(getString(R.string.providers_intro));
        List<ProviderConfig> list = g.providers.list();
        UsageStore usage = UsageStore.get(this);
        if (list.isEmpty()) {
            r.note(getString(R.string.providers_empty));
            r.button(R.string.provider_add, true, v -> startActivity(new Intent(this, ProviderEditorActivity.class)));
        }
        Set<Long> known = new HashSet<>();
        for (ProviderConfig p : list) {
            known.add(p.id);
            ImageView star = new ImageView(this);
            star.setImageResource(p.isDefault ? R.drawable.ic_star_filled : R.drawable.ic_star);
            star.setContentDescription(getString(R.string.provider_default));
            star.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            star.setOnClickListener(v -> {
                g.providers.setDefault(p.id);
                build();
            });
            UsageStore.Stats st = usage.get(p.id);
            String sub = p.model + "\n" + p.baseUrl + (p.enabled ? "" : "\n" + getString(R.string.provider_disabled))
                    + (p.apiKey.isEmpty() ? "\n" + getString(R.string.provider_no_key) : "")
                    + (st == null ? "" : "\n" + line(st));
            r.row(R.drawable.ic_provider, p.name, sub, star,
                    v -> startActivity(new Intent(this, ProviderEditorActivity.class).putExtra(ProviderEditorActivity.EXTRA_ID, p.id)));
        }

        // Usage per provider; tap one for the details (errors by type, speed, reset).
        r.section(R.string.usage_section);
        r.note(getString(R.string.usage_intro));
        List<UsageStore.Stats> all = usage.all();
        if (all.isEmpty()) r.note(getString(R.string.usage_none));
        for (UsageStore.Stats st : all) {
            String name = st.name.isEmpty() ? "#" + st.providerId : st.name;
            if (!known.contains(st.providerId)) name = getString(R.string.usage_deleted_provider, name);
            TextView chip = r.chip(st.successPercent() + "%", st.failed == 0 ? getColor(R.color.success)
                    : st.successPercent() >= 80 ? getColor(R.color.warning) : Palette.of(this).error);
            final String title = name;
            r.row(R.drawable.ic_history, name, line(st) + "\n" + st.model, chip, v -> details(st.providerId, title));
        }
        if (!all.isEmpty()) {
            r.button(R.string.usage_reset_all, false, v -> Ui.confirm(this, R.string.usage_reset_all,
                    getString(R.string.usage_reset_confirm, getString(R.string.section_providers)), () -> {
                        usage.resetAll();
                        build();
                    }));
        }
        Ui.tintAccent(this, box);
    }

    /** One line: tokens in / out, success rate and speed. */
    private String line(UsageStore.Stats st) {
        double tps = st.tokensPerSecond();
        String speed = tps > 0 ? String.format(Locale.getDefault(), "%.1f tok/s", tps) : st.requests + " req";
        return getString(R.string.usage_line, compact(st.inTokens), compact(st.outTokens), st.successPercent(), speed);
    }

    static String compact(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.getDefault(), "%.1fk", n / 1000.0);
        if (n < 1_000_000_000) return String.format(Locale.getDefault(), "%.2fM", n / 1_000_000.0);
        return String.format(Locale.getDefault(), "%.2fB", n / 1_000_000_000.0);
    }

    private static String duration(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format(Locale.getDefault(), "%.1f s", ms / 1000.0);
        return String.format(Locale.getDefault(), "%d min %d s", ms / 60_000, (ms / 1000) % 60);
    }

    /** Everything about one provider's usage in a glass sheet. */
    private void details(long id, String title) {
        UsageStore usage = UsageStore.get(this);
        UsageStore.Stats st = usage.get(id);
        if (st == null) return;
        Palette pal = Palette.of(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        stat(col, pal, getString(R.string.usage_requests),
                getString(R.string.usage_requests_value, st.requests, st.ok, st.failed, st.cancelled));
        stat(col, pal, getString(R.string.usage_tokens),
                getString(R.string.usage_tokens_value, compact(st.inTokens), compact(st.outTokens), compact(st.inTokens + st.outTokens)));
        stat(col, pal, getString(R.string.usage_provider_tokens), st.providerIn < 0 && st.providerOut < 0
                ? getString(R.string.usage_provider_none)
                : getString(R.string.usage_tokens_value, compact(Math.max(0, st.providerIn)), compact(Math.max(0, st.providerOut)),
                compact(Math.max(0, st.providerIn) + Math.max(0, st.providerOut))));
        double tps = st.tokensPerSecond();
        stat(col, pal, getString(R.string.usage_speed), getString(R.string.usage_speed_value,
                tps > 0 ? String.format(Locale.getDefault(), "%.1f", tps) : "—", duration(st.avgFirstTokenMs()), duration(st.avgRequestMs())));
        stat(col, pal, getString(R.string.usage_last_used), st.lastUsedAt == 0 ? "—" : Ui.ago(st.lastUsedAt).toString());

        // Errors: the same kind counted together, most frequent first, with the last message of each.
        TextView head = new TextView(this, null, 0, R.style.YF_Section);
        head.setText(R.string.usage_errors);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 6));
        col.addView(head, hp);
        List<Map.Entry<String, long[]>> errs = new ArrayList<>(st.errors.entrySet());
        errs.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        if (errs.isEmpty()) {
            stat(col, pal, getString(R.string.usage_no_errors), "");
        }
        for (Map.Entry<String, long[]> e : errs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setBackground(new TonalDrawable(Palette.alpha(pal.error, pal.dark ? 0.12f : 0.07f), Ui.dp(this, 18)));
            TextView count = new TextView(this, null, 0, R.style.YF_Title);
            count.setText("×" + e.getValue()[0]);
            count.setTextSize(16f);
            count.setTextColor(pal.error);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            row.addView(count, cp);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView kind = new TextView(this, null, 0, R.style.YF_Body);
            kind.setText(e.getKey());
            kind.setTextSize(15f);
            kind.setTextColor(pal.onSurface);
            texts.addView(kind);
            String msg = st.errorSamples.getOrDefault(e.getKey(), "");
            TextView m = new TextView(this, null, 0, R.style.YF_Caption);
            m.setText((msg.isEmpty() ? "" : msg + "\n") + Ui.ago(e.getValue()[1]));
            m.setTextColor(pal.onSurfaceVariant);
            m.setTextIsSelectable(true);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = Ui.dp(this, 2);
            texts.addView(m, mp);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(0, Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            row.addView(texts, tp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            col.addView(row, rp);
        }

        final Overlay[] sheet = new Overlay[1];
        android.widget.Button reset = new android.widget.Button(this, null, 0, R.style.YF_Button_Ghost);
        reset.setText(R.string.usage_reset);
        reset.setBackground(new TonalDrawable(pal.surfaceHighest, -1));
        reset.setTextColor(pal.onSurface);
        reset.setOnClickListener(v -> Ui.confirm(this, R.string.usage_reset, getString(R.string.usage_reset_confirm, title), () -> {
            usage.reset(id);
            if (sheet[0] != null) sheet[0].dismiss();
            build();
        }));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        bp.setMargins(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        col.addView(reset, bp);
        sheet[0] = Fx.sheet(this, title, col);
    }

    /** A label over its value, on a tonal tile. */
    private void stat(LinearLayout col, Palette pal, String label, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(new TonalDrawable(pal.surfaceHigh, Ui.dp(this, 18)));
        TextView l = new TextView(this, null, 0, R.style.YF_Caption);
        l.setText(label);
        l.setTextColor(pal.onSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), value.isEmpty() ? Ui.dp(this, 12) : 0);
        tile.addView(l, lp);
        if (!value.isEmpty()) {
            TextView v = new TextView(this, null, 0, R.style.YF_Body);
            v.setText(value);
            v.setTextSize(15.5f);
            v.setTextColor(pal.onSurface);
            v.setTextIsSelectable(true);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.setMargins(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 12));
            tile.addView(v, vp);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        col.addView(tile, tp);
    }
}
