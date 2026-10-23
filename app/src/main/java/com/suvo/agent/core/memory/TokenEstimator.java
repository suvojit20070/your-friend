package com.suvo.agent.core.memory;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.util.TextUtil;

import java.util.List;

/**
 * Conservative token estimate without a tokenizer. Latin text ≈ 4 chars/token; CJK and Indic
 * scripts are closer to 1–2 chars/token, so they are counted heavier to avoid overflowing.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        double tokens = 0;
        int latinRun = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x0250) {
                latinRun++;
            } else {
                tokens += latinRun / 3.6;
                latinRun = 0;
                if (TextUtil.isCjk(cp)) tokens += 1.0;
                else tokens += 0.75; // Devanagari, Bengali, other scripts
            }
        }
        tokens += latinRun / 3.6;
        return (int) Math.ceil(tokens) + 1;
    }

    public static int estimate(ChatMessage m) {
        int t = 4 + estimate(m.content);
        for (ToolCall c : m.toolCalls) t += 8 + estimate(c.name) + estimate(c.argumentsJson);
        t += m.images.size() * 800;
        return t;
    }

    public static int estimate(List<ChatMessage> msgs) {
        int t = 3;
        for (ChatMessage m : msgs) t += estimate(m);
        return t;
    }
}
