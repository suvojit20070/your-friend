package com.suvo.agent.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Chat text size: a live preview of everything an answer can contain (headings, many scripts,
 * lists, symbols, a table, a quote, inline code, a code card) above a floating card with the
 * "Markdown scale" slider (50–200 %). The chat uses it for answers and your own messages.
 */
public final class MarkdownScaleActivity extends BaseActivity {

    public static final String KEY = "md_scale";
    public static final int MIN = 50, MAX = 200, DEFAULT = 100;

    private static final String SAMPLE = "# hello\n"
            + "HELLO 你好 こんにちは ПРИВЕТ HOLA 안녕하세요 ALLÔ CIAO\n"
            + "### A B C あいう 日月山川\n"
            + "- ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"
            + "- abcdefghijklmnopqrstuvwxyz\n"
            + "- 0123456789 ! ? . , : ; — … · « » 「 」 『 』\n"
            + "- あいうえお かきくけこ さしすせそ\n"
            + "- 天地玄黄 宇宙洪荒 日月盈昃 辰宿列张\n"
            + "\n"
            + "▲ ● ■ ◆ ★ ♪ ⚡ ⌘\n"
            + "\n"
            + "| A | あ | 永 |\n"
            + "|---|---|---|\n"
            + "| B | か | 空 |\n"
            + "| 「 | さ | 海 |\n"
            + "\n"
            + "> ! ? ¿ ¡ : ; ~ ≈ ≠ ∞ °\n"
            + "\n"
            + "`Aa` **Aa** *Aa* あ 永 Ж Ω\n"
            + "\n"
            + "```kotlin\n"
            + "val hello = \"hello 你好 こんにちは ПРИВЕТ\"\n"
            + "repeat(8) { print(hello) }\n"
            + "```\n";

    /** The setting in percent (50–200). */
    public static int scalePercent(Context c) {
        return Math.max(MIN, Math.min(MAX, Settings.get(c).integer(KEY, DEFAULT)));
    }

    /** The setting as a factor (1.0 = normal). */
    public static float scale(Context c) {
        return scalePercent(c) / 100f;
    }

    private LinearLayout preview;
    private Markdown.Style style;
    private List<Markdown.Block> blocks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rows);
        setTopBar(R.string.md_scale);
        style = Markdown.styleFor(this);
        blocks = Markdown.blocks(SAMPLE, style);
        Palette pal = Palette.of(this);

        LinearLayout rows = findViewById(R.id.rows);
        preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 16));
        rows.addView(preview, pp);
        // The preview colours itself (like the chat).
        preview.setTag(R.id.theme_skip, Boolean.TRUE);

        // Card under the preview with the slider.
        View scroll = findViewById(R.id.rows_scroll);
        ViewGroup column = (ViewGroup) scroll.getParent();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new TonalDrawable(pal.surfaceHigh, Ui.dp(this, 36)));
        card.setElevation(Ui.dp(this, 2));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this, null, 0, R.style.YF_RowTitle);
        title.setText(R.string.md_scale);
        title.setTextColor(pal.onSurface);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this, null, 0, R.style.YF_RowTitle);
        value.setTextColor(pal.primary);
        head.addView(value);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), 0);
        card.addView(head, hp);

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView small = new TextView(this, null, 0, R.style.YF_Body);
        small.setText("A");
        small.setTextSize(14f);
        small.setTextColor(pal.onSurfaceVariant);
        line.addView(small);
        int cur = scalePercent(this);
        LiquidSlider slider = Widgets.newSlider(this, MIN, MAX, cur);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        line.addView(slider, sp);
        TextView big = new TextView(this, null, 0, R.style.YF_Body);
        big.setText("A");
        big.setTextSize(26f);
        big.setTextColor(pal.onSurface);
        line.addView(big);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 22));
        card.addView(line, lp);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 16));
        column.addView(card, cp);

        value.setText(cur + "%");
        render(cur);
        slider.addOnChangeListener((s, v, fromUser) -> {
            int pct = Math.round(v);
            value.setText(pct + "%");
            render(pct);
        });
        slider.addOnSliderTouchListener(new LiquidSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(LiquidSlider s) {
            }

            @Override
            public void onStopTrackingTouch(LiquidSlider s) {
                g.settings.put(KEY, Widgets.value(s));
            }
        });
        Ui.tintAccent(this, column);
    }

    /** The preview at {@code pct} % (same sizes as the chat uses). */
    private void render(int pct) {
        float k = pct / 100f;
        // Rebuilt (not reused) so every text view gets the new size.
        preview.removeAllViews();
        MarkdownViews.fill(preview, blocks, style, 17f * k, Ui.dp(this, 6.5f) * k, Palette.of(this).onSurface, true, null);
    }
}
