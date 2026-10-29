package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.suvo.agent.R;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Builds the views for a finished answer from {@link Markdown#blocks}: text as selectable text,
 * code as a card that scrolls sideways with a copy button, tables as a real grid that scrolls
 * sideways when it is wider than the screen.
 */
final class MarkdownViews {

    private MarkdownViews() {
    }

    static void fill(LinearLayout box, List<Markdown.Block> blocks, Markdown.Style st, float textSp, float lineExtraPx,
                     int textColor) {
        Context ctx = box.getContext();
        box.removeAllViews();
        for (int i = 0; i < blocks.size(); i++) {
            Markdown.Block b = blocks.get(i);
            View v;
            switch (b.type) {
                case Markdown.Block.CODE:
                    v = code(ctx, b, st);
                    break;
                case Markdown.Block.TABLE:
                    v = table(ctx, b, st, textSp, textColor);
                    break;
                default:
                    v = text(ctx, b.text, st, textSp, lineExtraPx, textColor);
                    break;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = Ui.dp(ctx, b.type == Markdown.Block.TEXT ? 10 : 8);
            box.addView(v, lp);
        }
    }

    private static TextView text(Context ctx, CharSequence text, Markdown.Style st, float textSp, float lineExtraPx, int color) {
        TextView t = new TextView(ctx, null, 0, R.style.YF_Body);
        t.setTextSize(textSp);
        t.setLineSpacing(lineExtraPx, 1f);
        t.setTextColor(color);
        t.setLinkTextColor(st.link);
        t.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setMovementMethod(SelectLinkMovement.get());
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLongClickable(true);
        return t;
    }

    private static View code(Context ctx, Markdown.Block b, Markdown.Style st) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.code_bg);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 6), 0);
        TextView lang = new TextView(ctx, null, 0, R.style.YF_Caption);
        lang.setTextSize(12f);
        lang.setText(b.lang);
        head.addView(lang, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton copy = new ImageButton(ctx);
        copy.setBackground(null);
        copy.setImageResource(R.drawable.ic_copy);
        copy.setImageTintList(android.content.res.ColorStateList.valueOf(st.muted));
        copy.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        copy.setContentDescription(ctx.getString(R.string.copy));
        int cp = Ui.dp(ctx, 6);
        copy.setPadding(cp, cp, cp, cp);
        copy.setOnClickListener(x -> Ui.copy(ctx, b.code));
        LiquidPress.attach(copy, LiquidPress.ICON);
        head.addView(copy, new LinearLayout.LayoutParams(Ui.dp(ctx, 32), Ui.dp(ctx, 32)));
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        TextView code = new TextView(ctx, null, 0, R.style.YF_Mono);
        code.setText(b.code.isEmpty() ? " " : b.code);
        code.setTextIsSelectable(true);
        code.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 6), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        hs.addView(code, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (b.lang.isEmpty()) lang.setVisibility(View.INVISIBLE);
        return card;
    }

    private static View table(Context ctx, Markdown.Block b, Markdown.Style st, float textSp, int textColor) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        TableLayout tl = new TableLayout(ctx);
        int line = Palette.alpha(st.muted, 0.35f);
        tl.setBackgroundColor(line);
        tl.setPadding(1, 1, 0, 0); // the table background shows through the 1px gaps as grid lines
        int body = Palette.of(ctx).background;
        int maxCell = Ui.dp(ctx, 240);
        for (int r = 0; r < b.rows.size(); r++) {
            TableRow row = new TableRow(ctx);
            CharSequence[] cells = b.rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                TextView t = new TextView(ctx, null, 0, R.style.YF_Body);
                t.setTextSize(Math.max(12f, textSp - 3f));
                t.setLineSpacing(Ui.dp(ctx, 2), 1f);
                t.setTextColor(textColor);
                t.setLinkTextColor(st.link);
                t.setText(cells[c]);
                t.setMaxWidth(maxCell);
                t.setMinWidth(Ui.dp(ctx, 56));
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 7), Ui.dp(ctx, 10), Ui.dp(ctx, 7));
                int a = c < b.align.length ? b.align[c] : 0;
                t.setGravity(a == 1 ? Gravity.CENTER_HORIZONTAL : a == 2 ? Gravity.END : Gravity.START);
                if (r == 0) {
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    t.setBackgroundColor(st.codeBackground);
                } else {
                    t.setBackgroundColor(body);
                }
                TableRow.LayoutParams lp = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 1, 1);
                row.addView(t, lp);
            }
            tl.addView(row, new TableLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        hs.addView(tl, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }
}
