package com.suvo.agent.core.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Turns the wake word the user typed ("tanu") into phrases an English speech model can listen
 * for. A name is usually not in the model's dictionary, so it is spelled with words that sound
 * the same: "tanu" → "tanu" (if known), "tanoo", "tan new", "tan nu", "ta new"… Only phrases made
 * of known words are kept (when the dictionary is known). Pure Java.
 */
public final class WakeVariants {

    private WakeVariants() {
    }

    /** The single wake word from the setting: first word, letters only, lower case ("" = none). */
    public static String single(String setting) {
        if (setting == null) return "";
        for (String part : setting.trim().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!part.isEmpty()) return part;
        }
        return "";
    }

    /** Sound-alike spellings of one piece ("nu" → nu, new, noo, knew, gnu). */
    static Set<String> spellings(String s) {
        Set<String> out = new LinkedHashSet<>();
        out.add(s);
        String[][] ends = {
                {"u", "oo"}, {"u", "ew"}, {"u", "ou"}, {"u", "ue"}, {"oo", "u"},
                {"i", "ee"}, {"i", "y"}, {"i", "ie"}, {"ee", "i"}, {"y", "ee"},
                {"a", "ah"}, {"a", "er"}, {"o", "oh"}, {"o", "ow"}, {"e", "ay"}, {"e", "ey"}};
        for (String[] e : ends) {
            if (s.endsWith(e[0]) && s.length() > e[0].length()) out.add(s.substring(0, s.length() - e[0].length()) + e[1]);
        }
        String[][] inner = {{"oo", "u"}, {"u", "oo"}, {"ph", "f"}, {"f", "ph"}, {"k", "c"}, {"c", "k"}, {"v", "w"}, {"w", "v"},
                {"z", "s"}, {"s", "z"}};
        for (String[] in : inner) {
            int i = s.indexOf(in[0]);
            if (i >= 0) out.add(s.substring(0, i) + in[1] + s.substring(i + in[0].length()));
        }
        if (s.equals("nu")) {
            out.add("new");
            out.add("knew");
            out.add("gnu");
        }
        return out;
    }

    /**
     * Phrases to listen for, best first. {@code vocab} empty = dictionary unknown (keep only the
     * whole-word spellings then, which the model may or may not know).
     */
    public static List<String> phrases(String word, Set<String> vocab, int max) {
        String w = single(word);
        List<String> out = new ArrayList<>();
        if (w.isEmpty()) return out;
        boolean known = vocab != null && !vocab.isEmpty();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : spellings(w)) if (!known || vocab.contains(s)) seen.add(s);
        if (known && w.length() >= 3) {
            // A doubled sound across the split: "tan" + "nu" also as "tan" + "new".
            for (int k = 2; k <= w.length() - 1; k++) {
                String left = w.substring(0, k), right = w.substring(k - 1);
                if (vocab.contains(left)) for (String r : spellings(right)) if (vocab.contains(r)) seen.add(left + " " + r);
            }
            // Two words that together sound like the name: "tan"+"new", "ta"+"new", "tan"+"nu".
            for (int k = 2; k <= w.length() - 2; k++) {
                for (String left : spellings(w.substring(0, k))) {
                    if (!vocab.contains(left)) continue;
                    for (String right : spellings(w.substring(k))) {
                        if (vocab.contains(right)) seen.add(left + " " + right);
                        if (seen.size() >= max * 2) break;
                    }
                }
            }
        }
        for (String s : seen) {
            if (out.size() >= max) break;
            out.add(s);
        }
        return out;
    }
}
