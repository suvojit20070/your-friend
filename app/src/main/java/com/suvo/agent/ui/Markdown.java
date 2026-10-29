package com.suvo.agent.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Markdown renderer for chat: headings, bold, italic, strikethrough, inline code, fenced code
 * blocks (with light syntax colours), bullet / numbered / task lists (nested by indent, wrapped
 * lines stay aligned), block quotes, links, horizontal rules and tables. Unknown syntax is left as
 * plain text, so nothing the model writes is ever lost.
 *
 * Every colour comes from the app's tonal {@link Palette} (the seed the user picked, or the
 * wallpaper), so answers follow the theme: headings in the serif face and primary colour, list
 * markers drawn in primary, quotes on a soft primary tint, code on a tonal card.
 *
 * Two ways to use it:
 * <ul>
 *   <li>{@link #render} gives one {@link CharSequence} (code blocks and tables are monospace text).</li>
 *   <li>{@link #blocks} splits the answer into text, code and table blocks so the chat can show
 *       code with its own horizontal scroll and a copy button, and tables as real grids.</li>
 * </ul>
 */
public final class Markdown {

    /** Colours and faces used by the renderer; {@link #styleFor} fills them from the palette. */
    public static final class Style {
        public int codeBackground = 0x22888888;
        public int codeText = 0;
        public int inlineCodeBackground = 0x22888888;
        public int inlineCodeText = 0;
        public int quoteStripe = 0xFF7C5CFF;
        public int quoteBackground = 0x147C5CFF;
        public int link = 0;
        public int muted = 0x99888888;
        public int heading = 0;
        public int marker = 0xFF7C5CFF;
        public int onMarker = 0xFFFFFFFF;
        public int rule = 0x55888888;
        public int keyword = 0;
        public int string = 0;
        public int comment = 0;
        public int number = 0;
        public int type = 0;
        /** Serif face for headings (null = the text's own face). */
        public Typeface headingFont;
        public float density = 2f;
    }

    /** One part of an answer. */
    public static final class Block {
        public static final int TEXT = 0, CODE = 1, TABLE = 2, QUOTE = 3;
        public int type;
        /** TEXT: the styled text. */
        public CharSequence text = "";
        /** CODE: language label (may be empty) and the code itself. QUOTE: alert kind (NOTE, TIP, WARNING…) or empty. */
        public String lang = "";
        public String code = "";
        /** CODE: the code with syntax colours (null when not highlighted, e.g. while streaming). */
        public CharSequence styledCode;
        /** TABLE: rows of styled cells, the first row is the header; align per column: 0 left, 1 centre, 2 right. */
        public List<CharSequence[]> rows = new ArrayList<>();
        public int[] align = new int[0];

        /** Text that identifies the content (used to skip rebuilding a view that shows the same thing). */
        public String signature() {
            switch (type) {
                case CODE:
                    return "c:" + lang + "\u0000" + code + (styledCode != null ? "\u0001" : "");
                case TABLE: {
                    StringBuilder sb = new StringBuilder("t:");
                    for (CharSequence[] r : rows) {
                        for (CharSequence c : r) sb.append(c).append('\u0002');
                        sb.append('\u0003');
                    }
                    return sb.toString();
                }
                case QUOTE:
                    return "q:" + lang + "\u0000" + text;
                default:
                    return "x:" + text;
            }
        }
    }

    // Kinds of block, used to decide the spacing between them.
    private static final int NONE = 0, PARA = 1, HEAD = 2, LIST = 3, QUOTE = 4, RULE = 5, CODE = 6, TABLE = 7;

    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(\\[( |x|X)\\]\\s+)?(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern QUOTE_RE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern RULE_RE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern ALERT = Pattern.compile("^\\s*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|INFO|DANGER)\\]\\s*\\n?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");

    /** Marker kinds drawn by {@link ListSpan}. */
    static final int DOT = 0, RING = 1, SQUARE = 2, NUMBER = 3, BOX = 4, BOX_DONE = 5;

    private Markdown() {
    }

    /** Theme-aware style for the chat, from the current palette. */
    public static Style styleFor(android.content.Context ctx) {
        Palette p = Palette.of(ctx);
        Style st = new Style();
        st.codeBackground = p.surfaceHigh;
        st.codeText = p.onSurface;
        st.inlineCodeBackground = Palette.alpha(p.primary, p.dark ? 0.20f : 0.12f);
        st.inlineCodeText = p.dark ? p.onPrimaryContainer : p.primary;
        st.quoteStripe = p.primary;
        st.quoteBackground = Palette.alpha(p.primary, p.dark ? 0.10f : 0.07f);
        st.link = p.primary;
        st.muted = p.onSurfaceVariant;
        st.heading = p.primary;
        st.marker = p.primary;
        st.onMarker = p.onPrimary;
        st.rule = Palette.alpha(p.outlineVariant, 0.9f);
        st.keyword = p.primary;
        st.string = p.tertiary;
        st.comment = Palette.alpha(p.onSurfaceVariant, 0.75f);
        st.number = hueShift(p.primary, -150f);
        st.type = Palette.alpha(p.tertiary, 0.9f);
        try {
            st.headingFont = androidx.core.content.res.ResourcesCompat.getFont(ctx, com.suvo.agent.R.font.anthropic_serif);
        } catch (RuntimeException e) {
            st.headingFont = null;
        }
        st.density = ctx.getResources().getDisplayMetrics().density;
        return st;
    }

    /** The colour with its hue turned by {@code deg} (same lightness feel), for a distinct code colour. */
    private static int hueShift(int color, float deg) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[0] = ((hsv[0] + deg) % 360f + 360f) % 360f;
        return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(color), hsv);
    }

    /** Everything as one styled text (code blocks and tables as monospace text). */
    public static CharSequence render(String src, Style st) {
        return build(src, st, null, false);
    }

    /** The answer split into text, code and table blocks, code with syntax colours. */
    public static List<Block> blocks(String src, Style st) {
        return blocks(src, st, true);
    }

    /** As {@link #blocks(String, Style)}; {@code highlight} false skips syntax colours (cheaper while streaming). */
    public static List<Block> blocks(String src, Style st, boolean highlight) {
        List<Block> blocks = new ArrayList<>();
        build(src, st, blocks, highlight);
        return blocks;
    }

    // ---- Block parser ----------------------------------------------------------------------------

    private static CharSequence build(String src, Style st, List<Block> blocks, boolean highlight) {
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
                    if (highlight) b.styledCode = CodeColors.highlight(b.code, lang, st);
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

            // Quote (in block mode): one card for all its lines; "> [!NOTE]" makes a titled alert.
            if (blocks != null && QUOTE_RE.matcher(line).matches()) {
                StringBuilder inner = new StringBuilder();
                while (i < lines.length) {
                    Matcher qm = QUOTE_RE.matcher(lines[i]);
                    if (!qm.matches()) break;
                    if (inner.length() > 0) inner.append('\n');
                    inner.append(qm.group(1));
                    i++;
                }
                flush(out, blocks);
                out = new SpannableStringBuilder();
                Block qb = new Block();
                qb.type = Block.QUOTE;
                String body = inner.toString();
                Matcher alert = ALERT.matcher(body);
                if (alert.find()) {
                    qb.lang = alert.group(1).toUpperCase(java.util.Locale.ROOT);
                    body = body.substring(alert.end());
                }
                qb.text = new SpannedString(build(body, st, null, false));
                blocks.add(qb);
                prev = QUOTE;
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
                out.append(' ');
                out.setSpan(new RuleSpan(st.rule, st.density), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = RULE;
                lastItem = null;
            } else if (h.matches()) {
                gap(out, prev, HEAD, blank);
                int level = h.group(1).length();
                int s = out.length();
                inline(out, h.group(2), st);
                float size = level == 1 ? 1.38f : level == 2 ? 1.24f : level == 3 ? 1.12f : 1.0f;
                out.setSpan(new RelativeSizeSpan(size), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (st.headingFont != null && level <= 3) {
                    out.setSpan(new FontSpan(st.headingFont), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                int color = level <= 2 ? st.heading : 0;
                if (color != 0) out.setSpan(new ForegroundColorSpan(color), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = HEAD;
                lastItem = null;
            } else if (b.matches() || n.matches()) {
                boolean bullet = b.matches(); // (a line can only be one of the two)
                String ws = bullet ? b.group(1) : n.group(1);
                int depth = indentDepth(ws);
                gap(out, prev, LIST, blank);
                int s = out.length();
                int kind;
                String label = "";
                String body;
                boolean done = false;
                if (bullet) {
                    String box = b.group(3);
                    if (box == null) {
                        kind = depth % 3 == 0 ? DOT : depth % 3 == 1 ? RING : SQUARE;
                    } else {
                        done = !box.trim().isEmpty();
                        kind = done ? BOX_DONE : BOX;
                    }
                    body = b.group(4);
                } else {
                    kind = NUMBER;
                    label = n.group(2) + ".";
                    body = n.group(3);
                }
                inline(out, body, st);
                if (out.length() == s) out.append(' ');
                if (done) {
                    out.setSpan(new StrikethroughSpan(), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(st.muted), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                float indent = kind == NUMBER ? (label.length() > 3 ? 34 : 28) : kind >= BOX ? 30 : 22;
                lastItem = new ListSpan(kind, label, st.density, depth * 20 + indent, depth * 20, st.marker, st.onMarker);
                lastItemStart = s;
                out.setSpan(lastItem, s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                prev = LIST;
            } else if (QUOTE_RE.matcher(line).matches() && q.matches()) {
                gap(out, prev, QUOTE, blank);
                int s = out.length();
                inline(out, q.group(1), st);
                if (out.length() == s) out.append(' ');
                // One span over all consecutive quote lines, so the tinted block has rounded ends.
                QuoteBar bar = null;
                QuoteBar[] near = s >= 2 ? out.getSpans(s - 2, s - 1, QuoteBar.class) : new QuoteBar[0];
                if (prev == QUOTE && near.length > 0) bar = near[near.length - 1];
                if (bar != null) {
                    int bs = out.getSpanStart(bar);
                    out.removeSpan(bar);
                    out.setSpan(bar, bs, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    out.setSpan(new QuoteBar(st.quoteStripe, st.quoteBackground, st.density), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
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
        if (kind == QUOTE && prev == QUOTE) together = true; // a quote stays one block
        out.append('\n');
        if (together && !(blank && kind != QUOTE)) return;
        int s = out.length();
        out.append('\n');
        // The extra line is half height, so paragraphs are separated without big holes.
        out.setSpan(new RelativeSizeSpan(kind == HEAD ? 0.75f : 0.55f), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                    out.setSpan(new RelativeSizeSpan(0.92f), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(st.inlineCodeBackground), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    int fg = st.inlineCodeText != 0 ? st.inlineCodeText : st.codeText;
                    if (fg != 0) out.setSpan(new ForegroundColorSpan(fg), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                    if (url.startsWith(AppScreens.SCHEME) || url.startsWith("yf://")) {
                        // A button that opens a screen of this app: [Open Voice](yf://open/voice)
                        String key = url.substring(url.lastIndexOf('/') + 1);
                        int b0 = out.length();
                        out.append(' ');
                        inline(out, label.isEmpty() ? key : label, st);
                        out.append(" ↗ ");
                        if (AppScreens.find(key) != null) {
                            int accent = st.link != 0 ? st.link : 0xFF7C5CFF;
                            out.setSpan(new AppLink(key, accent), b0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new BackgroundColorSpan((accent & 0x00FFFFFF) | 0x33000000), b0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new StyleSpan(Typeface.BOLD), b0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = paren + 1;
                        continue;
                    }
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

    // ---- Code colours ----------------------------------------------------------------------------

    /**
     * Small, language-agnostic syntax colouring: comments, strings, numbers, keywords, types
     * (Capitalised words) and annotations. Good enough for chat; never changes the text.
     */
    static final class CodeColors {
        private static final int MAX = 24_000;
        private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
                "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "def",
                "default", "del", "do", "elif", "else", "enum", "except", "export", "extends", "false", "final", "finally",
                "fn", "for", "from", "func", "function", "global", "go", "if", "impl", "implements", "import", "in",
                "include", "instanceof", "interface", "is", "lambda", "let", "match", "mod", "module", "mut", "namespace",
                "new", "nil", "none", "None", "not", "null", "nullptr", "or", "override", "package", "pass", "private",
                "protected", "pub", "public", "raise", "return", "self", "static", "struct", "super", "switch", "synchronized",
                "this", "throw", "throws", "trait", "True", "False", "true", "try", "type", "typedef", "typeof", "use",
                "using", "val", "var", "void", "volatile", "when", "where", "while", "with", "yield", "int", "long", "float",
                "double", "char", "boolean", "bool", "byte", "short", "string", "auto", "unsigned", "signed", "virtual",
                "template", "typename", "echo", "then", "fi", "esac", "done", "local", "readonly", "unset", "elsif",
                "end", "begin", "select", "insert", "update", "delete", "create", "table", "values", "into", "join", "on",
                "group", "order", "by", "limit", "having", "union", "set", "drop", "alter", "suspend", "inline", "constexpr"));
        private static final Set<String> HASH = new HashSet<>(Arrays.asList(
                "py", "python", "python3", "sh", "bash", "shell", "zsh", "console", "yaml", "yml", "toml", "rb", "ruby", "r",
                "perl", "pl", "dockerfile", "docker", "makefile", "make", "cmake", "conf", "properties", "ps1", "powershell",
                "nix", "elixir", "ex", "gitignore", "env", "ini"));
        private static final Set<String> NO_SLASH = new HashSet<>(Arrays.asList(
                "py", "python", "python3", "sh", "bash", "shell", "zsh", "console", "yaml", "yml", "toml", "rb", "ruby", "r",
                "json", "ini", "properties", "conf", "sql", "html", "xml", "md", "markdown", "text", "txt", "csv", "env"));

        private CodeColors() {
        }

        static CharSequence highlight(String code, String lang, Style st) {
            if (code == null || code.isEmpty() || code.length() > MAX) return code == null ? "" : code;
            String l = lang == null ? "" : lang.toLowerCase(java.util.Locale.ROOT);
            if (l.equals("text") || l.equals("txt") || l.equals("plain") || l.equals("output") || l.equals("log")) return code;
            boolean hash = HASH.contains(l) || l.isEmpty() && code.startsWith("#!");
            boolean slash = !NO_SLASH.contains(l);
            boolean dash = l.equals("sql") || l.equals("lua") || l.equals("haskell") || l.equals("hs");
            boolean markup = l.equals("html") || l.equals("xml") || l.equals("svg") || l.equals("vue");
            SpannableStringBuilder out = new SpannableStringBuilder(code);
            int n = code.length();
            int i = 0;
            while (i < n) {
                char c = code.charAt(i);
                // Comments.
                if (slash && c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                    int e = lineEnd(code, i);
                    color(out, st.comment, i, e, true);
                    i = e;
                    continue;
                }
                if (slash && c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                    int e = code.indexOf("*/", i + 2);
                    e = e < 0 ? n : e + 2;
                    color(out, st.comment, i, e, true);
                    i = e;
                    continue;
                }
                if (hash && c == '#' && (i == 0 || code.charAt(i - 1) != '$')) {
                    int e = lineEnd(code, i);
                    color(out, st.comment, i, e, true);
                    i = e;
                    continue;
                }
                if (dash && c == '-' && i + 1 < n && code.charAt(i + 1) == '-') {
                    int e = lineEnd(code, i);
                    color(out, st.comment, i, e, true);
                    i = e;
                    continue;
                }
                if (markup && code.startsWith("<!--", i)) {
                    int e = code.indexOf("-->", i + 4);
                    e = e < 0 ? n : e + 3;
                    color(out, st.comment, i, e, true);
                    i = e;
                    continue;
                }
                // Strings.
                if (c == '"' || c == '\'' || c == '`') {
                    if (c == '\'' && i > 0 && Character.isLetterOrDigit(code.charAt(i - 1)) && !markup) {
                        i++; // an apostrophe inside a word (don't, it's)
                        continue;
                    }
                    int e = i + 1;
                    boolean triple = code.startsWith("" + c + c + c, i);
                    if (triple) {
                        int t = code.indexOf("" + c + c + c, i + 3);
                        e = t < 0 ? n : t + 3;
                    } else {
                        while (e < n) {
                            char d = code.charAt(e);
                            if (d == '\\') {
                                e += 2;
                                continue;
                            }
                            if (d == c) {
                                e++;
                                break;
                            }
                            if (d == '\n' && c != '`') break;
                            e++;
                        }
                    }
                    e = Math.min(e, n);
                    color(out, st.string, i, e, false);
                    i = e;
                    continue;
                }
                // Numbers.
                if (Character.isDigit(c) && (i == 0 || !Character.isLetterOrDigit(code.charAt(i - 1)) && code.charAt(i - 1) != '_')) {
                    int e = i + 1;
                    while (e < n && (Character.isLetterOrDigit(code.charAt(e)) || code.charAt(e) == '.' || code.charAt(e) == '_')) e++;
                    color(out, st.number, i, e, false);
                    i = e;
                    continue;
                }
                // Annotations / decorators.
                if (c == '@' && i + 1 < n && Character.isLetter(code.charAt(i + 1))) {
                    int e = i + 1;
                    while (e < n && (Character.isLetterOrDigit(code.charAt(e)) || code.charAt(e) == '_' || code.charAt(e) == '.')) e++;
                    color(out, st.type, i, e, false);
                    i = e;
                    continue;
                }
                // Words.
                if (Character.isLetter(c) || c == '_' || c == '$') {
                    int e = i + 1;
                    while (e < n && (Character.isLetterOrDigit(code.charAt(e)) || code.charAt(e) == '_')) e++;
                    String w = code.substring(i, e);
                    if (KEYWORDS.contains(w)) {
                        color(out, st.keyword, i, e, false);
                        out.setSpan(new StyleSpan(Typeface.BOLD), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (Character.isUpperCase(c) && w.length() > 1 && !w.equals(w.toUpperCase(java.util.Locale.ROOT))) {
                        color(out, st.type, i, e, false);
                    }
                    i = e;
                    continue;
                }
                i++;
            }
            return new SpannedString(out);
        }

        private static int lineEnd(String s, int from) {
            int e = s.indexOf('\n', from);
            return e < 0 ? s.length() : e;
        }

        private static void color(SpannableStringBuilder out, int color, int s, int e, boolean italic) {
            if (color == 0 || e <= s) return;
            out.setSpan(new ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (italic) out.setSpan(new StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ---- Paragraph spans ---------------------------------------------------------------------------

    /**
     * A list item: the marker (filled dot, ring, square, number, checkbox) is drawn in the margin in
     * the primary colour and every line of the item, including wrapped lines, starts at the same x,
     * so the text lines up exactly.
     */
    static final class ListSpan implements LeadingMarginSpan {
        private final int kind;
        private final String label;
        private final float density;
        private final float marginDp;
        private final float markerStartDp;
        private final int color;
        private final int onColor;

        ListSpan(int kind, String label, float density, float marginDp, float markerStartDp, int color, int onColor) {
            this.kind = kind;
            this.label = label;
            this.density = density;
            this.marginDp = marginDp;
            this.markerStartDp = markerStartDp;
            this.color = color;
            this.onColor = onColor;
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
            Paint.Style oldStyle = p.getStyle();
            float oldStroke = p.getStrokeWidth();
            boolean oldFake = p.isFakeBoldText();
            p.setColor(color);
            float gap = 7f * density;
            float right = x + dir * (marginDp * density - gap);
            // Vertical centre of the first line's x-height, so shapes sit on the text.
            Paint.FontMetrics fm = p.getFontMetrics();
            float cy = baseline + (fm.ascent * 0.36f);
            switch (kind) {
                case NUMBER: {
                    p.setFakeBoldText(true);
                    float w = p.measureText(label);
                    c.drawText(label, dir > 0 ? right - w : right, baseline, p);
                    break;
                }
                case DOT:
                case RING:
                case SQUARE: {
                    float r = 3.1f * density;
                    float cx = dir > 0 ? right - r : right + r;
                    if (kind == DOT) {
                        p.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, cy, r, p);
                    } else if (kind == RING) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.4f * density);
                        c.drawCircle(cx, cy, r * 0.9f, p);
                    } else {
                        p.setStyle(Paint.Style.FILL);
                        float s = r * 0.8f;
                        c.drawRect(cx - s, cy - s, cx + s, cy + s, p);
                    }
                    break;
                }
                default: { // BOX / BOX_DONE
                    float s = 8f * density;
                    float l = dir > 0 ? right - 2 * s : right;
                    RectF box = new RectF(l, cy - s, l + 2 * s, cy + s);
                    float rr = 4f * density;
                    if (kind == BOX_DONE) {
                        p.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(box, rr, rr, p);
                        p.setColor(onColor);
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.8f * density);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        Path tick = new Path();
                        tick.moveTo(box.left + s * 0.45f, box.centerY());
                        tick.lineTo(box.left + s * 0.85f, box.centerY() + s * 0.42f);
                        tick.lineTo(box.right - s * 0.42f, box.centerY() - s * 0.45f);
                        c.drawPath(tick, p);
                        p.setStrokeCap(Paint.Cap.BUTT);
                    } else {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(1.6f * density);
                        box.inset(0.8f * density, 0.8f * density);
                        c.drawRoundRect(box, rr, rr, p);
                    }
                    break;
                }
            }
            p.setFakeBoldText(oldFake);
            p.setStrokeWidth(oldStroke);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }
    }

    /** Tappable button that opens a screen of the app (see {@link AppScreens}). */
    static final class AppLink extends ClickableSpan {
        private final String key;
        private final int color;

        AppLink(String key, int color) {
            this.key = key;
            this.color = color;
        }

        @Override
        public void onClick(View widget) {
            AppScreens.open(widget.getContext(), key);
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(false);
        }
    }

    /**
     * A quote: soft primary-tinted block with rounded ends and a primary stripe on the left (on
     * every line, also wrapped ones). One span covers all consecutive quote lines.
     */
    static final class QuoteBar implements LeadingMarginSpan, LineBackgroundSpan {
        private final int color;
        private final int background;
        private final float density;
        private final RectF rect = new RectF();
        private final Path path = new Path();

        QuoteBar(int color, int background, float density) {
            this.color = color;
            this.background = background;
            this.density = density;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return Math.round(16f * density);
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                      CharSequence text, int start, int end, boolean first, Layout layout) {
            Paint.Style oldStyle = p.getStyle();
            int oldColor = p.getColor();
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float bar = 3f * density;
            float inset = 0f;
            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                // Shorter at the very top and bottom, so the stripe follows the rounded block.
                if (sp.getSpanStart(this) == start) inset = 4f * density;
                if (sp.getSpanEnd(this) <= end) bottom -= Math.round(4f * density);
            }
            c.drawRect(Math.min(x, x + dir * bar), top + inset, Math.max(x, x + dir * bar), bottom, p);
            p.setStyle(oldStyle);
            p.setColor(oldColor);
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lineNumber) {
            if (background == 0) return;
            boolean firstLine = true, lastLine = true;
            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                firstLine = sp.getSpanStart(this) == start;
                lastLine = sp.getSpanEnd(this) <= end;
            }
            int old = p.getColor();
            Paint.Style oldStyle = p.getStyle();
            p.setColor(background);
            p.setStyle(Paint.Style.FILL);
            float r = 10f * density;
            rect.set(left, top, right, bottom);
            float[] radii = {
                    firstLine ? r : 0, firstLine ? r : 0, firstLine ? r : 0, firstLine ? r : 0,
                    lastLine ? r : 0, lastLine ? r : 0, lastLine ? r : 0, lastLine ? r : 0};
            path.reset();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            c.drawPath(path, p);
            p.setColor(old);
            p.setStyle(oldStyle);
        }
    }

    /** Horizontal rule: a thin line across the whole width, on a line of its own. */
    static final class RuleSpan implements LineBackgroundSpan {
        private final int color;
        private final float density;

        RuleSpan(int color, float density) {
            this.color = color;
            this.density = density;
        }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lineNumber) {
            int old = p.getColor();
            Paint.Style oldStyle = p.getStyle();
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            float h = Math.max(1f, density);
            float y = (top + baseline) / 2f;
            c.drawRect(left, y - h / 2f, right, y + h / 2f, p);
            p.setColor(old);
            p.setStyle(oldStyle);
        }
    }

    /** A custom typeface (the serif heading face) that also works below Android 9. */
    static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face;

        FontSpan(Typeface face) {
            this.face = face;
        }

        private void apply(TextPaint p) {
            Typeface old = p.getTypeface();
            int style = old == null ? 0 : old.getStyle();
            Typeface tf = Typeface.create(face, style);
            int fake = style & ~tf.getStyle();
            if ((fake & Typeface.BOLD) != 0) p.setFakeBoldText(true);
            if ((fake & Typeface.ITALIC) != 0) p.setTextSkewX(-0.25f);
            p.setTypeface(tf);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            apply(tp);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            apply(tp);
        }
    }
}
