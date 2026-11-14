package com.suvo.agent.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SpeakableTextTest {

    /** Feeds text in small deltas the way Talk does and returns the pieces that would be said. */
    private static List<String> stream(String full, int step) {
        List<String> out = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        int consumed = 0;
        for (int i = step; ; i += step) {
            int upto = Math.min(full.length(), i);
            raw.setLength(0);
            raw.append(full, 0, upto);
            boolean all = upto >= full.length();
            String visible = SpeakableText.speakable(raw.toString());
            while (consumed < visible.length()) {
                String rest = visible.substring(consumed);
                int cut = SpeakableText.cutPoint(rest, out.isEmpty() ? 40 : 120, all);
                if (cut <= 0) break;
                String piece = rest.substring(0, cut);
                consumed += cut;
                if (!SpeakableText.looksLikeMarkup(piece)) out.add(piece.trim());
            }
            if (all) break;
        }
        return out;
    }

    @Test
    public void firstSentenceGoesEarly() {
        String text = "Sure, I checked the weather for you today. It is sunny and 31 degrees in Kolkata, with a light breeze in the evening. Carry water.";
        List<String> p = stream(text, 3);
        assertTrue(p.size() >= 2);
        assertEquals("Sure, I checked the weather for you today.", p.get(0));
        assertEquals(text, String.join(" ", p));
    }

    @Test
    public void decimalsAreNotSentenceEnds() {
        List<String> p = stream("The version is 3.5 and it costs about 2.99 dollars per month for everyone here.", 2);
        assertEquals(1, p.size());
    }

    @Test
    public void codeAndThinkingAreNeverSaid() {
        String text = "<think>let me plan this</think>Here is the script you asked for, it is short.\n```python\nprint('hi. there')\n```\nRun it with python3.";
        String all = String.join(" ", stream(text, 4));
        assertFalse(all.contains("print"));
        assertFalse(all.contains("plan"));
        assertTrue(all.contains("Run it with python3."));
    }

    @Test
    public void toolCallTextIsDropped() {
        String text = "Okay, searching now. <tool_call>{\"name\": \"web_search\", \"arguments\": {\"q\": \"x\"}}</tool_call>";
        String all = String.join(" ", stream(text, 5));
        assertEquals("Okay, searching now.", all.trim());
        assertTrue(SpeakableText.looksLikeMarkup("{\"name\": \"web_search\"}"));
    }

    @Test
    public void longRunWithoutPunctuationIsCut() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 80; i++) b.append("word").append(i).append(' ');
        List<String> p = stream(b.toString(), 7);
        assertTrue(p.size() >= 2);
        for (String s : p) assertTrue(s.length() <= 301);
    }

    @Test
    public void hindiDandaEndsSentence() {
        List<String> p = stream("हाँ, मैंने आज का पूरा मौसम ध्यान से देख लिया है अभी। बाहर धूप है और काफी गर्मी भी है आज।", 3);
        assertEquals(2, p.size());
    }
}
