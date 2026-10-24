package com.suvo.agent.core.audio;

/**
 * Your Friend — Original project by Suvo.
 *
 * Mouth shapes from the actual sound being played. For each frame it estimates the first two
 * formants (the resonances that make vowels sound different: F1 ≈ how open the mouth is,
 * F2 ≈ how far forward/spread the tongue and lips are) from a smoothed spectrum, then scores the
 * five VRM mouth shapes a / i / u / e / o by distance to typical vowel formants. Multiplied by the
 * loudness ("open") this drives the 3D character's aa / ih / ou / ee / oh expressions, so the lips
 * follow what is really said instead of only flapping with the volume.
 *
 * Pure Java (unit-tested); TTS engines give no phoneme timings, so this is analysed from audio.
 */
public final class Visemes {

    public static final int A = 0, I = 1, U = 2, E = 3, O = 4;

    // Typical formants (Hz) of the five vowels for a light, young voice.
    private static final float[][] VOWELS = {
            {850, 1450},  // a
            {320, 2600},  // i
            {360, 950},   // u
            {520, 2150},  // e
            {560, 1000},  // o
    };

    private Visemes() {
    }

    /**
     * @return {@code [5][frames]} weights per frame (each frame sums to ~1); frames match a
     * {@link LipSync.Track} with the same hop.
     */
    public static float[][] analyze(Pcm pcm, int hop) {
        int frames = Math.max(1, (pcm.samples.length + hop - 1) / hop);
        float[][] out = new float[5][frames];
        int rate = pcm.sampleRate;
        int win = Math.max(hop * 2, rate / 40); // at least 25 ms, enough to see formants
        float[] w = new float[win];
        for (int i = 0; i < win; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (win - 1)));
        float[] buf = new float[win];
        float[] prev = {0.2f, 0.2f, 0.2f, 0.2f, 0.2f};
        for (int f = 0; f < frames; f++) {
            int start = Math.max(0, f * hop + hop / 2 - win / 2);
            int n = Math.min(win, pcm.samples.length - start);
            double energy = 0;
            for (int i = 0; i < win; i++) {
                float s = i < n ? pcm.samples[start + i] : 0f;
                buf[i] = s * w[i];
                energy += buf[i] * buf[i];
            }
            float[] cur = prev.clone();
            if (energy > 1e-7 * win) {
                // Fine grids: the voice is a comb of pitch harmonics, a coarse grid can miss the peak.
                float f1 = peak(buf, win, rate, 250, 1050, 20);
                float f2 = peak(buf, win, rate, Math.max(700, f1 + 250), 3000, 30);
                cur = score(f1, f2);
            }
            // Fast attack, slower release, like real articulation.
            for (int k = 0; k < 5; k++) {
                float rateK = cur[k] > prev[k] ? 0.55f : 0.3f;
                prev[k] = prev[k] + (cur[k] - prev[k]) * rateK;
                out[k][f] = prev[k];
            }
        }
        return out;
    }

    /** Weights for the five shapes from formant estimates. */
    public static float[] score(float f1, float f2) {
        float[] s = new float[5];
        float sum = 0;
        for (int k = 0; k < 5; k++) {
            double d1 = Math.log(f1 / VOWELS[k][0]) / 0.22;
            double d2 = Math.log(f2 / VOWELS[k][1]) / 0.20;
            s[k] = (float) Math.exp(-(d1 * d1 + d2 * d2) / 2);
            sum += s[k];
        }
        if (sum < 1e-6f) return new float[]{0.2f, 0.2f, 0.2f, 0.2f, 0.2f};
        for (int k = 0; k < 5; k++) s[k] /= sum;
        return s;
    }

    /** Frequency of the strongest region in [lo, hi] (energy-weighted around the maximum). */
    static float peak(float[] x, int n, int rate, float lo, float hi, float step) {
        int count = (int) ((hi - lo) / step) + 1;
        if (count <= 1) return lo;
        double[] e = new double[count];
        int best = 0;
        for (int i = 0; i < count; i++) {
            e[i] = goertzel(x, n, lo + i * step, rate);
            if (e[i] > e[best]) best = i;
        }
        // Smooth over neighbours so single harmonics of the voice pitch do not decide alone.
        double num = 0, den = 0;
        for (int i = Math.max(0, best - 4); i <= Math.min(count - 1, best + 4); i++) {
            double wgt = e[i] * e[i];
            num += wgt * (lo + i * step);
            den += wgt;
        }
        return den <= 0 ? lo + best * step : (float) (num / den);
    }

    static double goertzel(float[] x, int n, double freq, int rate) {
        double w = 2 * Math.PI * freq / rate;
        double coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double s0 = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }
}
