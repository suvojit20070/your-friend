package com.suvo.agent.voice;

import android.content.Context;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.nativebridge.OmniVoiceNative;

import java.io.File;

/**
 * Local OmniVoice through audio.cpp loaded in-process. Requires the user to provide:
 * (1) libaudiocpp.so built for this phone's ABI, (2) an OmniVoice GGUF model (CC-BY-NC weights;
 * downloaded by the user), and optionally (3) ref.wav + ref.txt for voice cloning.
 */
public final class OmniVoiceNativeEngine implements TtsEngine {

    public static final String LIB_DIR = "omnivoice/lib";
    public static final String MAIN_LIB = "libaudiocpp.so";

    private final Context ctx;
    private final Settings settings;
    private OmniVoiceNative engine;
    private String loadedModel = "";
    private String status = "";

    public OmniVoiceNativeEngine(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
    }

    public File libDir() {
        File d = new File(ctx.getFilesDir(), LIB_DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    @Override
    public String id() {
        return "omnivoice_native";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String notReadyReason() {
        if (!new File(libDir(), MAIN_LIB).isFile()) return "Import libaudiocpp.so (audio.cpp C API built for Android) in Voice settings";
        String model = settings.str(Settings.OMNI_MODEL_PATH, "");
        if (model.isEmpty() || !new File(model).exists()) return "Choose an OmniVoice GGUF model file in Voice settings";
        return null;
    }

    public synchronized String status() {
        return status;
    }

    /** Loads library and model if needed (slow: seconds to a minute). */
    public synchronized void ensureLoaded() throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        String model = settings.str(Settings.OMNI_MODEL_PATH, "");
        if (engine == null) {
            status = "Loading audio.cpp…";
            engine = OmniVoiceNative.open(libDir(), MAIN_LIB);
            status = engine.version();
        }
        if (!model.equals(loadedModel)) {
            status = "Loading model…";
            int threads = settings.integer(Settings.OMNI_THREADS, Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)));
            engine.loadModel(model, threads);
            loadedModel = model;
            status = engine.version() + " · " + engine.modelFamily() + " · " + new File(model).getName();
        }
    }

    @Override
    public synchronized Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        ensureLoaded();
        cancel.throwIfCancelled();
        VoiceProfile v = VoiceProfile.fromSettings(settings);
        Pcm ref = v.loadReference();
        float[] out = engine.synthesize(text, v.language, ref == null ? null : ref.samples, ref == null ? 0 : ref.sampleRate,
                ref == null ? "" : v.refText, ref == null ? v.instruct : "", v.speed, v.steps);
        cancel.throwIfCancelled();
        int rate = engine.sampleRate();
        return new Pcm(out, rate > 0 ? rate : 24000);
    }

    @Override
    public synchronized void close() {
        if (engine != null) engine.close();
        engine = null;
        loadedModel = "";
        status = "";
    }
}
