package com.suvo.agent.core.audio;

/**
 * JNI bridge to the native audio library (C++). Heavy per-frame audio work runs
 * natively for speed: used by the voice/call layer (VAD) and the character layer
 * (lip-sync mouth motion).
 *
 * If the native lib fails to load (e.g. unsupported ABI), {@link #available()} is
 * false and callers fall back to Java equivalents.
 */
public final class NativeAudio {

    private static boolean loaded;
    static {
        try { System.loadLibrary("agentnative"); loaded = true; }
        catch (Throwable t) { loaded = false; }
    }

    private NativeAudio() {}

    public static boolean available() { return loaded; }

    /** Normalized RMS (0..1) of 16-bit PCM. */
    public static native float rms(short[] pcm, int len);

    /** Normalized peak (0..1). */
    public static native float peak(short[] pcm, int len);

    /** 1 if the frame is speech (energy VAD), else 0. threshold ~0.012. */
    public static native int isVoiced(short[] pcm, int len, float threshold);

    /** Next mouth-open value (0..1) for lip-sync, smoothed from prev toward amp. */
    public static native float mouthOpen(float prev, float amp);

    /** Fast linear resample of 16-bit PCM. */
    public static native short[] resample(short[] pcm, int len, int inRate, int outRate);

    // ---- Java fallbacks (used when native lib unavailable) ----
    public static float rmsJava(short[] pcm, int len) {
        if (len <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < len; i++) { double v = pcm[i]; sum += v * v; }
        return (float) Math.min(1.0, Math.sqrt(sum / len) / 32768.0);
    }
}
