package com.suvo.agent.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.suvo.agent.R;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Builds the views for an answer from {@link Markdown#blocks}: text as selectable text (select it
 * right in the chat: long-press and drag the handles), code as a tonal card with a language chip,
 * syntax colours, a sideways scroll and a copy button, tables as a rounded grid with a tinted
 * header row and zebra rows that scrolls sideways when wider than the screen.
 *
 * {@link #fill} reuses the views already in the box: a block that did not change keeps its view
 * (so streaming does not rebuild everything each frame, and a selection in progress survives).
 */
final class MarkdownViews {

    private MarkdownViews() {
    }

    /** Lets the chat know while the user is selecting text (it pauses list updates meanwhile). */
    interface SelectionHook {
        void onSelecting(boolean active);
    }

    private static final int KIND_TEXT = 1, KIND_CODE = 2, KIND_TABLE = 3, KIND_QUOTE = 4;

    static void fill(LinearLayout box, List<Markdown.Block> blocks, Markdown.Style st, float textSp, float lineExtraPx,
                     int textColor) {
        fill(box, blocks, st, textSp, lineExtraPx, textColor, true, null);
    }

    static void fill(LinearLayout box, List<Markdown.Block> blocks, Markdown.Style st, float textSp, float lineExtraPx,
                     int textColor, boolean selectable, SelectionHook hook) {
        for (int i = 0; i < blocks.size(); i++) {
            Markdown.Block b = blocks.get(i);
            View old = i < box.getChildCount() ? box.getChildAt(i) : null;
            int kind = b.type == Markdown.Block.CODE ? KIND_CODE : b.type == Markdown.Block.TABLE ? KIND_TABLE
                    : b.type == Markdown.Block.QUOTE ? KIND_QUOTE : KIND_TEXT;
            if (old != null && Integer.valueOf(kind).equals(old.getTag(R.id.md_kind))) {
                String sig = old.getTag(R.id.md_block) == b ? (String) old.getTag(R.id.md_sig) : b.signature();
                boolean same = sig != null && sig.equals(old.getTag(R.id.md_sig));
                if (kind == KIND_TEXT) {
                    TextView t = (TextView) old;
                    if (!same) t.setText(b.text);
                    // Changed text (or a streamed answer that just finished) needs selection set up again.
                    if (t.isTextSelectable() != selectable || (!same && selectable)) makeSelectable(t, selectable, hook);
                } else if (kind == KIND_CODE) {
                    if (!same) updateCode(old, b, st);
                } else if (!same) {
                    old = replace(box, i, old, b, kind, st, textSp, lineExtraPx, textColor, selectable, hook);
                }
                old.setTag(R.id.md_block, b);
                old.setTag(R.id.md_sig, sig);
                spacing(old, i, b);
                continue;
            }
            replace(box, i, old, b, kind, st, textSp, lineExtraPx, textColor, selectable, hook);
        }
        while (box.getChildCount() > blocks.size()) box.removeViewAt(box.getChildCount() - 1);
    }

    private static View replace(LinearLayout box, int i, View old, Markdown.Block b, int kind, Markdown.Style st, float textSp,
                                float lineExtraPx, int textColor, boolean selectable, SelectionHook hook) {
        Context ctx = box.getContext();
        View v;
        switch (kind) {
            case KIND_CODE:
                v = code(ctx, b, st, hook);
                break;
            case KIND_TABLE:
                v = table(ctx, b, st, textSp, textColor);
                break;
            case KIND_QUOTE:
                v = quote(ctx, b, st, textSp, lineExtraPx, selectable, hook);
                break;
            default:
                v = text(ctx, b.text, st, textSp, lineExtraPx, textColor, selectable, hook);
                break;
        }
        v.setTag(R.id.md_kind, kind);
        v.setTag(R.id.md_block, b);
        v.setTag(R.id.md_sig, b.signature());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = i == 0 ? 0 : Ui.dp(ctx, b.type == Markdown.Block.TEXT ? 12 : 10);
        if (old != null) box.removeViewAt(i);
        box.addView(v, i, lp);
        return v;
    }

    private static void spacing(View v, int i, Markdown.Block b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (!(p instanceof LinearLayout.LayoutParams)) return;
        int want = i == 0 ? 0 : Ui.dp(v.getContext(), b.type == Markdown.Block.TEXT ? 12 : 10);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) p;
        if (lp.topMargin != want) {
            lp.topMargin = want;
            v.setLayoutParams(lp);
        }
    }

    /**
     * Text selection right in the chat. A TextView whose text changed while it was selectable can
     * refuse to start a selection ("does not support text selection"); turning it off and on fixes it.
     */
    static void makeSelectable(TextView t, boolean selectable, SelectionHook hook) {
        Ui.selectionColors(t);
        t.setTextIsSelectable(false);
        if (selectable) {
            t.setTextIsSelectable(true);
            t.setMovementMethod(SelectLinkMovement.get());
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setLongClickable(true);
            watchSelection(t, hook);
        } else {
            t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            t.setFocusable(false);
            t.setLongClickable(false);
        }
    }

    /** Wraps the text's selection menu so the chat knows a selection is active. */
    static void watchSelection(TextView t, SelectionHook hook) {
        if (hook == null) return;
        t.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, android.view.Menu menu) {
                hook.onSelecting(true);
                return true; // keep the system items (Copy, Select all, Share…)
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, android.view.MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                hook.onSelecting(false);
            }
        });
    }

    private static TextView text(Context ctx, CharSequence text, Markdown.Style st, float textSp, float lineExtraPx, int color,
                                 boolean selectable, SelectionHook hook) {
        TextView t = new TextView(ctx, null, 0, R.style.YF_Body);
        t.setTextSize(textSp);
        t.setLineSpacing(lineExtraPx, 1f);
        t.setTextColor(color);
        t.setLinkTextColor(st.link);
        t.setHighlightColor(Palette.alpha(st.link, 0.28f));
        t.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        t.setText(text);
        Ui.selectionColors(t);
        if (selectable) {
            t.setTextIsSelectable(true);
            t.setMovementMethod(SelectLinkMovement.get());
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setLongClickable(true);
            watchSelection(t, hook);
        } else {
            t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        return t;
    }

    // ---- Code card -------------------------------------------------------------------------------

    private static View code(Context ctx, Markdown.Block b, Markdown.Style st, SelectionHook hook) {
        Palette pal = Palette.of(ctx);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new TonalDrawable(st.codeBackground, Ui.dp(ctx, 20)));
        card.setClipToOutline(true);

        // Header: language chip on the left, copy pill on the right.
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Palette.alpha(pal.onSurface, pal.dark ? 0.05f : 0.035f));

        TextView lang = new TextView(ctx, null, 0, R.style.YF_Caption);
        lang.setTextSize(12f);
        lang.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lang.setTextColor(st.marker);
        lang.setText(b.lang.isEmpty() ? "code" : b.lang);
        lang.setBackground(new TonalDrawable(Palette.alpha(st.marker, 0.14f), -1));
        lang.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 3), Ui.dp(ctx, 10), Ui.dp(ctx, 3));
        lang.setTag("md_lang");
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lgp.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8));
        head.addView(lang, lgp);

        View fill = new View(ctx);
        head.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout copy = new LinearLayout(ctx);
        copy.setOrientation(LinearLayout.HORIZONTAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setBackground(new TonalDrawable(0, -1).pressable(pal.pressed()));
        copy.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4));
        copy.setClickable(true);
        copy.setContentDescription(ctx.getString(R.string.copy));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_copy);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(st.muted));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16));
        ip.setMarginEnd(Ui.dp(ctx, 6));
        copy.addView(icon, ip);
        TextView label = new TextView(ctx, null, 0, R.style.YF_Caption);
        label.setTextSize(12.5f);
        label.setTextColor(st.muted);
        label.setText(R.string.copy);
        copy.addView(label);
        copy.setOnClickListener(x -> {
            Object cur = card.getTag(R.id.md_block);
            String text = cur instanceof Markdown.Block ? ((Markdown.Block) cur).code : b.code;
            Ui.copy(ctx, text);
            icon.setImageResource(R.drawable.ic_check);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(st.marker));
            label.setText(R.string.copied);
            label.setTextColor(st.marker);
            x.postDelayed(() -> {
                icon.setImageResource(R.drawable.ic_copy);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(st.muted));
                label.setText(R.string.copy);
                label.setTextColor(st.muted);
            }, 1600);
        });
        LiquidPress.attach(copy, LiquidPress.TEXT);
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 32));
        cpp.setMargins(0, Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4));
        head.addView(copy, cpp);
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setFillViewport(true);
        TextView code = new TextView(ctx, null, 0, R.style.YF_Mono);
        code.setTag("md_code");
        code.setTextSize(13.5f);
        code.setLineSpacing(Ui.dp(ctx, 4), 1f);
        code.setTextColor(st.codeText != 0 ? st.codeText : pal.onSurface);
        code.setHighlightColor(Palette.alpha(st.marker, 0.28f));
        code.setText(codeText(b));
        Ui.selectionColors(code);
        code.setTextIsSelectable(true);
        watchSelection(code, hook);
        HorizontalScrollView.LayoutParams clp = new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 14));
        hs.addView(code, clp);
        card.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private static CharSequence codeText(Markdown.Block b) {
        if (b.code.isEmpty()) return " ";
        return b.styledCode != null ? b.styledCode : b.code;
    }

    private static void updateCode(View card, Markdown.Block b, Markdown.Style st) {
        TextView lang = card.findViewWithTag("md_lang");
        if (lang != null) lang.setText(b.lang.isEmpty() ? "code" : b.lang);
        TextView code = card.findViewWithTag("md_code");
        if (code != null) {
            CharSequence next = codeText(b);
            if (!TextUtils.equals(code.getText().toString(), next.toString()) || b.styledCode != null) {
                code.setText(next);
                code.setTextIsSelectable(false);
                code.setTextIsSelectable(true);
            }
        }
    }

    // ---- Quote card ------------------------------------------------------------------------------

    /**
     * A quote as a soft tinted card with a rounded accent stripe; GitHub-style alerts
     * ("> [!NOTE]", TIP, IMPORTANT, WARNING, CAUTION) get a titled header in their own colour.
     */
    private static View quote(Context ctx, Markdown.Block b, Markdown.Style st, float textSp, float lineExtraPx,
                              boolean selectable, SelectionHook hook) {
        Palette pal = Palette.of(ctx);
        int color;
        int icon = 0;
        String title = "";
        switch (b.lang) {
            case "TIP":
                color = pal.tertiary;
                icon = R.drawable.ic_star;
                title = "Tip";
                break;
            case "IMPORTANT":
                color = pal.primary;
                icon = R.drawable.ic_shield;
                title = "Important";
                break;
            case "WARNING":
                color = ctx.getColor(R.color.warning);
                icon = R.drawable.ic_info;
                title = "Warning";
                break;
            case "CAUTION":
            case "DANGER":
                color = pal.error;
                icon = R.drawable.ic_shield;
                title = b.lang.equals("DANGER") ? "Danger" : "Caution";
                break;
            case "NOTE":
            case "INFO":
                color = pal.primary;
                icon = R.drawable.ic_info;
                title = b.lang.equals("INFO") ? "Info" : "Note";
                break;
            default:
                color = pal.primary;
        }
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(new TonalDrawable(Palette.alpha(color, pal.dark ? 0.13f : 0.08f), Ui.dp(ctx, 18)));

        View stripe = new View(ctx);
        stripe.setBackground(new TonalDrawable(color, -1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(ctx, 4), ViewGroup.LayoutParams.MATCH_PARENT);
        sp.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12));
        card.addView(stripe, sp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        if (!title.isEmpty()) {
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(color));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(ctx, 17), Ui.dp(ctx, 17));
            ip.setMarginEnd(Ui.dp(ctx, 8));
            head.addView(iv, ip);
            TextView t = new TextView(ctx, null, 0, R.style.YF_Body);
            t.setText(title);
            t.setTextSize(14.5f);
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
            t.setTextColor(color);
            head.addView(t);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.bottomMargin = Ui.dp(ctx, 6);
            col.addView(head, hp);
        }
        TextView body = text(ctx, b.text, st, Math.max(14f, textSp - 1f), lineExtraPx, Palette.alpha(pal.onSurface, 0.88f), selectable, hook);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMargins(Ui.dp(ctx, 12), Ui.dp(ctx, 11), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        card.addView(col, cp);
        return card;
    }

    // ---- Table -----------------------------------------------------------------------------------

    private static View table(Context ctx, Markdown.Block b, Markdown.Style st, float textSp, int textColor) {
        Palette pal = Palette.of(ctx);
        FrameLayout card = new FrameLayout(ctx);
        int line = androidx.core.graphics.ColorUtils.compositeColors(Palette.alpha(pal.outlineVariant, 0.55f), pal.surfaceLow);
        // The card colour shows through 1px gaps between cells as the grid lines.
        card.setBackground(new TonalDrawable(line, Ui.dp(ctx, 16)));
        card.setClipToOutline(true);
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setFillViewport(true);
        TableLayout tl = new TableLayout(ctx);
        tl.setStretchAllColumns(true); // fills the card when narrower than it
        int body = pal.surfaceLow;
        int zebra = androidx.core.graphics.ColorUtils.compositeColors(Palette.alpha(pal.onSurface, pal.dark ? 0.04f : 0.025f), body);
        int header = androidx.core.graphics.ColorUtils.compositeColors(Palette.alpha(pal.primary, pal.dark ? 0.20f : 0.12f), body);
        int maxCell = Ui.dp(ctx, 240);
        int cols = b.rows.isEmpty() ? 0 : b.rows.get(0).length;
        for (int r = 0; r < b.rows.size(); r++) {
            TableRow row = new TableRow(ctx);
            CharSequence[] cells = b.rows.get(r);
            for (int c = 0; c < cells.length; c++) {
                TextView t = new TextView(ctx, null, 0, R.style.YF_Body);
                t.setTextSize(Math.max(12f, textSp - 2.5f));
                t.setLineSpacing(Ui.dp(ctx, 2), 1f);
                t.setTextColor(textColor);
                t.setLinkTextColor(st.link);
                t.setText(cells[c]);
                t.setMaxWidth(maxCell);
                t.setMinWidth(Ui.dp(ctx, 64));
                t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 9), Ui.dp(ctx, 12), Ui.dp(ctx, 9));
                int a = c < b.align.length ? b.align[c] : 0;
                t.setGravity((a == 1 ? Gravity.CENTER_HORIZONTAL : a == 2 ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
                if (r == 0) {
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    t.setTextColor(st.marker);
                    t.setBackgroundColor(header);
                } else {
                    t.setBackgroundColor(r % 2 == 0 ? zebra : body);
                }
                TableRow.LayoutParams lp = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                // 1px grid line on the right and bottom of every cell except the outer edges.
                lp.setMargins(0, 0, c < cols - 1 ? 1 : 0, r < b.rows.size() - 1 ? 1 : 0);
                row.addView(t, lp);
            }
            tl.addView(row, new TableLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        hs.addView(tl, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(hs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // A table narrower than the screen hugs its content instead of stretching.
        return card;
    }
}
