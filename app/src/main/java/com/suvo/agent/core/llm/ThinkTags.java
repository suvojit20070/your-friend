package com.suvo.agent.core.llm;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Some providers and proxies put the model's thinking into the answer text as
 * {@code <thought>…</thought>}, {@code <think>…</think>} or {@code <thinking>…</thinking>}
 * instead of a separate reasoning field. This moves those blocks out of the answer, so the chat
 * shows (and speaks, and sends back to the model) only the reply. A block that is still open while
 * streaming counts as thinking until it closes.
 */
public final class ThinkTags {

    private static final Pattern BLOCK = Pattern.compile("(?is)<(thought|think|thinking)>(.*?)</\\1>");
    private static final Pattern OPEN = Pattern.compile("(?is)^\\s*<(thought|think|thinking)>(.*)$");

    private ThinkTags() {
    }

    /** @return {reasoning, answer} */
    public static String[] split(String text) {
        if (text == null || text.isEmpty()) return new String[]{"", text == null ? "" : text};
        if (text.indexOf('<') < 0 || !text.toLowerCase(Locale.ROOT).contains("<th")) return new String[]{"", text};
        StringBuilder reasoning = new StringBuilder();
        Matcher m = BLOCK.matcher(text);
        StringBuffer rest = new StringBuffer();
        while (m.find()) {
            if (reasoning.length() > 0) reasoning.append("\n\n");
            reasoning.append(m.group(2).trim());
            m.appendReplacement(rest, "");
        }
        m.appendTail(rest);
        String answer = rest.toString();
        Matcher open = OPEN.matcher(answer);
        if (open.matches()) {
            // Still thinking (streaming) or the provider never closed the tag.
            if (reasoning.length() > 0) reasoning.append("\n\n");
            reasoning.append(open.group(2).trim());
            answer = "";
        }
        return new String[]{reasoning.toString(), answer.trim()};
    }

    /** Moves tagged thinking from {@code r.content} into {@code r.reasoning}. */
    public static void apply(ChatResponse r) {
        if (r == null) return;
        String[] s = split(r.content);
        if (s[0].isEmpty()) return;
        r.reasoning = r.reasoning == null || r.reasoning.isEmpty() ? s[0] : r.reasoning + "\n\n" + s[0];
        r.content = s[1];
    }
}
