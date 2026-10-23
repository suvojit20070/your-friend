package com.suvo.agent.core.util;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Small text helpers shared by memory ranking, tools and prompt assembly. */
public final class TextUtil {

    private TextUtil() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Truncates to at most {@code max} characters, marking the cut so the model knows output was clipped. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int keep = Math.max(0, max - 40);
        return s.substring(0, keep) + "\n…[truncated " + (s.length() - keep) + " chars]";
    }

    /** Keeps the beginning and the end of long output (useful for command logs). */
    public static String headTail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int half = Math.max(0, (max - 60) / 2);
        return s.substring(0, half) + "\n…[" + (s.length() - 2 * half) + " chars omitted]…\n"
                + s.substring(s.length() - half);
    }

    /** Lower-cased, accent-folded word tokens (Unicode letters/digits, keeps CJK/Indic characters). */
    public static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        String norm = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < norm.length(); ) {
            int cp = norm.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                if (isCjk(cp)) {
                    flush(cur, out);
                    out.add(new String(Character.toChars(cp)));
                } else {
                    cur.appendCodePoint(cp);
                }
            } else {
                flush(cur, out);
            }
        }
        flush(cur, out);
        out.removeIf(t -> t.length() < 2 && !isCjk(t.codePointAt(0)));
        out.removeAll(STOP);
        return out;
    }

    private static void flush(StringBuilder cur, Set<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    public static boolean isCjk(int cp) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                || sc == Character.UnicodeScript.KATAKANA;
    }

    /** Jaccard similarity of token sets, 0..1. */
    public static double similarity(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int inter = 0;
        for (String t : ta) if (tb.contains(t)) inter++;
        int union = ta.size() + tb.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    private static final Set<String> STOP = new LinkedHashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were",
            "be", "it", "this", "that", "with", "as", "at", "by", "my", "me", "i", "you", "your",
            "do", "does", "did", "can", "please", "hai", "ka", "ki", "ke", "ko", "se", "mein", "aur"));
}
