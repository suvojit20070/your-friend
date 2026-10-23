package com.suvo.agent.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Small Markdown renderer for chat bubbles: headings, bold, italic, strikethrough, inline code,
 * fenced code blocks, bullet and numbered lists (nested by indent), task lists, block quotes, links,
 * horizontal rules and tables (shown aligned in monospace). Unknown syntax is left as plain text,
 * so nothing the model writes is ever lost.
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

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([\\w+#.-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(\\[( |x|X)\\]\\s+)?(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

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

    public static CharSequence render(String src, Style st) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (src == null || src.isEmpty()) return out;
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        boolean lastWasBlank = true;
        while (i < lines.length) {
            String line = lines[i];

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith(marker)) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                i++; // closing fence (or end of text while streaming)
                newBlock(out);
                int start = out.length();
                String lang = fence.group(2);
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
                lastWasBlank = false;
                continue;
            }

            if (line.trim().isEmpty()) {
                lastWasBlank = true;
                i++;
                continue;
            }

            // Table: header row, separator row, body rows.
            if (line.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                java.util.List<String[]> rows = new java.util.ArrayList<>();
                rows.add(cells(line));
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].trim().isEmpty()) {
                    rows.add(cells(lines[i]));
                    i++;
                }
                newBlock(out);
                appendTable(out, rows, st);
                lastWasBlank = false;
                continue;
            }

            Matcher h = HEADING.matcher(line);
            Matcher b = BULLET.matcher(line);
            Matcher n = NUMBERED.matcher(line);
            Matcher q = QUOTE.matcher(line);
            if (RULE.matcher(line).matches()) {
                newBlock(out);
                int s = out.length();
                out.append("────────────");
                out.setSpan(new ForegroundColorSpan(st.muted), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (h.matches()) {
                if (out.length() > 0) out.append(lastWasBlank ? "\n\n" : "\n");
                int level = h.group(1).length();
                int s = out.length();
                inline(out, h.group(2), st);
                float size = level == 1 ? 1.35f : level == 2 ? 1.22f : level == 3 ? 1.1f : 1.0f;
                out.setSpan(new RelativeSizeSpan(size), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (b.matches()) {
                lineBreak(out, lastWasBlank);
                int depth = indentDepth(b.group(1));
                int s = out.length();
                String box = b.group(3);
                String mark = box == null ? (depth % 2 == 0 ? "•" : "◦") : (box.trim().isEmpty() ? "☐" : "☑");
                out.append(mark).append("  ");
                inline(out, b.group(4), st);
                int margin = (int) (st.density * (8 + 16 * depth));
                out.setSpan(new LeadingMarginSpan.Standard(margin, margin + (int) (st.density * 14)), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (n.matches()) {
                lineBreak(out, lastWasBlank);
                int depth = indentDepth(n.group(1));
                int s = out.length();
                out.append(n.group(2)).append(".  ");
                inline(out, n.group(3), st);
                int margin = (int) (st.density * (6 + 16 * depth));
                out.setSpan(new LeadingMarginSpan.Standard(margin, margin + (int) (st.density * 20)), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (q.matches()) {
                lineBreak(out, lastWasBlank);
                int s = out.length();
                inline(out, q.group(1), st);
                if (out.length() == s) out.append(' ');
                out.setSpan(new QuoteSpan(st.quoteStripe), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(st.muted), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // Paragraph text: consecutive lines join with a line break (chat text is line-oriented).
                if (out.length() > 0) out.append(lastWasBlank ? "\n\n" : "\n");
                inline(out, line.trim(), st);
            }
            lastWasBlank = false;
            i++;
        }
        return out;
    }

    private static void newBlock(SpannableStringBuilder out) {
        if (out.length() > 0) out.append("\n\n");
    }

    private static void lineBreak(SpannableStringBuilder out, boolean blank) {
        if (out.length() > 0) out.append(blank ? "\n\n" : "\n");
    }

    private static int indentDepth(String ws) {
        int spaces = 0;
        for (char c : ws.toCharArray()) spaces += c == '\t' ? 4 : 1;
        return Math.min(4, spaces / 2);
    }

    private static String[] cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        String[] parts = r.split("\\|", -1);
        for (int k = 0; k < parts.length; k++) parts[k] = stripInline(parts[k].trim());
        return parts;
    }

    private static void appendTable(SpannableStringBuilder out, java.util.List<String[]> rows, Style st) {
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        int[] w = new int[cols];
        for (String[] r : rows) for (int c = 0; c < r.length; c++) w[c] = Math.min(28, Math.max(w[c], r[c].length()));
        int start = out.length();
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                String cell = c < r.length ? r[c] : "";
                if (cell.length() > w[c]) cell = cell.substring(0, Math.max(0, w[c] - 1)) + "…";
                line.append(cell);
                for (int pad = cell.length(); pad < w[c]; pad++) line.append(' ');
                if (c < cols - 1) line.append(" │ ");
            }
            int ls = out.length();
            out.append(line);
            if (ri == 0) out.setSpan(new StyleSpan(Typeface.BOLD), ls, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (ri < rows.size() - 1) out.append('\n');
            if (ri == 0) {
                StringBuilder sep = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    for (int k = 0; k < w[c]; k++) sep.append('─');
                    if (c < cols - 1) sep.append("─┼─");
                }
                out.append(sep);
                if (rows.size() > 1) out.append('\n');
            }
        }
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.85f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String stripInline(String s) {
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("`([^`]+)`", "$1");
    }

    /** Inline spans: `code`, **bold**, __bold__, *italic*, _italic_, ~~strike~~, [text](url), <url>, bare URLs. */
    static void inline(SpannableStringBuilder out, String s, Style st) {
        int i = 0;
        int n = s.length();
        StringBuilder plain = new StringBuilder();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_~[]()#>!|".indexOf(s.charAt(i + 1)) >= 0) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    flush(out, plain);
                    int st0 = out.length();
                    out.append(s, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(st.codeBackground), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (st.codeText != 0) out.setSpan(new ForegroundColorSpan(st.codeText), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c) {
                String mark = "" + c + c;
                int end = s.indexOf(mark, i + 2);
                if (end > i + 2) {
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
                    int st0 = out.length();
                    out.append(label.isEmpty() ? url : label);
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        out.setSpan(new URLSpan(url), st0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    i = paren + 1;
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
}
