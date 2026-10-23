package com.suvo.agent.nativebridge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * JNI wrapper around a user-supplied audio.cpp C API library (libaudiocpp.so) used to run OmniVoice
 * on the device. All calls are blocking and must run off the main thread.
 */
public final class OmniVoiceNative implements AutoCloseable {

    static {
        NativeAudio.isLoaded(); // ensures libyourfriend.so is loaded
    }

    private static native String nativePreload(String path);

    private static native long nativeOpen(String libPath, String[] errOut);

    private static native String nativeVersion(long h);

    private static native boolean nativeLoadModel(long h, String modelPath, String family, int threads);

    private static native String nativeModelFamily(long h);

    private static native float[] nativeSynthesize(long h, String text, String language, float[] refAudio, int refRate,
                                                   String refText, String instruct, float speed, int steps);

    private static native int nativeSampleRate(long h);

    private static native String nativeLastError(long h);

    private static native void nativeClose(long h);

    private long handle;

    private OmniVoiceNative(long handle) {
        this.handle = handle;
    }

    /**
     * Loads libaudiocpp.so from {@code libDir}. Any other .so files in that folder (dependencies) are
     * loaded first, retrying until their own dependencies resolve.
     */
    public static OmniVoiceNative open(File libDir, String mainLib) throws Exception {
        if (!NativeAudio.isLoaded()) throw new IllegalStateException("libyourfriend.so is not available on this device ABI");
        File main = new File(libDir, mainLib);
        if (!main.isFile()) throw new IllegalStateException("Library not found: " + main.getName());
        List<File> deps = new ArrayList<>();
        File[] all = libDir.listFiles((d, n) -> n.endsWith(".so") || n.contains(".so."));
        if (all != null) for (File f : all) if (!f.equals(main)) deps.add(f);
        for (int round = 0; round < 4 && !deps.isEmpty(); round++) {
            List<File> next = new ArrayList<>();
            for (File f : deps) if (nativePreload(f.getAbsolutePath()) != null) next.add(f);
            if (next.size() == deps.size()) break;
            deps = next;
        }
        String[] err = new String[1];
        long h = nativeOpen(main.getAbsolutePath(), err);
        if (h == 0) throw new IllegalStateException(err[0] == null ? "Could not open the audio.cpp library" : err[0]);
        return new OmniVoiceNative(h);
    }

    public synchronized String version() {
        return handle == 0 ? "" : nativeVersion(handle);
    }

    public synchronized void loadModel(String modelPath, int threads) {
        check();
        if (!nativeLoadModel(handle, modelPath, "omnivoice", threads)) throw new IllegalStateException(nativeLastError(handle));
    }

    public synchronized String modelFamily() {
        return handle == 0 ? null : nativeModelFamily(handle);
    }

    /** Returns mono float samples at {@link #sampleRate()}. */
    public synchronized float[] synthesize(String text, String language, float[] ref, int refRate, String refText,
                                           String instruct, float speed, int steps) {
        check();
        float[] out = nativeSynthesize(handle, text, language, ref, refRate, refText, instruct, speed, steps);
        if (out == null) throw new IllegalStateException(nativeLastError(handle));
        return out;
    }

    public synchronized int sampleRate() {
        return handle == 0 ? 0 : nativeSampleRate(handle);
    }

    private void check() {
        if (handle == 0) throw new IllegalStateException("OmniVoice engine is closed");
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }
}
