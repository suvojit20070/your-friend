package com.suvo.agent.core.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Decides when you start and stop talking, from a continuous microphone stream (16 kHz mono,
 * 20 ms frames). Pure Java, no model:
 * <ul>
 *   <li><b>Adapts to the room.</b> A noise floor follows the background (fan, traffic): speech is
 *       sound clearly above it, not above a fixed level.</li>
 *   <li><b>Start.</b> Needs a short run of speech frames (a click or a cough is ignored); the
 *       segment keeps a little audio from before, so the first syllable is not cut.</li>
 *   <li><b>End — learns your pauses.</b> Pauses inside your sentences are measured; the wait
 *       before "you are done" follows them (a slow, thinking speaker gets more time, a quick one
 *       gets a faster reply). Short phrases get a bit more time ("call… papa").</li>
 *   <li><b>While the companion talks</b> ({@link #setEchoGuard}) the bar is higher and a longer
 *       run is needed, so leftover sound from the speaker is not taken for you, while you can still
 *       talk over it.</li>
 * </ul>
 */
public final class SmartVad {

    public interface Listener {
        /** Speech started (for "talk over the companion" and UI). */
        void onSpeechStart();

        /** A finished utterance, with a bit of lead-in audio. */
        void onSegment(float[] samples);

        /** Frame loudness in dB (about -90 … 0), for animations. */
        default void onLevel(float db) {
        }
    }

    public static final int RATE = 16000;
    public static final int FRAME = 320; // 20 ms

    private static final int PRE_ROLL_FRAMES = 15;          // 300 ms kept before the start
    private static final int START_FRAMES = 5;              // 100 ms of speech to start
    private static final int START_FRAMES_GUARDED = 14;     // 280 ms while the companion talks
    private static final float MARGIN_DB = 9f;
    private static final float MARGIN_GUARDED_DB = 15f;
    private static final float ABS_MIN_DB = -52f;
    private static final int MIN_SPEECH_FRAMES = 12;        // < 240 ms of speech = noise, dropped
    private static final int MAX_SEGMENT_FRAMES = 20 * 50;  // 20 s, then it is cut anyway
    private static final int HANG_MIN_MS = 500, HANG_MAX_MS = 1400, HANG_DEFAULT_MS = 800;

    private final Listener listener;
    private float noise = -60f;
    private boolean noiseKnown;
    private boolean speaking;
    private boolean echoGuard;
    private int runSpeech;
    private int runSilence;
    private int speechFrames;
    private final ArrayDeque<float[]> pre = new ArrayDeque<>();
    private final List<float[]> seg = new ArrayList<>();
    /** Pauses (ms) inside utterances that were followed by more speech: the user's own rhythm. */
    private final ArrayDeque<Integer> pauses = new ArrayDeque<>();
    private int pendingPauseFrames;

    public SmartVad(Listener listener) {
        this.listener = listener;
    }

    /** True while the companion is talking (stricter start). */
    public void setEchoGuard(boolean on) {
        echoGuard = on;
    }

    public boolean isSpeaking() {
        return speaking;
    }

    /** Drops what is buffered (e.g. the call was muted). */
    public void reset() {
        speaking = false;
        runSpeech = runSilence = speechFrames = 0;
        pre.clear();
        seg.clear();
        pendingPauseFrames = 0;
    }

    /** How long a silence ends an utterance now (ms). */
    public int hangoverMs() {
        int base;
        if (pauses.size() < 3) {
            base = HANG_DEFAULT_MS;
        } else {
            int[] p = new int[pauses.size()];
            int i = 0;
            for (int v : pauses) p[i++] = v;
            Arrays.sort(p);
            int median = p[p.length / 2];
            // Comfortably longer than the pauses you usually make inside a sentence.
            base = Math.round(median * 1.6f + 250);
        }
        // Short phrases ("call… papa") get extra time: people pause after the first word.
        if (speechFrames < 50) base += 250;
        return Math.max(HANG_MIN_MS, Math.min(HANG_MAX_MS, base));
    }

    /** Feeds one 20 ms frame ({@link #FRAME} samples). */
    public void accept(float[] frame) {
        double sum = 0;
        for (float v : frame) sum += v * v;
        float db = (float) (10 * Math.log10(sum / frame.length + 1e-10));
        listener.onLevel(db);

        if (!noiseKnown) {
            noise = db;
            noiseKnown = true;
        }
        float margin = echoGuard ? MARGIN_GUARDED_DB : MARGIN_DB;
        float threshold = Math.max(ABS_MIN_DB, noise + margin);
        boolean voiced = db > threshold;

        // The floor follows the background only while nobody talks (falls fast, rises slowly).
        if (!speaking && !voiced) {
            if (db < noise) noise = noise * 0.7f + db * 0.3f;
            else noise = noise * 0.98f + db * 0.02f;
        }

        if (!speaking) {
            pre.addLast(frame);
            while (pre.size() > PRE_ROLL_FRAMES) pre.removeFirst();
            runSpeech = voiced ? runSpeech + 1 : 0;
            if (runSpeech >= (echoGuard ? START_FRAMES_GUARDED : START_FRAMES)) {
                speaking = true;
                seg.clear();
                seg.addAll(pre);
                pre.clear();
                speechFrames = runSpeech;
                runSilence = 0;
                pendingPauseFrames = 0;
                listener.onSpeechStart();
            }
            return;
        }

        seg.add(frame);
        if (voiced) {
            if (runSilence >= 8) {
                // A pause inside the utterance (160 ms+), and speech came back: learn it.
                pendingPauseFrames = runSilence;
                pauses.addLast(pendingPauseFrames * 20);
                while (pauses.size() > 24) pauses.removeFirst();
            }
            runSilence = 0;
            speechFrames++;
        } else {
            runSilence++;
        }
        boolean ended = runSilence * 20 >= hangoverMs();
        if (ended || seg.size() >= MAX_SEGMENT_FRAMES) finish();
    }

    private void finish() {
        speaking = false;
        runSpeech = 0;
        // Trailing silence beyond 200 ms is not needed by the recognizer.
        int keep = seg.size() - Math.max(0, runSilence - 10);
        int frames = Math.max(0, keep);
        boolean real = speechFrames >= MIN_SPEECH_FRAMES;
        float[] out = null;
        if (real && frames > 0) {
            out = new float[frames * FRAME];
            for (int i = 0; i < frames; i++) System.arraycopy(seg.get(i), 0, out, i * FRAME, FRAME);
        }
        seg.clear();
        runSilence = 0;
        speechFrames = 0;
        if (out != null) listener.onSegment(out);
    }
}
