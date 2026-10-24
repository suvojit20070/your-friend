package com.suvo.agent.voice;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.suvo.agent.nativebridge.OmniVoiceNative;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 *
 * Runs the user-supplied audio.cpp library in its own process (":tts"). Native code we did not
 * build — and a model file it may not understand — can crash with a signal or be killed for using
 * too much memory; in this process that only ends the voice engine, never the app. The app sees the
 * process die, reads why from Android (signal, low memory, crash record) and shows that instead.
 *
 * Audio and the voice reference travel through files, because binder messages are limited to ~1 MB.
 * Before every risky step the current stage is written to a small file, so a crash report can say
 * whether it died loading the library, loading the model or speaking.
 */
public final class OmniVoiceService extends Service {

    static final int MSG_LOAD = 1, MSG_SYNTH = 2;
    static final String STAGE_FILE = "omnivoice_stage.txt";

    private HandlerThread thread;
    private Messenger messenger;
    private OmniVoiceNative engine;
    private String loadedLibDir = "", loadedModel = "";
    private int loadedThreads;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("omnivoice");
        thread.start();
        messenger = new Messenger(new Handler(thread.getLooper(), this::handle));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (engine != null) engine.close();
        engine = null;
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    private boolean handle(Message msg) {
        Bundle in = msg.getData();
        Bundle out = new Bundle();
        try {
            if (msg.what == MSG_LOAD) {
                load(in, out);
            } else if (msg.what == MSG_SYNTH) {
                load(in, out);
                synth(in, out);
            }
            out.putBoolean("ok", true);
        } catch (Throwable t) {
            Log.w("YF.OmniVoice", "Engine call failed", t);
            out.putBoolean("ok", false);
            out.putString("error", t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        stage("idle");
        Message reply = Message.obtain(null, msg.what);
        reply.setData(out);
        try {
            if (msg.replyTo != null) msg.replyTo.send(reply);
        } catch (RemoteException ignored) {
            // The app went away; nothing to tell.
        }
        return true;
    }

    private void load(Bundle in, Bundle out) throws Exception {
        String libDir = in.getString("libDir", "");
        String mainLib = in.getString("mainLib", "libaudiocpp.so");
        String model = in.getString("model", "");
        int threads = in.getInt("threads", 4);
        if (engine == null || !libDir.equals(loadedLibDir)) {
            if (engine != null) engine.close();
            engine = null;
            loadedModel = "";
            stage("loading the library " + mainLib + " (and the other imported .so files)");
            engine = OmniVoiceNative.open(new File(libDir), mainLib);
            loadedLibDir = libDir;
        }
        if (!model.equals(loadedModel) || threads != loadedThreads) {
            stage("loading the model " + new File(model).getName() + " with " + threads + " threads");
            engine.loadModel(model, threads);
            loadedModel = model;
            loadedThreads = threads;
        }
        out.putString("version", engine.version());
        out.putString("family", engine.modelFamily());
    }

    private void synth(Bundle in, Bundle out) throws Exception {
        float[] ref = null;
        String refPath = in.getString("refPath");
        if (refPath != null) ref = readFloats(new File(refPath));
        stage("speaking (synthesizing " + in.getString("text", "").length() + " characters)");
        float[] pcm = engine.synthesize(in.getString("text", ""), in.getString("language", ""), ref,
                in.getInt("refRate", 0), in.getString("refText", ""), in.getString("instruct", ""),
                in.getFloat("speed", 1f), in.getInt("steps", 32));
        writeFloats(new File(in.getString("outPath")), pcm);
        out.putInt("rate", engine.sampleRate());
        out.putInt("frames", pcm.length);
    }

    /** Written synchronously before each risky step: it must be on disk if the next call kills us. */
    private void stage(String s) {
        try (OutputStream o = new FileOutputStream(new File(getCacheDir(), STAGE_FILE))) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
            o.flush();
        } catch (IOException ignored) {
        }
    }

    static void writeFloats(File f, float[] data) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            o.writeInt(data.length);
            for (float v : data) o.writeFloat(v);
        }
    }

    static float[] readFloats(File f) throws IOException {
        try (DataInputStream i = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            int n = i.readInt();
            if (n < 0 || n > 50_000_000) throw new IOException("Bad audio file from the voice engine");
            float[] d = new float[n];
            for (int k = 0; k < n; k++) d[k] = i.readFloat();
            return d;
        }
    }
}
