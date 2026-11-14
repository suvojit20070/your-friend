package com.suvo.agent.core.audio;

import java.util.ArrayDeque;

/**
 * Your Friend — Original project by Suvo.
 *
 * Finds the tempo (BPM) and where the beats fall from a stream of loudness readings of the music
 * (e.g. bass energy from the system's audio visualizer, ~10–50 readings a second, not evenly
 * spaced). A reading that jumps above the recent level is an onset (a kick, a clap); onsets are
 * folded onto each candidate beat length and the length where they line up best wins. Octave
 * errors (half / double tempo) are pulled toward the usual dance range 85–160 BPM.
 * Pure Java (unit-tested).
 */
public final class BeatTracker {

    private static final long WINDOW_MS = 10_000;
    private static final int MIN_BPM = 70, MAX_BPM = 180;

    private final ArrayDeque<long[]> onsets = new ArrayDeque<>(); // {timeMs, strength*1000}
    private float slow = -1f;
    private float level;
    private float bpm;
    private float confidence;
    private long anchorMs = -1;
    private long lastEstimateMs;

    /** Feeds one loudness reading (any positive scale) taken at {@code timeMs}. */
    public void accept(float energy, long timeMs) {
        if (energy < 0 || Float.isNaN(energy)) return;
        if (slow < 0) slow = energy;
        float onset = energy - slow * 1.15f;
        slow = slow * 0.85f + energy * 0.15f;
        level = level * 0.95f + energy * 0.05f;
        if (onset > 0) onsets.addLast(new long[]{timeMs, Math.round(onset * 1000f)});
        while (!onsets.isEmpty() && timeMs - onsets.peekFirst()[0] > WINDOW_MS) onsets.removeFirst();
        if (timeMs - lastEstimateMs >= 1000) {
            lastEstimateMs = timeMs;
            estimate();
        }
    }

    /** Tempo in BPM, 0 while unknown. */
    public float bpm() {
        return confidence >= 0.4f ? bpm : 0f;
    }

    /** 0..1: how regular the beat is. */
    public float confidence() {
        return confidence;
    }

    /** A time (ms, same clock as {@link #accept}) when a beat fell, or -1. */
    public long beatAnchorMs() {
        return confidence >= 0.4f ? anchorMs : -1;
    }

    /** How loud the music is lately (same scale as the readings). */
    public float level() {
        return level;
    }

    public void reset() {
        onsets.clear();
        slow = -1f;
        bpm = 0f;
        confidence = 0f;
        anchorMs = -1;
    }

    private float[] score(float periodMs) {
        double re = 0, im = 0, total = 0;
        long ref = onsets.peekFirst()[0];
        for (long[] o : onsets) {
            double a = 2 * Math.PI * (o[0] - ref) / periodMs;
            re += o[1] * Math.cos(a);
            im += o[1] * Math.sin(a);
            total += o[1];
        }
        if (total <= 0) return new float[]{0f, 0f};
        return new float[]{(float) (Math.hypot(re, im) / total), (float) Math.atan2(im, re)};
    }

    private void estimate() {
        if (onsets.size() < 8) {
            confidence = 0f;
            return;
        }
        float best = 0f, bestBpm = 0f, bestPhase = 0f;
        for (float b = MIN_BPM; b <= MAX_BPM; b += 0.5f) {
            float[] s = score(60_000f / b);
            // A mild preference for the usual dance range.
            float prior = b >= 85 && b <= 160 ? 1f : 0.85f;
            if (s[0] * prior > best) {
                best = s[0] * prior;
                bestBpm = b;
                bestPhase = s[1];
            }
        }
        // Half or double tempo scoring almost as well and inside the range: take that one.
        if (bestBpm < 85 && bestBpm * 2 <= MAX_BPM) {
            float[] s = score(60_000f / (bestBpm * 2));
            if (s[0] > best * 0.7f) {
                bestBpm *= 2;
                bestPhase = s[1];
                best = s[0];
            }
        } else if (bestBpm > 160) {
            float[] s = score(60_000f / (bestBpm / 2));
            if (s[0] > best * 0.7f) {
                bestBpm /= 2;
                bestPhase = s[1];
                best = s[0];
            }
        }
        bpm = bpm <= 0 || Math.abs(bpm - bestBpm) > 4 ? bestBpm : bpm * 0.7f + bestBpm * 0.3f;
        confidence = best;
        // The phase gives the time of a beat: a = 2π·(t − first)/P ⇒ t = first + phase·P/2π (mod P).
        float period = 60_000f / bestBpm;
        double frac = bestPhase / (2 * Math.PI);
        if (frac < 0) frac += 1;
        anchorMs = onsets.peekFirst()[0] + Math.round(frac * period);
    }
}
