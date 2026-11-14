package com.suvo.agent.core.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Which dance move comes next. Every ~30 seconds a new sequence of moves is planned — by the AI
 * from the song (name, artist, tempo), or here by tempo and loudness when the AI is off, offline
 * or answers nonsense. The moves of a sequence share the planning period; a change happens on a
 * bar (four beats) so it lands with the music. Pure Java (unit-tested).
 */
public final class DanceDirector {

    public static final long PERIOD_MS = 30_000;
    public static final float DEFAULT_BPM = 110f;

    private final Random rnd;
    private final List<Integer> plan = new ArrayList<>();
    private long planStart = -1;
    private int lastMove = -1;
    private float energy = 0.6f;
    private float bpm = DEFAULT_BPM;

    public DanceDirector(Random rnd) {
        this.rnd = rnd;
    }

    public float energy() {
        return energy;
    }

    public float bpm() {
        return bpm;
    }

    public void setBpm(float b) {
        if (b >= 60 && b <= 200) bpm = b;
    }

    /** True when a new plan should be asked for (none yet, or the period is over). */
    public boolean needsPlan(long now) {
        return planStart < 0 || now - planStart >= PERIOD_MS;
    }

    /** Uses this plan from {@code now} (empty = keep the old one). */
    public void setPlan(List<Integer> moves, float newEnergy, long now) {
        if (moves == null || moves.isEmpty()) return;
        plan.clear();
        plan.addAll(moves);
        if (newEnergy >= 0 && newEnergy <= 1) energy = newEnergy;
        planStart = now;
    }

    /** A plan without the AI: moves that fit the tempo and loudness, never the same twice in a row. */
    public List<Integer> fallbackPlan(float loudness01) {
        float target = Math.max(0f, Math.min(1f, (bpm - 75f) / 85f * 0.7f + loudness01 * 0.3f));
        energy = 0.35f + target * 0.6f;
        List<Integer> out = new ArrayList<>();
        int prev = lastMove;
        for (int k = 0; k < 4; k++) {
            int pick = -1;
            float bestScore = -1;
            for (int tries = 0; tries < 6; tries++) {
                int c = rnd.nextInt(DanceMoves.COUNT);
                if (c == prev || (c == DanceMoves.SPIN && rnd.nextFloat() < 0.7f)) continue;
                float sc = 1f - Math.abs(DanceMoves.ENERGY[c] - target) + rnd.nextFloat() * 0.3f;
                if (sc > bestScore) {
                    bestScore = sc;
                    pick = c;
                }
            }
            if (pick < 0) pick = (prev + 1 + rnd.nextInt(DanceMoves.COUNT - 1)) % DanceMoves.COUNT;
            out.add(pick);
            prev = pick;
        }
        return out;
    }

    /** The move for this moment: the plan is spread over the period, changes fall on a bar. */
    public int moveAt(long now, float beatsSinceStart) {
        if (plan.isEmpty()) return lastMove >= 0 ? lastMove : DanceMoves.HEAD_BOB;
        long since = Math.max(0, now - planStart);
        int idx = (int) Math.min(plan.size() - 1, since * plan.size() / PERIOD_MS);
        int want = plan.get(idx);
        // Only switch on the first beat of a bar.
        if (lastMove >= 0 && want != lastMove) {
            float inBar = beatsSinceStart % 4f;
            if (inBar > 0.25f) return lastMove;
        }
        lastMove = want;
        return want;
    }

    // ---- The AI -----------------------------------------------------------------------------

    public static String systemPrompt() {
        StringBuilder b = new StringBuilder();
        b.append("You choreograph a small anime companion who dances on the user's phone screen to the music that is playing. ")
                .append("Pick the next dance moves for about 30 seconds of this song, matching its mood, genre and tempo. ")
                .append("Moves (use these names only):\n");
        for (int i = 0; i < DanceMoves.COUNT; i++) b.append("- ").append(DanceMoves.NAMES[i]).append(": ").append(DanceMoves.DESCRIPTIONS[i]).append('\n');
        b.append("Answer with JSON only, no other text: {\"moves\":[3 to 6 move names in order],\"bpm\":the song's tempo as a number,")
                .append("\"energy\":0.0 to 1.0}. Vary the moves; do not repeat the previous plan exactly; use spin at most once.");
        return b.toString();
    }

    public static String userMessage(String title, String artist, String app, float measuredBpm, float loudness01, List<Integer> previous) {
        StringBuilder b = new StringBuilder();
        b.append("Song: ").append(empty(title) ? "unknown" : title.trim());
        if (!empty(artist)) b.append(" — ").append(artist.trim());
        if (!empty(app)) b.append(" (playing in ").append(app.trim()).append(')');
        b.append("\nMeasured tempo: ").append(measuredBpm > 0 ? String.format(Locale.ROOT, "%.0f BPM", measuredBpm) : "unknown (estimate it from the song)");
        b.append(String.format(Locale.ROOT, "\nLoudness: %.2f", loudness01));
        if (previous != null && !previous.isEmpty()) {
            b.append("\nPrevious plan: ");
            for (int i = 0; i < previous.size(); i++) {
                if (i > 0) b.append(", ");
                int m = previous.get(i);
                b.append(m >= 0 && m < DanceMoves.COUNT ? DanceMoves.NAMES[m] : "?");
            }
        }
        return b.toString();
    }

    /** The current plan (for "previous plan" in the next question). */
    public List<Integer> plan() {
        return new ArrayList<>(plan);
    }

    /** Parsed AI answer. */
    public static final class Answer {
        public final List<Integer> moves = new ArrayList<>();
        public float bpm = -1;
        public float energy = -1;
    }

    private static final Pattern MOVES = Pattern.compile("\"moves\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern BPM = Pattern.compile("\"bpm\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern ENERGY = Pattern.compile("\"energy\"\\s*:\\s*\"?([0-9]*\\.?[0-9]+)");

    /** Reads the AI's JSON (tolerates code fences and extra text). Unknown names are skipped. */
    public static Answer parse(String text) {
        Answer a = new Answer();
        if (text == null) return a;
        Matcher m = MOVES.matcher(text);
        if (m.find()) {
            Matcher n = NAME.matcher(m.group(1));
            while (n.find() && a.moves.size() < 8) {
                int id = DanceMoves.parse(n.group(1));
                if (id >= 0) a.moves.add(id);
            }
        }
        Matcher b = BPM.matcher(text);
        if (b.find()) {
            try {
                float v = Float.parseFloat(b.group(1));
                if (v >= 60 && v <= 200) a.bpm = v;
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher e = ENERGY.matcher(text);
        if (e.find()) {
            try {
                float v = Float.parseFloat(e.group(1));
                if (v >= 0 && v <= 1) a.energy = v;
            } catch (NumberFormatException ignored) {
            }
        }
        return a;
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
