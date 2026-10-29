package com.suvo.agent.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Markdown renderer for chat: headings, bold, italic, strikethrough, inline code, fenced code
 * blocks, bullet / numbered / task lists (nested by indent, wrapped lines stay aligned), block
 * quotes, links, horizontal rules and tables. Unknown syntax is left as plain text, so nothing the
 * model writes is ever lost.
 *
 * Two ways to use it:
 * <ul>
 *   <li>{@link #render} gives one {@link CharSequence} (code blocks and tables are monospace text).
 *       Cheap; used while an answer is still streaming and in the "select text" sheet.</li>
 *   <li>{@link #blocks} splits the answer into text, code and table blocks so the chat can show
 *       code with its own horizontal scroll and a copy button, and tables as real grids.</li>
 * </ul>
 */
public final class Markdown {

    /** Colors used for code and quotes; chosen by the caller for light/dark theme. */
    public static final class Style {
        public int codeBackground = 0x22888888;
        public int codeText = 0;
        public int quoteStripe = 0xFF7C5CFF;
        public int link = 0;
        public int muted = 0x99888888;
        public float density = 2f;
    }

    /** One part of an answer. */
    public static final class Block {
        public static final int TEXT = 0, CODE = 1, TABLE = 2;
        public int type;
        /** TEXT: the styled text. */
        public CharSequence text = "";
        /** CODE: language label (may be empty) and the code itself. */
        public String lang = "";
        public String code = "";
        /** TABLE: rows of styled cells, the first row is the header; align per column: 0 left, 1 centre, 2 right. */
        public List<CharSequence[]> rows = new ArrayList<>();
        public int[] align = new int[0];
    }

    // Kinds of block, used to decide the spacing between them.
    private static final int NONE = 0, PARA = 1, HEAD = 2, LIST = 3, QUOTE = 4, RULE = 5, CODE = 6, TABLE = 7;

    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(\\[( |x|X)\\]\\s+)?(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern QUOTE_RE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern RULE_RE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    private Markdown() {
    }

    /** Theme-aware style for chat bubbles. */
    public static Style styleFor(android.content.Context ctx) {
        Style st = new Style();
        st.codeBackground = ctx.getColor(com.suvo.agent.R.color.code_bg);
        st.codeText = ctx.getColor(com.suvo.agent.R.color.code_text);
        st.quoteStripe = Ui.accent(ctx);
        st.link = Ui.accent(ctx);
        st.muted = ctx.getColor(com.suvo.agent.R.color.text_secondary);
        st.density = ctx.getResources().getDisplayMetrics().density;
        return st;
    }

    /** Everything as one styled text (code blocks and tables as monospace text). */
    public static CharSequence render(String src, Style st) {
        return build(src, st, null);
    }

    /** The answer split into text, code and table blocks. */
    public static List<Block> blocks(String src, Style st) {
        List<Block> blocks = new ArrayList<>();
        build(src, st, blocks);
        return blocks;
    }

    // ---- Block parser ----------------------------------------------------------------------------

    private static CharSequence build(String src, Style st, List<Block> blocks) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null || src.isEmpty()) return out;
        String[] lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        boolean blank = true;
        int prev = NONE;
        // The last list item, so an indented continuation line stays inside it.
        ListSpan lastItem = null;
        int lastItemStart = 0;
        while (i < lines.length) {
            String line = lines[i];

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                String lang = fence.group(2);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(marker)) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                i++; // closing fence (or end of text while streaming)
                if (blocks != null) {
                    flush(out, blocks);
                    out = new SpannableStringBuilder();
                    Block b = new Block();
                    b.type = Block.CODE;
                    b.lang = lang;
                    b.code = code.toString();
                    blocks.add(b);
                } else {
                    gap(out, prev, CODE, blank);
                    int start = out.length();
                    if (!lang.isEmpty()) {
                        out.append(lang).append('\n');
                        out.setSpan(new ForegroundColorSpan(st.muted), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.8f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    int codeStart = out.length();
                    out.append(code.length() == 0 ? " " : code.toString());
                    out.setSpan(new TypefaceSpan("monospace"), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.9f), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (st.codeText != 0) out.setSpan(new ForegroundColorSpan(st.codeText), codeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(st.codeBackground), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                prev = CODE;
                blank = false;
                lastItem = null;
                continue;
            }

            if (line.trim().isEmpty()) {
                blank = true;
                i++;
                continue;
            }

            // Table: header row, separator row, body rows.
            if (line.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches() && lines[i + 1].contains("-")
                    && (lines[i + 1].contains("|") || cells(line).length == 1)) {
                String[] sepCells = cells(lines[i + 1]);
                List<String[]> rows = new ArrayList<>();
                rows.add(cells(line));
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    rows.add(cells(lines[i]));
                    i++;
                }
                if (blocks != null) {
                    flush(out, blocks);
                    out = new SpannableStringBuilder();
                    blocks.add(tableBlock(rows, sepCells, st));
                } else {
                    gap(out, prev, TABLE, blank);
                    appendTable(out, rows);
                }
                prev = TABLE;
                blank = false;
                lastItem = null;
                continue;
            }

            Matcher h = HEADING.matcher(line);
            Matcher b = BULLET.matcher(line);
            Matcher n = NUMBERED.matcher(line);
            Matcher q = QUOTE_RE.matcher(line);
            if (RULE_RE.matcher(line).matches()) {
                gap(out, prev, RULE, blank);
                int s = out.length();
                out.append("────────────");
                out.setSpan(new ForegroundColorSpan(st.muted), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = RULE;
                lastItem = null;
            } else if (h.matches()) {
                gap(out, prev, HEAD, blank);
                int level = h.group(1).length();
                int s = out.length();
                inline(out, h.group(2), st);
                float size = level == 1 ? 1.35f : level == 2 ? 1.22f : level == 3 ? 1.1f : 1.0f;
                out.setSpan(new RelativeSizeSpan(size), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = HEAD;
                lastItem = null;
            } else if (b.matches() || n.matches()) {
                boolean bullet = b.matches(); // (a line can only be one of the two)
                String ws = bullet ? b.group(1) : n.group(1);
                int depth = indentDepth(ws);
                gap(out, prev, LIST, blank);
                int s = out.length();
                String marker;
                String body;
                if (bullet) {
                    String box = b.group(3);
                    marker = box == null ? (depth % 2 == 0 ? "•" : "◦") : (box.trim().isEmpty() ? "☐" : "☑");
                    body = b.group(4);
                } else {
                    marker = n.group(2) + ".";
                    body = n.group(3);
                }
                inline(out, body, st);
                if (out.length() == s) out.append(' ');
                float indent = marker.length() > 3 ? 34 : bullet ? 22 : 28;
                lastItem = new ListSpan(marker, st.density, depth * 20 + indent, depth * 20, st.muted);
                lastItemStart = s;
                out.setSpan(lastItem, s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = LIST;
            } else if (QUOTE_RE.matcher(line).matches() && q.matches()) {
                gap(out, prev, QUOTE, blank);
                int s = out.length();
                inline(out, q.group(1), st);
                if (out.length() == s) out.append(' ');
                out.setSpan(new QuoteBar(st.quoteStripe, st.density), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(st.muted), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = QUOTE;
                lastItem = null;
            } else if (prev == LIST && lastItem != null && !blank && (line.startsWith("  ") || line.startsWith("\t"))) {
                // An indented line right under a list item belongs to that item.
                out.append('\n');
                inline(out, line.trim(), st);
                out.setSpan(lastItem, lastItemStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // Paragraph text: consecutive lines join with a line break (chat text is line-oriented).
                gap(out, prev, PARA, blank);
                inline(out, line.trim(), st);
                prev = PARA;
                lastItem = null;
            }
            blank = false;
            i++;
        }
        if (blocks != null) flush(out, blocks);
        return out;
    }

    private static void flush(SpannableStringBuilder out, List<Block> blocks) {
        if (out.length() == 0) return;
        Block b = new Block();
        b.type = Block.TEXT;
        b.text = new SpannedString(out); // immutable: cached blocks are shared by several views
        blocks.add(b);
    }

    /**
     * Spacing between two blocks: items of the same list or quote and lines of a paragraph stay
     * together; everything else gets a short blank line (half height), a heading hugs what follows.
     */
    private static void gap(SpannableStringBuilder out, int prev, int kind, boolean blank) {
        if (out.length() == 0 || prev == NONE) return;
        boolean together = (kind == prev && (kind == LIST || kind == QUOTE || kind == PARA))
                || (prev == HEAD && !blank)
                || (prev == PARA && kind == LIST && !blank);
        out.append('\n');
        if (together && !blank) return;
        int s = out.length();
        out.append('\n');
        // The extra line is half height, so paragraphs are separated without big holes.
        out.setSpan(new RelativeSizeSpan(0.55f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static int indentDepth(String ws) {
        int spaces = 0;
        for (char c : ws.toCharArray()) spaces += c == '\t' ? 4 : 1;
        return Math.min(4, spaces / 2);
    }

    // ---- Tables ----------------------------------------------------------------------------------

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.substring(0, r.length() - 1);
        r = r.replace("\\|", "\u0001");
        String[] parts = r.split("\\|", -1);
        for (int k = 0; k < parts.length; k++) parts[k] = parts[k].replace('\u0001', '|').trim();
        return parts;
    }

    private static Block tableBlock(List<String[]> rows, String[] sep, Style st) {
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        Block b = new Block();
        b.type = Block.TABLE;
        b.align = new int[cols];
        for (int c = 0; c < cols && c < sep.length; c++) {
            String s = sep[c].trim();
            boolean l = s.startsWith(":"), r = s.endsWith(":");
            b.align[c] = l && r ? 1 : r ? 2 : 0;
        }
        for (String[] r : rows) {
            CharSequence[] cs = new CharSequence[cols];
            for (int c = 0; c < cols; c++) {
                SpannableStringBuilder cell = new SpannableStringBuilder();
                inline(cell, c < r.length ? r[c].replace("<br>", "\n").replace("<br/>", "\n") : "", st);
                cs[c] = new SpannedString(cell);
            }
            b.rows.add(cs);
        }
        return b;
    }

    /** Monospace fallback: columns padded to an aligned grid. */
    private static void appendTable(SpannableStringBuilder out, List<String[]> rows) {
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        int[] w = new int[cols];
        String[][] plain = new String[rows.size()][];
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            plain[ri] = new String[cols];
            for (int c = 0; c < cols; c++) {
                plain[ri][c] = stripInline(c < r.length ? r[c] : "");
                w[c] = Math.min(28, Math.max(w[c], plain[ri][c].length()));
            }
        }
        int start = out.length();
        for (int ri = 0; ri < plain.length; ri++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                String cell = plain[ri][c];
                if (cell.length() > w[c]) cell = cell.substring(0, Math.max(0, w[c] - 1)) + "…";
                line.append(cell);
                for (int pad = cell.length(); pad < w[c]; pad++) line.append(' ');
                if (c < cols - 1) line.append(" │ ");
            }
            int ls = out.length();
            out.append(line);
            if (ri == 0) out.setSpan(new StyleSpan(Typeface.BOLD), ls, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (ri < plain.length - 1) out.append('\n');
            if (ri == 0) {
                StringBuilder sep = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    for (int k = 0; k < w[c]; k++) sep.append('─');
                    if (c < cols - 1) sep.append("─┼─");
                }
                out.append(sep);
                if (plain.length > 1) out.append('\n');
            }
        }
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.85f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String stripInline(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
    }

    // ---- Inline ----------------------------------------------------------------------------------

    /** Inline spans: `code`, ***both***, **bold**, __bold__, *italic*, _italic_, ~~strike~~, [text](url), &lt;url&gt;, bare URLs. */
    static void inline(SpannableStringBuilder out, String s, Style st) {
        int i = 0;
        int n = s.length();
        StringBuilder plain = new StringBuilder();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_~[]()#>!|<-+.{}".indexOf(s.charAt(i + 1)) >= 0) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int ticks = 1;
                while (i + ticks < n && s.charAt(i + ticks) == '`') ticks++;
                String fence = s.substring(i, i + ticks);
                int end = s.indexOf(fence, i + ticks);
                if (end > i + ticks) {
                    flush(out, plain);
                    int st0 = out.length();
                    out.append(s.substring(i + ticks, end).trim());
                    out.setSpan(new TypefaceSpan("monospace"), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(st.codeBackground), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (st.codeText != 0) out.setSpan(new ForegroundColorSpan(st.codeText), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + ticks;
                    continue;
                }
            }
            // ***bold italic***
            if ((c == '*' || c == '_') && s.startsWith("" + c + c + c, i)) {
                String mark = "" + c + c + c;
                int end = s.indexOf(mark, i + 3);
                if (end > i + 3) {
                    flush(out, plain);
                    int st0 = out.length();
                    inline(out, s.substring(i + 3, end), st);
                    out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 3;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c) {
                String mark = "" + c + c;
                int end = s.indexOf(mark, i + 2);
                if (end > i + 2 && (c == '*' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                    flush(out, plain);
                    int st0 = out.length();
                    inline(out, s.substring(i + 2, end), st);
                    out.setSpan(new StyleSpan(Typeface.BOLD), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) != ' ' && (c == '*' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = s.indexOf(c, i + 1);
                if (end > i + 1 && s.charAt(end - 1) != ' ' && (c == '*' || end + 1 >= n || !Character.isLetterOrDigit(s.charAt(end + 1)))) {
                    flush(out, plain);
                    int st0 = out.length();
                    inline(out, s.substring(i + 1, end), st);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    flush(out, plain);
                    int st0 = out.length();
                    inline(out, s.substring(i + 2, end), st);
                    out.setSpan(new StrikethroughSpan(), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[' || (c == '!' && i + 1 < n && s.charAt(i + 1) == '[')) {
                int open = c == '!' ? i + 1 : i;
                int close = s.indexOf("](", open);
                int paren = close < 0 ? -1 : s.indexOf(')', close + 2);
                if (close > open && paren > close) {
                    flush(out, plain);
                    String label = s.substring(open + 1, close);
                    String url = s.substring(close + 2, paren).trim();
                    int sp = url.indexOf(' '); // [text](url "title")
                    if (sp > 0) url = url.substring(0, sp);
                    int st0 = out.length();
                    if (label.isEmpty()) out.append(url);
                    else inline(out, label, st);
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        out.setSpan(new URLSpan(url), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = paren + 1;
                    continue;
                }
            }
            if (c == '<' && (s.startsWith("<http://", i) || s.startsWith("<https://", i))) {
                int end = s.indexOf('>', i + 1);
                if (end > i) {
                    flush(out, plain);
                    int st0 = out.length();
                    String url = s.substring(i + 1, end);
                    out.append(url);
                    out.setSpan(new URLSpan(url), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i)) && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = i;
                while (end < n && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != ')' && s.charAt(end) != '>') end++;
                while (end > i && ".,;:!?\"'".indexOf(s.charAt(end - 1)) >= 0) end--;
                flush(out, plain);
                int st0 = out.length();
                String url = s.substring(i, end);
                out.append(url);
                out.setSpan(new URLSpan(url), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
                continue;
            }
            plain.append(c);
            i++;
        }
        flush(out, plain);
    }

    private static void flush(SpannableStringBuilder out, StringBuilder plain) {
        if (plain.length() > 0) {
            out.append(plain);
            plain.setLength(0);
        }
    }

    // ---- Paragraph spans ---------------------------------------------------------------------------

    /**
     * A list item: the marker (•, ◦, 1., ☐, ☑) is drawn in the margin and every line of the item,
     * including wrapped lines, starts at the same x, so the text lines up exactly.
     */
    static final class ListSpan implements LeadingMarginSpan {
        private final String marker;
        private final float density;
        private final float marginDp;
        private final float markerStartDp;
        private final int color;

        ListSpan(String marker, float density, float marginDp, float markerStartDp, int color) {
            this.marker = marker;
            this.density = density;
            this.marginDp = marginDp;
            this.markerStartDp = markerStartDp;
            this.color = color;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return Math.round(marginDp * density);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            // Only on the item's own first line (a continuation paragraph inside the item has none).
            if (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
            int oldColor = p.getColor();
            p.setColor(color);
            float w = p.measureText(marker);
            float gap = 6f * density;
            float right = x + dir * (marginDp * density - gap);
            c.drawText(marker, dir > 0 ? right - w : right, baseline, p);
            p.setColor(oldColor);
        }
    }

    /** Stripe on the left of a quote (on every line, also wrapped ones). */
    static final class QuoteBar implements LeadingMarginSpan {
        private final int color;
        private final float density;

        QuoteBar(int color, float density) {
            this.color = color;
            this.density = density;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return Math.round(14f * density);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float bar = 3f * density;
            c.drawRect(Math.min(x, x + dir * bar), top, Math.max(x, x + dir * bar), bottom, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }
}
