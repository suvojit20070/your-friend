package com.suvo.agent.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Local OmniVoice through audio.cpp. Requires the user to provide:
 * (1) libaudiocpp.so built for this phone's ABI (plus libc++_shared.so), (2) an OmniVoice GGUF
 * package made by audio.cpp's own converter (CC-BY-NC weights; downloaded by the user), and
 * optionally (3) ref.wav + ref.txt for voice cloning.
 *
 * The library runs in {@link OmniVoiceService}'s separate process. If it crashes or is killed for
 * memory, this class reports why (from Android's exit record) instead of the app closing.
 */
public final class OmniVoiceNativeEngine implements TtsEngine {

    public static final String LIB_DIR = "omnivoice/lib";
    public static final String MAIN_LIB = "libaudiocpp.so";
    private static final long CONNECT_MS = 15_000, LOAD_MS = 6 * 60_000L, SYNTH_MS = 4 * 60_000L;

    private final Context ctx;
    private final Settings settings;
    private final Object lock = new Object();
    private Messenger remote;
    private ServiceConnection connection;
    private IBinder.DeathRecipient death;
    private volatile boolean died;
    private long boundAt;
    private HandlerThread replyThread;
    private String loadedKey = "";
    private volatile String status = "";

    /** Reply of the call in flight (one at a time). */
    private Bundle reply;
    private CountDownLatch replyLatch;

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

    public String status() {
        return status;
    }

    private int threads() {
        return settings.integer(Settings.OMNI_THREADS, Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)));
    }

    private Bundle baseArgs() {
        Bundle b = new Bundle();
        b.putString("libDir", libDir().getAbsolutePath());
        b.putString("mainLib", MAIN_LIB);
        b.putString("model", settings.str(Settings.OMNI_MODEL_PATH, ""));
        b.putInt("threads", threads());
        return b;
    }

    /** Loads library and model if needed (slow: seconds to a minute). */
    public synchronized void ensureLoaded() throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        Bundle args = baseArgs();
        String key = args.getString("model") + "|" + args.getInt("threads");
        if (key.equals(loadedKey) && remote != null && !died) return;
        status = "Loading audio.cpp and the model…";
        Bundle r = call(OmniVoiceService.MSG_LOAD, args, LOAD_MS, null);
        loadedKey = key;
        status = r.getString("version", "audio.cpp") + " · " + r.getString("family", "?") + " · "
                + new File(args.getString("model", "")).getName();
    }

    @Override
    public synchronized Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        ensureLoaded();
        cancel.throwIfCancelled();
        VoiceProfile v = VoiceProfile.fromSettings(settings);
        Pcm ref = v.loadReference();
        File dir = new File(ctx.getCacheDir(), "omnivoice");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "out.f32");
        File refFile = new File(dir, "ref.f32");
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        Bundle args = baseArgs();
        args.putString("text", text);
        args.putString("language", v.language);
        if (ref != null) {
            OmniVoiceService.writeFloats(refFile, ref.samples);
            args.putString("refPath", refFile.getAbsolutePath());
            args.putInt("refRate", ref.sampleRate);
            args.putString("refText", v.refText);
        } else {
            args.putString("instruct", v.instruct);
        }
        args.putFloat("speed", v.speed);
        args.putInt("steps", v.steps);
        args.putString("outPath", out.getAbsolutePath());
        Bundle r = call(OmniVoiceService.MSG_SYNTH, args, SYNTH_MS, cancel);
        cancel.throwIfCancelled();
        float[] samples = OmniVoiceService.readFloats(out);
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        int rate = r.getInt("rate", 0);
        return new Pcm(samples, rate > 0 ? rate : 24000);
    }

    // ------------------------------------------------------------------------------ process link

    private Bundle call(int what, Bundle args, long timeoutMs, CancellationToken cancel) throws Exception {
        Messenger m = connect();
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            reply = null;
            replyLatch = latch;
        }
        Message msg = Message.obtain(null, what);
        msg.setData(args);
        msg.replyTo = new Messenger(new Handler(replyThread.getLooper(), rm -> {
            synchronized (lock) {
                if (replyLatch == latch) reply = rm.getData();
            }
            latch.countDown();
            return true;
        }));
        try {
            m.send(msg);
        } catch (RemoteException e) {
            throw new IllegalStateException(crashReport());
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancel != null && cancel.isCancelled()) {
                // Native code cannot be interrupted safely: restart the engine process instead.
                restart();
                cancel.throwIfCancelled();
            }
            if (System.currentTimeMillis() > deadline) {
                restart();
                throw new IllegalStateException("The voice engine did not answer within " + timeoutMs / 1000 + " s; it was restarted");
            }
        }
        Bundle r;
        synchronized (lock) {
            r = reply;
            replyLatch = null;
        }
        if (r == null) {
            loadedKey = "";
            String report = crashReport();
            status = report;
            throw new IllegalStateException(report);
        }
        if (!r.getBoolean("ok")) {
            loadedKey = "";
            String err = r.getString("error", "unknown error");
            status = err;
            throw new IllegalStateException(err);
        }
        return r;
    }

    private Messenger connect() throws Exception {
        synchronized (lock) {
            if (remote != null && !died) return remote;
        }
        unbind();
        if (replyThread == null) {
            replyThread = new HandlerThread("omnivoice-client");
            replyThread.start();
        }
        CountDownLatch ready = new CountDownLatch(1);
        final Messenger[] got = new Messenger[1];
        died = false;
        boundAt = System.currentTimeMillis();
        clearStage();
        ServiceConnection c = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                got[0] = new Messenger(service);
                IBinder.DeathRecipient d = () -> onDied();
                try {
                    service.linkToDeath(d, 0);
                    death = d;
                } catch (RemoteException e) {
                    onDied();
                }
                ready.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                onDied();
            }
        };
        if (!ctx.bindService(new Intent(ctx, OmniVoiceService.class), c, Context.BIND_AUTO_CREATE)) {
            throw new IllegalStateException("Could not start the voice engine process");
        }
        connection = c;
        if (!ready.await(CONNECT_MS, TimeUnit.MILLISECONDS) || got[0] == null) {
            unbind();
            throw new IllegalStateException("The voice engine process did not start");
        }
        synchronized (lock) {
            remote = got[0];
            return remote;
        }
    }

    private void onDied() {
        died = true;
        CountDownLatch l;
        synchronized (lock) {
            remote = null;
            l = replyLatch;
        }
        loadedKey = "";
        if (l != null) l.countDown(); // reply stays null → crash report
    }

    private void restart() {
        unbind();
        loadedKey = "";
    }

    private void unbind() {
        ServiceConnection c;
        synchronized (lock) {
            c = connection;
            connection = null;
            remote = null;
        }
        if (c != null) {
            try {
                ctx.unbindService(c);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------------------ crash report

    private File stageFile() {
        return new File(ctx.getCacheDir(), OmniVoiceService.STAGE_FILE);
    }

    private void clearStage() {
        //noinspection ResultOfMethodCallIgnored
        stageFile().delete();
    }

    private String readStage() {
        File f = stageFile();
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 400)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Why the engine process ended: stage + Android's exit record (signal, memory, crash lines) + a hint. */
    private String crashReport() {
        String stage = readStage();
        StringBuilder sb = new StringBuilder("The voice engine (audio.cpp) crashed");
        if (!stage.isEmpty() && !"idle".equals(stage)) sb.append(" while ").append(stage);
        sb.append(". The app itself kept running.");
        String hint = null;
        if (Build.VERSION.SDK_INT >= 30) {
            android.app.ApplicationExitInfo e = findExit();
            if (e != null) {
                int reason = e.getReason();
                int sig = e.getStatus();
                if (reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                        || (reason == android.app.ApplicationExitInfo.REASON_SIGNALED && sig == 9)) {
                    sb.append("\nAndroid stopped it because the phone ran out of memory.");
                    hint = "Use a smaller (Q8_0) model, close other apps, or lower the thread count.";
                } else if (reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                        || reason == android.app.ApplicationExitInfo.REASON_SIGNALED) {
                    sb.append("\nSignal ").append(sig).append(signalName(sig)).append('.');
                    if (sig == 4) {
                        hint = "The library uses CPU instructions this phone does not have. Rebuild it for a generic "
                                + "ARMv8 CPU (GGML_NATIVE=OFF, no -march with dotprod/i8mm/sve).";
                    } else if (sig == 6 && stage.startsWith("loading the model")) {
                        hint = "audio.cpp rejected the model file. It needs an OmniVoice GGUF made by audio.cpp's own "
                                + "converter (audiocpp_gguf --family omnivoice), not a GGUF from omnivoice.cpp or llama.cpp.";
                    }
                    String lines = crashLines(e);
                    if (!lines.isEmpty()) sb.append("\n").append(lines);
                } else if (e.getDescription() != null) {
                    sb.append("\n").append(e.getDescription());
                }
            }
        }
        if (hint == null && stage.startsWith("loading the library")) {
            hint = "Import libaudiocpp.so together with the libc++_shared.so from the same build artifact, both for this "
                    + "phone's ABI (" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + ").";
        } else if (hint == null && stage.startsWith("loading the model")) {
            hint = "Check the model: it must be an OmniVoice GGUF package made by audio.cpp (audiocpp_gguf), and small "
                    + "enough for the free memory.";
        }
        if (hint != null) sb.append("\nTip: ").append(hint);
        return sb.toString();
    }

    private android.app.ApplicationExitInfo findExit() {
        if (Build.VERSION.SDK_INT < 30) return null;
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        if (am == null) return null;
        String proc = ctx.getPackageName() + ":tts";
        // The record can appear a moment after the process died.
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 10);
                for (android.app.ApplicationExitInfo e : list) {
                    if (proc.equals(e.getProcessName()) && e.getTimestamp() >= boundAt - 1000) return e;
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** The few readable lines of a native crash record that matter: abort message and our library's frames. */
    private static String crashLines(android.app.ApplicationExitInfo e) {
        String all = com.suvo.agent.DebugActivity.readTrace(e);
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : all.split("\n")) {
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("abort") || low.contains("ggml_assert") || low.contains("assert") || low.contains("libaudiocpp")
                    || low.contains("libc++_shared") || low.contains("terminating") || low.contains("exception")) {
                sb.append(line.length() > 200 ? line.substring(0, 200) + "…" : line).append('\n');
                if (++n >= 8) break;
            }
        }
        return sb.toString().trim();
    }

    private static String signalName(int sig) {
        switch (sig) {
            case 4:
                return " (SIGILL: illegal CPU instruction)";
            case 6:
                return " (SIGABRT: the library aborted, usually a failed check)";
            case 7:
                return " (SIGBUS)";
            case 11:
                return " (SIGSEGV: invalid memory access)";
            default:
                return "";
        }
    }

    /** Not synchronized: may be called while a long synthesis runs (that call then fails fast). */
    @Override
    public void close() {
        unbind();
        loadedKey = "";
        status = "";
    }
}
