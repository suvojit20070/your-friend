package com.suvo.agent.core.audio;

/** Mono float PCM in [-1, 1]. */
public final class Pcm {

    public final float[] samples;
    public final int sampleRate;

    public Pcm(float[] samples, int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be > 0");
        this.samples = samples;
        this.sampleRate = sampleRate;
    }

    public double durationSeconds() {
        return samples.length / (double) sampleRate;
    }

    public Pcm resample(int targetRate) {
        if (targetRate == sampleRate || samples.length == 0) return this;
        int n = (int) Math.max(1, Math.round(samples.length * (double) targetRate / sampleRate));
        float[] out = new float[n];
        double step = (double) sampleRate / targetRate;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, samples.length - 1);
            double frac = pos - i0;
            out[i] = (float) (samples[Math.min(i0, samples.length - 1)] * (1 - frac) + samples[i1] * frac);
        }
        return new Pcm(out, targetRate);
    }

    /** Peak-normalizes quiet audio up to -1 dBFS (never amplifies by more than 6x). */
    public Pcm normalized() {
        float peak = 0;
        for (float s : samples) peak = Math.max(peak, Math.abs(s));
        if (peak <= 0 || peak >= 0.89f) return this;
        float gain = Math.min(6f, 0.89f / peak);
        float[] out = new float[samples.length];
        for (int i = 0; i < out.length; i++) out[i] = samples[i] * gain;
        return new Pcm(out, sampleRate);
    }

    public static Pcm concat(Pcm a, Pcm b) {
        if (a == null) return b;
        if (b == null) return a;
        Pcm bb = b.resample(a.sampleRate);
        float[] out = new float[a.samples.length + bb.samples.length];
        System.arraycopy(a.samples, 0, out, 0, a.samples.length);
        System.arraycopy(bb.samples, 0, out, a.samples.length, bb.samples.length);
        return new Pcm(out, a.sampleRate);
    }
}
