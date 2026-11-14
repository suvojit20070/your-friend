package com.suvo.agent.core.character;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * A dance the AI wrote for one song: not a list of fixed steps but a recording — for every joint,
 * the angle it should have at which beat. The app only plays it back.
 *
 * <pre>
 * {"mood":"romantic","style":"slow bollywood","dance":true,"bpm":92,"face":"happy",
 *  "phrases":{"A":{"beats":8,"keys":[
 *      {"t":0,"w":-0.6,"hp":[-3,-1,0,-4],"la":[35,10,0],"le":[40,0]},
 *      {"t":0.6,"ra":[60,20,10],"re":[70,30],"hd":[5,-12,6]}, …]}},
 *  "order":["A","A","rest:2","B"]}
 * </pre>
 * A key lists only the joints that move then, so each joint has its own timing (one hand can
 * follow the other 300 ms later). Between a joint's keys the motion is eased. "rest:N" in the
 * order is N beats standing naturally (people pause when they dance). "dance":false means this is
 * not a song to dance to: {@link #say} is then a short line for the user instead.
 *
 * Joints (degrees unless noted; left / right are the character's own):
 * hd [pitch (+ down), yaw (+ her left), roll (+ toward her left shoulder)] · ch (chest) the same ·
 * hp (hips) [x cm (+ her left), y cm (− down), turn, roll] · la / ra (upper arm)
 * [raise 0 hanging … 90 straight out to the side … 170 up, fwd (+ forward, 90 = pointing at the
 * viewer, − back), in (+ across the body)] · le / re (elbow) [bend 0…150, turn (0 = forearm bends
 * forward, 90 = up, −90 = down)] · lh / rh (hand) [wrist bend, curl 0 open … 1 fist] ·
 * lf / rf (foot) [out cm (away from the other foot), fwd cm, lift cm] · w weight −1 (all on the
 * left foot) … +1 (right). Legs are not given as angles: the app keeps planted feet on the floor
 * and bends the knees itself.
 *
 * Everything is clamped to what a body can do. Pure Java (unit-tested), with its own small JSON
 * reader so a slightly broken answer (code fences, trailing commas) still loads.
 */
public final class DanceRecord {

    public static final int HD_P = 0, HD_Y = 1, HD_R = 2, CH_P = 3, CH_Y = 4, CH_R = 5, HP_X = 6, HP_Y = 7, HP_TURN = 8, HP_ROLL = 9,
            LA_RAISE = 10, LA_FWD = 11, LA_IN = 12, LE_BEND = 13, LE_TURN = 14, LH_WRIST = 15, LH_CURL = 16,
            RA_RAISE = 17, RA_FWD = 18, RA_IN = 19, RE_BEND = 20, RE_TURN = 21, RH_WRIST = 22, RH_CURL = 23,
            LF_OUT = 24, LF_FWD = 25, LF_LIFT = 26, RF_OUT = 27, RF_FWD = 28, RF_LIFT = 29, WEIGHT = 30;
    public static final int CHANNELS = 31;

    private static final float[] LO = new float[CHANNELS], HI = new float[CHANNELS], NEUTRAL = new float[CHANNELS];
    /** JSON joint name → {first channel, count}. */
    private static final Map<String, int[]> JOINTS = new HashMap<>();

    private static void lim(int ch, float lo, float hi, float neutral) {
        LO[ch] = lo;
        HI[ch] = hi;
        NEUTRAL[ch] = neutral;
    }

    static {
        lim(HD_P, -35, 40, 0);
        lim(HD_Y, -70, 70, 0);
        lim(HD_R, -35, 35, 0);
        lim(CH_P, -20, 35, 0);
        lim(CH_Y, -40, 40, 0);
        lim(CH_R, -25, 25, 0);
        lim(HP_X, -18, 18, 0);
        lim(HP_Y, -30, 4, 0);
        lim(HP_TURN, -70, 70, 0);
        lim(HP_ROLL, -18, 18, 0);
        for (int base : new int[]{LA_RAISE, RA_RAISE}) {
            lim(base, 0, 175, 8);          // raise
            lim(base + 1, -50, 120, 0);    // fwd
            lim(base + 2, -20, 80, 0);     // in
            lim(base + 3, 0, 150, 15);     // elbow bend
            lim(base + 4, -90, 100, 0);    // elbow turn
            lim(base + 5, -60, 60, 0);     // wrist
            lim(base + 6, 0, 1, 0.3f);     // curl
        }
        for (int base : new int[]{LF_OUT, RF_OUT}) {
            lim(base, -12, 40, 0);         // out
            lim(base + 1, -35, 45, 0);     // fwd
            lim(base + 2, 0, 45, 0);       // lift
        }
        lim(WEIGHT, -1, 1, 0);
        JOINTS.put("hd", new int[]{HD_P, 3});
        JOINTS.put("ch", new int[]{CH_P, 3});
        JOINTS.put("hp", new int[]{HP_X, 4});
        JOINTS.put("la", new int[]{LA_RAISE, 3});
        JOINTS.put("le", new int[]{LE_BEND, 2});
        JOINTS.put("lh", new int[]{LH_WRIST, 2});
        JOINTS.put("ra", new int[]{RA_RAISE, 3});
        JOINTS.put("re", new int[]{RE_BEND, 2});
        JOINTS.put("rh", new int[]{RH_WRIST, 2});
        JOINTS.put("lf", new int[]{LF_OUT, 3});
        JOINTS.put("rf", new int[]{RF_OUT, 3});
        JOINTS.put("w", new int[]{WEIGHT, 1});
    }

    public static float neutral(int ch) {
        return NEUTRAL[ch];
    }

    /** What the AI is told about the format (kept next to the parser so they cannot drift apart). */
    public static String formatHelp() {
        return "FORMAT (JSON only, minified, no comments, no other text):\n"
                + "{\"mood\":\"…\",\"style\":\"…\",\"dance\":true,\"bpm\":number,\"face\":\"happy|neutral|shy|sad|surprised\",\"say\":\"\","
                + "\"phrases\":{\"A\":{\"beats\":8,\"keys\":[{\"t\":0,…joints…},{\"t\":0.5,…}]},\"B\":{…}},"
                + "\"order\":[\"A\",\"A\",\"rest:2\",\"B\",\"A\"]}\n"
                + "- t is the beat inside the phrase (0 ≤ t < beats, fractions allowed: at 100 BPM 0.5 beat = 300 ms).\n"
                + "- A key lists ONLY the joints that reach a new pose at that moment; each joint moves smoothly between its own keys, "
                + "so give joints different times (one hand follows the other a little later, the head arrives last). "
                + "Add \"e\":\"snap\" to a key for a sharp accent, \"e\":\"lin\" for a steady move.\n"
                + "- \"rest:N\" in order = N beats standing naturally (people pause, breathe, smile between moves). Use rests.\n"
                + "JOINTS (degrees unless noted; left/right are HER own left/right; she faces the viewer):\n"
                + "hd [pitch +down −up (−35…40), yaw +her left (−70…70), roll +tilt to her left shoulder (−35…35)]\n"
                + "ch chest [pitch +lean forward (−20…35), yaw (−40…40), roll (−25…25)]\n"
                + "hp hips [x cm +her left (−18…18), y cm −down (−30…4), turn (−70…70), roll (−18…18)]\n"
                + "la / ra upper arm [raise 0 hanging · 90 straight out to the side · 170 up (0…175), fwd +forward, 90 = pointing at the viewer, "
                + "−back (−50…120), in +across the body toward the chest (−20…80)]\n"
                + "le / re elbow [bend 0 straight … 150, turn 0 = forearm bends forward · 90 = forearm up · −90 = down (−90…100)]\n"
                + "lh / rh hand [wrist bend (−60…60), curl 0 open hand … 1 fist]\n"
                + "lf / rf foot [out cm away from the other foot (−12…40), fwd cm (−35…45), lift cm off the floor (0…45)]\n"
                + "w weight −1 all on her left foot … 0 both … +1 all on her right foot\n"
                + "LEGS: never give leg or knee angles. Feet with lift 0 stay planted on the floor; the knees bend by themselves when the hips "
                + "go down (hp y) or a foot moves. To step: shift the weight to the other foot first (w), lift the foot (lift 6–15), "
                + "move it (out / fwd), put it down (lift 0), then shift the weight. A lifted foot cannot carry weight.";
    }

    // ---- The record ------------------------------------------------------------------------------

    public String mood = "", style = "", face = "", say = "";
    public boolean dance = true;
    public float bpm = -1;
    /** Beats of the whole sequence (it loops). */
    public float totalBeats;

    private final float[][] keyBeat = new float[CHANNELS][];
    private final float[][] keyValue = new float[CHANNELS][];
    private final byte[][] keyEase = new byte[CHANNELS][];
    private final List<float[]> rests = new ArrayList<>(); // {from, to} in beats
    private int keyCount;

    private DanceRecord() {
    }

    /** True when there is something to play. */
    public boolean playable() {
        return dance && totalBeats > 0 && keyCount > 0;
    }

    public int keyCount() {
        return keyCount;
    }

    private static final byte SMOOTH = 0, LINEAR = 1, SNAP = 2;

    /**
     * The pose at {@code beat} (any value; the sequence loops) into {@code out}
     * ({@link #CHANNELS} values). Returns how much of it applies: 1 while dancing, falling to 0
     * during a rest (the caller blends to the normal standing animation).
     */
    public float sample(float beat, float[] out) {
        if (totalBeats <= 0) {
            System.arraycopy(NEUTRAL, 0, out, 0, CHANNELS);
            return 0f;
        }
        float b = beat % totalBeats;
        if (b < 0) b += totalBeats;
        for (int c = 0; c < CHANNELS; c++) {
            float[] kb = keyBeat[c];
            if (kb == null || kb.length == 0) {
                out[c] = NEUTRAL[c];
                continue;
            }
            int n = kb.length;
            if (n == 1) {
                out[c] = keyValue[c][0];
                continue;
            }
            // The key at or before b (wrapping to the last key of the loop).
            int lo = 0, hi = n - 1, i = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (kb[mid] <= b) {
                    i = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            int a = i < 0 ? n - 1 : i, z = (a + 1) % n;
            float ta = kb[a], tz = kb[z];
            if (i < 0) ta -= totalBeats;
            if (z <= a && i >= 0) tz += totalBeats;
            float span = tz - ta;
            float f = span <= 1e-4f ? 1f : (b - ta) / span;
            f = Math.max(0f, Math.min(1f, f));
            byte e = keyEase[c][z];
            if (e == SMOOTH) f = f * f * (3 - 2 * f);
            else if (e == SNAP) {
                // Most of the way in the last third of the gap: a hit on the beat.
                float s = Math.max(0f, (f - 0.66f) / 0.34f);
                f = s * s * (3 - 2 * s);
            }
            out[c] = keyValue[c][a] + (keyValue[c][z] - keyValue[c][a]) * f;
        }
        float amount = 1f;
        for (float[] r : rests) {
            // Half a beat to settle into the rest and half a beat to come out of it.
            float in = (b - r[0]) / 0.5f, outT = (r[1] - b) / 0.5f;
            if (in > 0 && outT > 0) amount = Math.min(amount, 1f - Math.min(1f, Math.min(in, outT)));
        }
        return amount;
    }

    // ---- Reading the AI's answer ------------------------------------------------------------------

    /** Parses the AI's JSON; null when it is not usable at all. */
    @SuppressWarnings("unchecked")
    public static DanceRecord parse(String text) {
        if (text == null) return null;
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        Object root;
        try {
            root = new Json(text.substring(start, end + 1)).value();
        } catch (RuntimeException e) {
            return null;
        }
        if (!(root instanceof Map)) return null;
        Map<String, Object> o = (Map<String, Object>) root;
        DanceRecord r = new DanceRecord();
        r.mood = str(o.get("mood"));
        r.style = str(o.get("style"));
        r.face = str(o.get("face")).toLowerCase(Locale.ROOT);
        r.say = str(o.get("say"));
        Object d = o.get("dance");
        r.dance = !(Boolean.FALSE.equals(d) || "false".equalsIgnoreCase(String.valueOf(d)));
        float bpm = num(o.get("bpm"), -1);
        if (bpm >= 50 && bpm <= 210) r.bpm = bpm;
        if (!r.dance) return r;

        Object ph = o.get("phrases");
        if (!(ph instanceof Map)) return r.say.isEmpty() ? null : r;
        Map<String, Object> phrases = (Map<String, Object>) ph;
        List<String> order = new ArrayList<>();
        Object ord = o.get("order");
        if (ord instanceof List) for (Object x : (List<Object>) ord) order.add(str(x));
        if (order.isEmpty()) order.addAll(phrases.keySet());

        List<List<float[]>> tracks = new ArrayList<>(); // per channel: {beat, value, ease}
        for (int c = 0; c < CHANNELS; c++) tracks.add(new ArrayList<>());
        float at = 0f;
        for (String name : order) {
            if (at > 512) break;
            String n = name.trim();
            if (n.toLowerCase(Locale.ROOT).startsWith("rest")) {
                float beats = 2f;
                int colon = n.indexOf(':');
                if (colon > 0) {
                    try {
                        beats = Float.parseFloat(n.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                beats = Math.max(1f, Math.min(16f, beats));
                r.rests.add(new float[]{at, at + beats});
                // Everything settles to the natural pose shortly after the rest begins …
                // … and stays there until just before the next phrase.
                float settle = Math.min(0.75f, beats / 2f), leave = Math.max(settle, beats - 0.5f);
                for (int c = 0; c < CHANNELS; c++) {
                    tracks.get(c).add(new float[]{at + settle, NEUTRAL[c], SMOOTH});
                    if (leave > settle) tracks.get(c).add(new float[]{at + leave, NEUTRAL[c], SMOOTH});
                }
                at += beats;
                continue;
            }
            Object po = phrases.get(n);
            if (!(po instanceof Map)) continue;
            Map<String, Object> p = (Map<String, Object>) po;
            float beats = Math.max(1f, Math.min(64f, num(p.get("beats"), 8f)));
            Object ko = p.get("keys");
            if (ko instanceof List) {
                for (Object k : (List<Object>) ko) {
                    if (!(k instanceof Map)) continue;
                    Map<String, Object> key = (Map<String, Object>) k;
                    float t = num(key.get("t"), -1);
                    if (t < 0 || t >= beats) continue;
                    String es = str(key.get("e")).toLowerCase(Locale.ROOT);
                    byte ease = es.startsWith("snap") ? SNAP : es.startsWith("lin") ? LINEAR : SMOOTH;
                    for (Map.Entry<String, Object> en : key.entrySet()) {
                        int[] j = JOINTS.get(en.getKey().toLowerCase(Locale.ROOT));
                        if (j == null) continue;
                        Object v = en.getValue();
                        for (int i = 0; i < j[1]; i++) {
                            Object item = v instanceof List ? (i < ((List<Object>) v).size() ? ((List<Object>) v).get(i) : null) : (i == 0 ? v : null);
                            if (item == null) continue;
                            float val = num(item, Float.NaN);
                            if (Float.isNaN(val)) continue;
                            int ch = j[0] + i;
                            tracks.get(ch).add(new float[]{at + t, Math.max(LO[ch], Math.min(HI[ch], val)), ease});
                            r.keyCount++;
                        }
                    }
                }
            }
            at += beats;
        }
        r.totalBeats = at;
        for (int c = 0; c < CHANNELS; c++) {
            List<float[]> tr = tracks.get(c);
            tr.sort((x, y) -> Float.compare(x[0], y[0]));
            // A channel only ever touched by rests is not a channel.
            boolean real = false;
            for (float[] k : tr) if (k[1] != NEUTRAL[c]) real = true;
            if (!real) continue;
            r.keyBeat[c] = new float[tr.size()];
            r.keyValue[c] = new float[tr.size()];
            r.keyEase[c] = new byte[tr.size()];
            for (int i = 0; i < tr.size(); i++) {
                r.keyBeat[c][i] = tr.get(i)[0];
                r.keyValue[c][i] = tr.get(i)[1];
                r.keyEase[c][i] = (byte) tr.get(i)[2];
            }
        }
        r.settleFeet();
        return r;
    }

    /** A lifted foot cannot carry the weight: where both say otherwise, the weight moves off it. */
    private void settleFeet() {
        if (keyBeat[WEIGHT] == null) return;
        float[] pose = new float[CHANNELS];
        for (int i = 0; i < keyBeat[WEIGHT].length; i++) {
            sample(keyBeat[WEIGHT][i], pose);
            float w = keyValue[WEIGHT][i];
            if (pose[LF_LIFT] > 2f && w < 0.4f) w = 0.8f;
            if (pose[RF_LIFT] > 2f && w > -0.4f) w = -0.8f;
            keyValue[WEIGHT][i] = w;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static float num(Object o, float def) {
        if (o instanceof Number) return ((Number) o).floatValue();
        if (o instanceof String) {
            try {
                return Float.parseFloat(((String) o).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** A small, forgiving JSON reader (objects → LinkedHashMap, arrays → ArrayList, numbers → Double). */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalStateException("end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"' || c == '\'') return string();
            int st = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            String tok = s.substring(st, i);
            if (tok.equals("true")) return Boolean.TRUE;
            if (tok.equals("false")) return Boolean.FALSE;
            if (tok.equals("null") || tok.isEmpty()) return null;
            try {
                return Double.parseDouble(tok.startsWith("+") ? tok.substring(1) : tok);
            } catch (NumberFormatException e) {
                return tok;
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            while (true) {
                ws();
                if (i >= s.length()) return m;
                char c = s.charAt(i);
                if (c == '}') {
                    i++;
                    return m;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                String k = c == '"' || c == '\'' ? string() : bare();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
            }
        }

        private String bare() {
            int st = i;
            while (i < s.length() && ":,}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            if (i == st) throw new IllegalStateException("key");
            return s.substring(st, i);
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            while (true) {
                ws();
                if (i >= s.length()) return l;
                char c = s.charAt(i);
                if (c == ']') {
                    i++;
                    return l;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                l.add(value());
            }
        }

        private String string() {
            char q = s.charAt(i++);
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == q) break;
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                try {
                                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                } catch (NumberFormatException ignored) {
                                }
                                i += 4;
                            }
                            break;
                        default:
                            b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }
    }
}
