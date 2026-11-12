package com.suvo.agent.voice;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Your Friend — Original project by Suvo.
 *
 * Underdog Woof (ConwayResearch/Underdog-Woof): a small (about 41 MB) English voice that runs
 * inside the app. Its original Python + numpy code runs in the app's own Python (Chaquopy), so the
 * voice sounds exactly as the authors made it — no Termux, no server. One tap in Voice settings
 * downloads the model files from Hugging Face into the app; the model loads on the first reply.
 * Voices: Juno, Mira.
 *
 * License: Underdog Non-Commercial License 1.0 — free for personal / non-commercial use with the
 * credit "Voice by Underdog Woof"; commercial use needs a license from Underdog.
 */
public final class WoofEngine implements TtsEngine {

    public static final String ID = "woof";
    public static final String VOICE_KEY = "woof_voice";
    public static final String READY_KEY = "woof_installed_app";
    static final String HF = "https://huggingface.co/ConwayResearch/Underdog-Woof/resolve/main/";
    /** Everything the runtime needs (the package folder and the weights next to it). */
    static final String[] FILES = {"woof_int8.npz", "LICENSE", "NOTICE",
            "underdog_woof/__init__.py", "underdog_woof/__main__.py", "underdog_woof/normalize.py",
            "underdog_woof/respell.py", "underdog_woof/respell.tsv", "underdog_woof/runtime.py",
            "underdog_woof/textsplit.py"};

    private final Context ctx;
    private final Settings settings;

    public WoofEngine(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
    }

    /** Where the model lives (inside the app; removed with it). */
    public File dir() {
        return new File(ctx.getFilesDir(), "woof");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    private boolean filesPresent() {
        File w = new File(dir(), "woof_int8.npz");
        return w.isFile() && w.length() > 30_000_000 && new File(dir(), "underdog_woof/runtime.py").isFile();
    }

    @Override
    public String notReadyReason() {
        if (!settings.bool(READY_KEY, false) || !filesPresent()) return "Not installed yet: tap \"Install Woof\" in Voice settings";
        return null;
    }

    public String voice() {
        String v = settings.str(VOICE_KEY, "juno");
        return "mira".equals(v) ? "mira" : "juno";
    }

    private PyObject bridge() {
        synchronized (WoofEngine.class) {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(ctx));
        }
        return Python.getInstance().getModule("woof_bridge");
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        // The model cannot be stopped half way; a cancelled reply simply is not played.
        byte[] wav = bridge().callAttr("synth", dir().getAbsolutePath(), text, voice(), 0).toJava(byte[].class);
        if (cancel.isCancelled()) throw new InterruptedException("cancelled");
        return Wav.decode(wav);
    }

    /** Frees the loaded model (it loads again on the next reply). */
    public void unload() {
        try {
            if (Python.isStarted()) bridge().callAttr("unload");
        } catch (Throwable ignored) {
        }
    }

    /** Progress of the one-tap install. */
    public interface Progress {
        void step(String text);
    }

    /**
     * The one-tap install: downloads the model files into the app, then loads the model once to
     * check it. Null on success, else what went wrong. Slow: never on the main thread.
     */
    public String install(Progress p, CancellationToken cancel) {
        File d = dir();
        //noinspection ResultOfMethodCallIgnored
        new File(d, "underdog_woof").mkdirs();
        try {
            for (int i = 0; i < FILES.length; i++) {
                if (cancel.isCancelled()) return "Cancelled";
                String f = FILES[i];
                File out = new File(d, f);
                download(HF + f, out, f.endsWith(".npz") ? p : null, i + 1, FILES.length);
            }
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
        if (!filesPresent()) return "The model file is incomplete: try again";
        p.step("Loading the model");
        try {
            bridge().callAttr("load", d.getAbsolutePath(), voice());
        } catch (Throwable t) {
            return "The model did not load: " + t.getMessage();
        }
        settings.put(READY_KEY, true);
        return null;
    }

    private static void download(String url, File out, Progress p, int n, int of) throws IOException {
        File part = new File(out.getPath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        if (out.isFile() && out.length() > 0 && p == null) return; // small files: once is enough
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            if (code == 416 && have > 0) { // already complete
                if (!part.renameTo(out)) throw new IOException("Could not save " + out.getName());
                return;
            }
            if (code >= 400) throw new IOException("HTTP " + code + " for " + out.getName());
            boolean append = code == 206 && have > 0;
            long total = c.getContentLengthLong();
            if (total > 0 && append) total += have;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long done = append ? have : 0, shown = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    done += r;
                    if (p != null && done - shown > 512 * 1024) {
                        shown = done;
                        p.step("Downloading the model " + (total > 0 ? (done * 100 / total) + "% (" + (done >> 20) + " / " + (total >> 20) + " MB)"
                                : (done >> 20) + " MB"));
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            if (!part.renameTo(out)) throw new IOException("Could not save " + out.getName());
        } finally {
            c.disconnect();
        }
    }
}
