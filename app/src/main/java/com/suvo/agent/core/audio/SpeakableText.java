package com.suvo.agent.core.audio;

import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Which part of a reply that is still streaming in may be read aloud, and where to cut it into
 * pieces for the voice: thinking, code, tool calls and JSON are never said. Pure Java (tested).
 */
public final class SpeakableText {

    private SpeakableText() {
    }

    private static final Pattern CODE_DONE = Pattern.compile("(?s)```.*?```");

    /** The reply text that may be said: no thinking, no code (an open code block ends it for now). */
    public static String speakable(String raw) {
        String answer = com.suvo.agent.core.llm.ThinkTags.split(raw)[1];
        answer = CODE_DONE.matcher(answer).replaceAll(" ");
        int open = answer.indexOf("```");
        if (open >= 0) answer = answer.substring(0, open);
        // A tool call written as text: nothing from there on is for the ear.
        int tc = indexOfAny(answer, "<tool_call", "<function", "<|tool", "[TOOL_CALLS]");
        if (tc >= 0) answer = answer.substring(0, tc);
        return answer;
    }

    private static int indexOfAny(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** JSON, tool calls and similar machine text (never read aloud). */
    public static boolean looksLikeMarkup(String piece) {
        String t = piece.trim();
        if (t.isEmpty()) return true;
        if (t.startsWith("{") || t.startsWith("[{") || t.startsWith("\"")) return true;
        return t.contains("\"name\":") || t.contains("\"arguments\"") || t.contains("function_call") || t.contains("{\"")
                || t.contains("tool_call") || t.contains("</");
    }

    /**
     * Where to cut the next piece off {@code rest}: after the first sentence end at least
     * {@code min} characters in (a sentence end needs a space after it, so "3.5" is not one);
     * a long run without one is cut at a space; at the end everything goes.
     */
    public static int cutPoint(String rest, int min, boolean all) {
        int n = rest.length();
        for (int i = 0; i < n; i++) {
            char c = rest.charAt(i);
            boolean end = c == '\n' || ".!?।॥。！？".indexOf(c) >= 0;
            if (!end) continue;
            boolean closed = i + 1 < n ? Character.isWhitespace(rest.charAt(i + 1)) || c == '\n' || "。！？".indexOf(c) >= 0 : all;
            if (closed && i + 1 >= Math.min(min, n)) return i + 1;
            if (closed && c == '\n' && i + 1 >= 12) return i + 1;
        }
        if (n > 300) {
            int sp = rest.lastIndexOf(' ', 300);
            return sp > 40 ? sp + 1 : 300;
        }
        return all ? n : 0;
    }

}
