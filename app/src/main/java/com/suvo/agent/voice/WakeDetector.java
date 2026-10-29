package com.suvo.agent.voice;

import android.content.Context;

import com.suvo.agent.core.util.WakeVariants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Offline wake-word spotting with Vosk (native C++ Kaldi engine): the recognizer is limited to a
 * grammar of only the wake word's sound-alike phrases plus "[unk]" (everything else), so it does
 * not transcribe what you say — it only answers "was that the wake word?". Works on quiet speech,
 * keeps the microphone without audio focus (music keeps playing), costs little CPU.
 *
 * Fed 16 kHz mono PCM on the recording thread. {@code minConfidence} comes from the sensitivity
 * setting (higher sensitivity = lower confidence needed = more triggers, also false ones).
 */
public final class WakeDetector {

    private final org.vosk.Recognizer rec;
    private final List<String> phrases;
    private final float minConfidence;
    public final String word;

    private WakeDetector(org.vosk.Recognizer rec, String word, List<String> phrases, float minConfidence) {
        this.rec = rec;
        this.word = word;
        this.phrases = phrases;
        this.minConfidence = minConfidence;
    }

    /** Sensitivity 0..100 → confidence the match needs (0 → 0.92, 50 → 0.62, 100 → 0.32). */
    public static float confidenceFor(int sensitivity) {
        int s = Math.max(0, Math.min(100, sensitivity));
        return 0.92f - s * 0.006f;
    }

    /** Builds a detector for {@code setting}'s word; null when there is no word. Loads the model (slow, off the main thread). */
    public static WakeDetector create(Context c, String setting, int sensitivity) throws IOException {
        String word = WakeVariants.single(setting);
        if (word.isEmpty()) return null;
        List<String> phrases = WakeVariants.phrases(word, WakeModel.vocabulary(c), 8);
        if (phrases.isEmpty()) phrases = Arrays.asList(word);
        JSONArray grammar = new JSONArray();
        for (String p : phrases) grammar.put(p);
        grammar.put("[unk]");
        org.vosk.Recognizer r = new org.vosk.Recognizer(WakeModel.model(c), 16000f, grammar.toString());
        r.setWords(true);
        r.setMaxAlternatives(0);
        return new WakeDetector(r, word, phrases, confidenceFor(sensitivity));
    }

    /** The phrases listened for (shown in settings). */
    public List<String> phrases() {
        return phrases;
    }

    /** Feeds one frame; true when the wake word was just heard. */
    public boolean feed(short[] pcm, int n) {
        try {
            if (rec.acceptWaveForm(pcm, n)) return matches(rec.getResult());
        } catch (RuntimeException ignored) {
            // A bad frame: skip it.
        }
        return false;
    }

    /** Forgets what was heard so far (after a trigger, or after a pause). */
    public void reset() {
        try {
            rec.reset();
        } catch (RuntimeException ignored) {
        }
    }

    public void close() {
        try {
            rec.close();
        } catch (RuntimeException ignored) {
        }
    }

    /** The result has one of the phrases, with enough confidence on its words. */
    boolean matches(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String text = o.optString("text", "").trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) return false;
            JSONArray words = o.optJSONArray("result");
            for (String p : phrases) {
                int at = (" " + text + " ").indexOf(" " + p + " ");
                if (at < 0) continue;
                if (words == null) return true;
                // Average confidence of the words that make up the phrase.
                String[] parts = p.split(" ");
                double sum = 0;
                int n = 0;
                for (int i = 0; i + parts.length <= words.length(); i++) {
                    boolean same = true;
                    for (int k = 0; k < parts.length && same; k++) {
                        same = parts[k].equals(words.getJSONObject(i + k).optString("word"));
                    }
                    if (!same) continue;
                    for (int k = 0; k < parts.length; k++) sum += words.getJSONObject(i + k).optDouble("conf", 1);
                    n = parts.length;
                    break;
                }
                if (n == 0 || sum / n >= minConfidence) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
