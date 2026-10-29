package com.suvo.agent.core.util;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.ToolCall;

/**
 * Your Friend — Original project by Suvo.
 *
 * The app's own token count, so usage is the same for every provider (many OpenAI-compatible
 * proxies send no usage, or wrong numbers). It approximates common BPE tokenizers:
 * <ul>
 *   <li>Latin words: one token per word up to 4 letters, then about one per 4 letters;</li>
 *   <li>digits: one token per 3 digits; punctuation and symbols: one each;</li>
 *   <li>CJK: one per character; other scripts (Devanagari, Bengali, Arabic, Cyrillic…): about
 *       one per 2 characters;</li>
 *   <li>per message a small overhead (role and separators); per image a flat cost.</li>
 * </ul>
 * Pure Java.
 */
public final class TokenCount {

    /** Tokens counted for one attached image (a mid-size image at "auto" detail). */
    public static final int IMAGE_TOKENS = 765;
    private static final int PER_MESSAGE = 4;

    private TokenCount() {
    }

    /** Estimated tokens of a piece of text. */
    public static int of(CharSequence s) {
        if (s == null || s.length() == 0) return 0;
        int n = s.length();
        int tokens = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c < 128 && Character.isLetter(c)) {
                int start = i;
                while (i < n && s.charAt(i) < 128 && Character.isLetter(s.charAt(i))) i++;
                int len = i - start;
                tokens += len <= 4 ? 1 : (len + 3) / 4;
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(s.charAt(i))) i++;
                tokens += (i - start + 2) / 3;
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < n) {
                tokens += 2; // emoji and other astral symbols
                i += 2;
                continue;
            }
            if (c >= 128 && (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK
                    || Character.getType(c) == Character.COMBINING_SPACING_MARK)) {
                Character.UnicodeScript sc = Character.UnicodeScript.of(c);
                if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                        || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL) {
                    tokens++;
                    i++;
                    continue;
                }
                int start = i;
                while (i < n && s.charAt(i) >= 128 && !Character.isWhitespace(s.charAt(i))
                        && (Character.isLetter(s.charAt(i)) || Character.getType(s.charAt(i)) == Character.NON_SPACING_MARK
                        || Character.getType(s.charAt(i)) == Character.COMBINING_SPACING_MARK)) i++;
                tokens += (i - start + 1) / 2;
                continue;
            }
            tokens++; // punctuation, symbols
            i++;
        }
        return tokens;
    }

    /** Tokens the request sends: every message, its images and the tool definitions. */
    public static int of(ChatRequest req) {
        if (req == null) return 0;
        int t = 3;
        for (ChatMessage m : req.messages) t += of(m);
        if (req.tools != null && req.tools.length() > 0) t += of(req.tools.toString());
        return t;
    }

    public static int of(ChatMessage m) {
        if (m == null) return 0;
        int t = PER_MESSAGE + of(m.content);
        t += m.images.size() * IMAGE_TOKENS;
        for (ToolCall c : m.toolCalls) t += 3 + of(c.name) + of(c.argumentsJson);
        return t;
    }

    /** Tokens the model produced: the answer, its reasoning and its tool calls. */
    public static int of(ChatResponse r) {
        if (r == null) return 0;
        int t = of(r.content) + of(r.reasoning);
        for (ToolCall c : r.toolCalls) t += 3 + of(c.name) + of(c.argumentsJson);
        return Math.max(t, r.content.isEmpty() && r.toolCalls.isEmpty() ? 0 : 1);
    }
}
