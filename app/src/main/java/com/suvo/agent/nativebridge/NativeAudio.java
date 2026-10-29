package com.suvo.agent.nativebridge;

import android.util.Log;

import com.suvo.agent.core.audio.LipSync;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Visemes;

/**
 * Your Friend — Original project by Suvo.
 *
 * The audio hot loops in C++ (libyourfriend.so): lip-sync, mouth shapes (visemes), resampling,
 * normalising and the microphone level. Every method has a transparent fallback to the Java
 * reference implementation, so the app works the same if the library cannot load.
 */
public final class NativeAudio {

    private static final String TAG = "YF.NativeAudio";
    private static final boolean LOADED;
    /** Set when a native call fails once, so the Java version is used from then on. */
    private static volatile boolean broken;

    static {
        boolean ok;
        try {
            System.loadLibrary("yourfriend");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native library unavailable, using Java audio code: " + e.getMessage());
            ok = false;
        }
        LOADED = ok;
    }

    private NativeAudio() {
    }

    public static boolean isLoaded() {
        return LOADED && !broken;
    }

    private static native float[] nativeAnalyze(float[] samples, int rate, double fps);

    private static native float[] nativeVisemes(float[] samples, int rate, int hop);

    private static native float[] nativeResample(float[] samples, int rate, int target);

    private static native float[] nativeNormalize(float[] samples);

    private static native float nativeRms16(short[] samples, int n);

    private static native float nativeRms16Bytes(byte[] bytes, int n);

    private static void fail(String what, Throwable e) {
        Log.w(TAG, what + " failed, using Java: " + e.getMessage());
        broken = true;
    }

    public static LipSync.Track analyze(Pcm pcm, double fps) {
        if (isLoaded()) {
            try {
                float[] interleaved = nativeAnalyze(pcm.samples, pcm.sampleRate, fps);
                if (interleaved != null && interleaved.length >= 2) {
                    int frames = interleaved.length / 2;
                    float[] open = new float[frames];
                    float[] form = new float[frames];
                    for (int i = 0; i < frames; i++) {
                        open[i] = interleaved[2 * i];
                        form[i] = interleaved[2 * i + 1];
                    }
                    int hop = Math.max(1, (int) Math.round(pcm.sampleRate / fps));
                    return new LipSync.Track(open, form, hop, pcm.sampleRate);
                }
            } catch (UnsatisfiedLinkError e) {
                fail("nativeAnalyze", e);
            }
        }
        return LipSync.analyze(pcm, fps);
    }

    /** Mouth shapes a / i / u / e / o per frame: {@code [5][frames]}. */
    public static float[][] visemes(Pcm pcm, int hop) {
        if (isLoaded() && hop > 0) {
            try {
                float[] flat = nativeVisemes(pcm.samples, pcm.sampleRate, hop);
                int frames = Math.max(1, (pcm.samples.length + hop - 1) / hop);
                if (flat != null && flat.length == 5 * frames) {
                    float[][] out = new float[5][frames];
                    for (int k = 0; k < 5; k++) System.arraycopy(flat, k * frames, out[k], 0, frames);
                    return out;
                }
            } catch (UnsatisfiedLinkError e) {
                fail("nativeVisemes", e);
            }
        }
        return Visemes.analyze(pcm, hop);
    }

    /** Same as {@link Pcm#normalized()}. */
    public static Pcm normalized(Pcm pcm) {
        if (isLoaded() && pcm.samples.length > 0) {
            try {
                float[] out = nativeNormalize(pcm.samples);
                return out == null ? pcm : new Pcm(out, pcm.sampleRate);
            } catch (UnsatisfiedLinkError e) {
                fail("nativeNormalize", e);
            }
        }
        return pcm.normalized();
    }

    /** Same as {@link Pcm#resample(int)}. */
    public static Pcm resample(Pcm pcm, int targetRate) {
        if (targetRate == pcm.sampleRate || pcm.samples.length == 0) return pcm;
        if (isLoaded()) {
            try {
                float[] out = nativeResample(pcm.samples, pcm.sampleRate, targetRate);
                if (out != null && out.length > 0) return new Pcm(out, targetRate);
            } catch (UnsatisfiedLinkError e) {
                fail("nativeResample", e);
            }
        }
        return pcm.resample(targetRate);
    }

    /** Root-mean-square of the first {@code n} 16-bit samples. */
    public static float rms16(short[] s, int n) {
        if (isLoaded()) {
            try {
                return nativeRms16(s, n);
            } catch (UnsatisfiedLinkError e) {
                fail("nativeRms16", e);
            }
        }
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) s[i] * s[i];
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    /** Root-mean-square of little-endian 16-bit samples stored in the first {@code n} bytes. */
    public static float rms16Bytes(byte[] b, int n) {
        if (isLoaded()) {
            try {
                return nativeRms16Bytes(b, n);
            } catch (UnsatisfiedLinkError e) {
                fail("nativeRms16Bytes", e);
            }
        }
        double sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            short s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (double) s * s;
        }
        return (float) Math.sqrt(sum / Math.max(1, n / 2));
    }
}
