package com.suvo.agent.core.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Wake words for hands-free mode ("tanu, hey tanu, hi alex"). A sentence triggers when it contains
 * one of the phrases anywhere; small recognition slips are tolerated ("tanoo" for "tanu"). The
 * command is what was said after the wake word (or before it, when nothing follows: "open YouTube
 * tanu"). Pure Java, unit-tested.
 */
public final class WakeWords {

    private WakeWords() {
    }

    /** Result of {@link #match}: {@code triggered} and the command text (may be empty). */
    public static final class Match {
        public final boolean triggered;
        public final String command;

        Match(boolean triggered, String command) {
            this.triggered = triggered;
            this.command = command;
        }
    }

    /** Parses the comma/newline separated setting into phrases. */
    public static List<String[]> parse(String setting) {
        List<String[]> out = new ArrayList<>();
        if (setting == null) return out;
        for (String part : setting.split("[,\\n;|]+")) {
            List<String> t = tokens(part);
            if (!t.isEmpty()) out.add(t.toArray(new String[0]));
        }
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            i += Character.charCount(cp);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Folds common spelling variants of the same sound ("tanoo" = "tanu", "alexx" = "alex"). */
    static String fold(String w) {
        String s = w.replace("oo", "u").replace("ee", "i").replace("ph", "f").replace("w", "v");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && c == s.charAt(i - 1)) continue;
            out.append(c);
        }
        return out.toString();
    }

    static boolean similar(String heard, String wanted) {
        if (heard.equals(wanted)) return true;
        heard = fold(heard);
        wanted = fold(wanted);
        if (heard.equals(wanted)) return true;
        int n = Math.min(heard.length(), wanted.length());
        if (n < 4) return false;
        return distance(heard, wanted) <= 1;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * @param phrases from {@link #parse}; when empty every sentence triggers with itself as command.
     */
    public static Match match(String sentence, List<String[]> phrases) {
        String text = sentence == null ? "" : sentence.trim();
        if (phrases == null || phrases.isEmpty()) return new Match(!text.isEmpty(), text);
        List<String> words = tokens(text);
        int bestStart = -1, bestLen = 0;
        for (String[] p : phrases) {
            for (int i = 0; i + p.length <= words.size(); i++) {
                boolean ok = true;
                for (int k = 0; k < p.length && ok; k++) ok = similar(words.get(i + k), p[k]);
                // Prefer the longest phrase ("hey tanu" over "tanu"), then the earliest position.
                if (ok && (p.length > bestLen || (p.length == bestLen && i < bestStart))) {
                    bestStart = i;
                    bestLen = p.length;
                }
            }
        }
        if (bestStart < 0) return new Match(false, "");
        List<String> after = words.subList(bestStart + bestLen, words.size());
        List<String> before = words.subList(0, bestStart);
        String cmd = String.join(" ", after.isEmpty() ? before : after).trim();
        return new Match(true, cmd);
    }
}
