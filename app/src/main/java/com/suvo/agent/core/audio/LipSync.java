package com.suvo.agent.core.audio;

/**
 * Amplitude/spectral lip-sync fallback (no phoneme timings are available from the TTS engines).
 * For each frame (default 1/60 s) it computes loudness → mouth openness and a coarse "form" from the
 * balance of low vs. high band energy (rounded "o/u" vs. wide "i/e" shapes). A native (C++)
 * implementation computes the same values faster; this Java version is the reference and fallback.
 */
public final class LipSync {

    /** Per-frame mouth values, both in 0..1 (form: 0 = rounded, 1 = wide). */
    public static final class Track {
        public final float[] open;
        public final float[] form;
        public final int hopSamples;
        public final int sampleRate;

        public Track(float[] open, float[] form, int hopSamples, int sampleRate) {
            this.open = open;
            this.form = form;
            this.hopSamples = hopSamples;
            this.sampleRate = sampleRate;
        }

        public int frameAt(long samplePosition) {
            int i = (int) (samplePosition / hopSamples);
            return Math.max(0, Math.min(open.length - 1, i));
        }
    }

    private LipSync() {
    }

    public static Track analyze(Pcm pcm, double framesPerSecond) {
        int hop = Math.max(1, (int) Math.round(pcm.sampleRate / framesPerSecond));
        int frames = Math.max(1, (pcm.samples.length + hop - 1) / hop);
        float[] rms = new float[frames];
        float[] low = new float[frames];
        float[] high = new float[frames];
        float maxRms = 1e-6f;
        int win = hop * 2;
        for (int f = 0; f < frames; f++) {
            int start = f * hop;
            int end = Math.min(pcm.samples.length, start + win);
            double sum = 0;
            for (int i = start; i < end; i++) sum += pcm.samples[i] * pcm.samples[i];
            int n = Math.max(1, end - start);
            rms[f] = (float) Math.sqrt(sum / n);
            maxRms = Math.max(maxRms, rms[f]);
            low[f] = (float) goertzel(pcm.samples, start, end, 500, pcm.sampleRate)
                    + (float) goertzel(pcm.samples, start, end, 800, pcm.sampleRate);
            high[f] = (float) goertzel(pcm.samples, start, end, 2200, pcm.sampleRate)
                    + (float) goertzel(pcm.samples, start, end, 3000, pcm.sampleRate);
        }
        float[] open = new float[frames];
        float[] form = new float[frames];
        float noise = maxRms * 0.06f;
        float prevOpen = 0;
        float prevForm = 0.5f;
        for (int f = 0; f < frames; f++) {
            float v = Math.max(0, rms[f] - noise) / Math.max(1e-6f, maxRms - noise);
            v = (float) Math.pow(v, 0.6); // perceptual curve: small sounds still move the mouth
            // Fast attack, slower release, like a jaw.
            float o = v > prevOpen ? prevOpen + (v - prevOpen) * 0.75f : prevOpen + (v - prevOpen) * 0.35f;
            float total = low[f] + high[f];
            float fm = total <= 1e-9 ? prevForm : high[f] / total;
            fm = prevForm + (Math.min(1f, Math.max(0f, fm * 1.6f)) - prevForm) * 0.4f;
            open[f] = Math.min(1f, Math.max(0f, o));
            form[f] = fm;
            prevOpen = open[f];
            prevForm = fm;
        }
        return new Track(open, form, hop, pcm.sampleRate);
    }

    /** Power at one frequency (Goertzel algorithm). */
    static double goertzel(float[] x, int start, int end, double freq, int rate) {
        int n = end - start;
        if (n <= 0) return 0;
        double k = Math.round(n * freq / rate);
        double w = 2 * Math.PI * k / n;
        double coeff = 2 * Math.cos(w);
        double s1 = 0;
        double s2 = 0;
        for (int i = start; i < end; i++) {
            double s0 = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / n;
    }
}
