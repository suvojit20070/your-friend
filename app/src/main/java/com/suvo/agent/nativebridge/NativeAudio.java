package com.suvo.agent.nativebridge;

import android.util.Log;

import com.suvo.agent.core.audio.LipSync;
import com.suvo.agent.core.audio.Pcm;

/** Lip-sync analysis in C++ with a transparent fallback to the Java reference implementation. */
public final class NativeAudio {

    private static final String TAG = "YF.NativeAudio";
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("yourfriend");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native library unavailable, using Java lip-sync: " + e.getMessage());
            ok = false;
        }
        LOADED = ok;
    }

    private NativeAudio() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    private static native float[] nativeAnalyze(float[] samples, int rate, double fps);

    public static LipSync.Track analyze(Pcm pcm, double fps) {
        if (LOADED) {
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
                Log.w(TAG, "nativeAnalyze failed: " + e.getMessage());
            }
        }
        return LipSync.analyze(pcm, fps);
    }
}
