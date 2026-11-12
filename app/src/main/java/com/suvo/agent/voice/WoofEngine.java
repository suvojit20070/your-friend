package com.suvo.agent.voice;

import android.content.Context;
import android.util.Base64;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.TermuxBackend;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 *
 * Underdog Woof (ConwayResearch/Underdog-Woof): a small (40 MB) English voice that runs on this
 * phone, in Termux, with Python and numpy only. One tap in Voice settings installs it: Python +
 * numpy from Termux's packages, the model files from Hugging Face, and a tiny local server
 * (127.0.0.1 only, token protected) that turns text into a WAV. The server starts by itself when
 * a reply is spoken. Voices: Juno, Mira.
 *
 * License: Underdog Non-Commercial License 1.0 — free for personal / non-commercial use with the
 * credit "Voice by Underdog Woof"; commercial use needs a license from Underdog.
 */
public final class WoofEngine implements TtsEngine {

    public static final String ID = "woof";
    public static final String VOICE_KEY = "woof_voice";
    public static final String READY_KEY = "woof_installed";
    private static final String TOKEN_KEY = "woof_token";
    static final int PORT = 8766;
    static final String DIR = "$HOME/.yourfriend/woof";
    static final String HF = "https://huggingface.co/ConwayResearch/Underdog-Woof/resolve/main/";
    /** Everything the runtime needs (the package folder and the weights next to it). */
    static final String[] FILES = {"woof_int8.npz", "LICENSE", "NOTICE", "requirements.txt",
            "underdog_woof/__init__.py", "underdog_woof/__main__.py", "underdog_woof/normalize.py",
            "underdog_woof/respell.py", "underdog_woof/respell.tsv", "underdog_woof/runtime.py",
            "underdog_woof/textsplit.py"};

    private final Context ctx;
    private final Settings settings;
    private final TermuxBackend termux;

    public WoofEngine(Context ctx, Settings settings, TermuxBackend termux) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.termux = termux;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String notReadyReason() {
        String why = termux.unavailableReason();
        if (why != null) return "Termux is needed: " + why;
        if (!settings.bool(READY_KEY, false)) return "Not installed yet: tap \"Install Woof\" in Voice settings";
        return null;
    }

    private String token() {
        String t = settings.str(TOKEN_KEY, "");
        if (t.isEmpty()) {
            byte[] b = new byte[18];
            new java.security.SecureRandom().nextBytes(b);
            t = Base64.encodeToString(b, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            settings.put(TOKEN_KEY, t);
        }
        return t;
    }

    public String voice() {
        String v = settings.str(VOICE_KEY, "juno");
        return "mira".equals(v) ? "mira" : "juno";
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        JSONObject req = new JSONObject();
        req.put("text", text);
        req.put("voice", voice());
        req.put("seed", 0);
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return Wav.decode(post(body, cancel));
        } catch (ConnectException e) {
            // Not running yet: start it and wait for it to load the model (a few seconds).
            String s = start(cancel);
            if (s != null) throw new IllegalStateException(s);
            for (int i = 0; i < 60 && !cancel.isCancelled(); i++) {
                Thread.sleep(1000);
                try {
                    return Wav.decode(post(body, cancel));
                } catch (ConnectException still) {
                    // loading
                }
            }
            throw new IllegalStateException("The Woof voice server did not start: see " + DIR + "/server.log in Termux, or install again");
        }
    }

    private byte[] post(byte[] body, CancellationToken cancel) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/tts").openConnection();
        Runnable kill = c::disconnect;
        cancel.onCancel(kill);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(180_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-token", token());
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : Wav.readAll(in, 60 * 1024 * 1024);
            if (code >= 400) {
                String m = new String(data, StandardCharsets.UTF_8);
                throw new IOException("Woof: HTTP " + code + " " + (m.length() > 300 ? m.substring(0, 300) : m));
            }
            return data;
        } finally {
            cancel.removeListener(kill);
            c.disconnect();
        }
    }

    /** Starts the server in Termux (background, kept awake). Null on success, else why not. */
    public String start(CancellationToken cancel) {
        String why = termux.unavailableReason();
        if (why != null) return why;
        ExecResult r = termux.run("cd " + DIR + " 2>/dev/null && [ -f woof_int8.npz ] && [ -f server.py ] || { echo NOSETUP; exit 3; }; "
                + "pkill -f 'python server.py' 2>/dev/null; YF_TOKEN='" + token() + "' YF_PORT=" + PORT + " YF_VOICE=" + voice()
                + " setsid nohup python server.py >> server.log 2>&1 < /dev/null & "
                + "(command -v termux-wake-lock >/dev/null && termux-wake-lock) ; sleep 1; echo STARTED", null, 30_000, cancel);
        if (r.stdout.contains("NOSETUP")) {
            settings.put(READY_KEY, false);
            return "Woof is not installed: install it again in Voice settings";
        }
        if (!r.stdout.contains("STARTED")) return "Could not start Woof: " + r.stderr + r.error;
        return null;
    }

    /** Stops the server (it frees its memory). */
    public void stop() {
        if (termux.unavailableReason() != null) return;
        termux.run("pkill -f 'python server.py' 2>/dev/null; echo OK", null, 10_000, new CancellationToken());
    }

    /** GET /health: a short status line. */
    public String health() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestProperty("x-token", token());
        try {
            return "HTTP " + c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** Progress of the one-tap install. */
    public interface Progress {
        void step(String text);
    }

    /**
     * The one-tap install: Python + numpy (Termux packages), the model from Hugging Face, the
     * server script; then a first start. Null on success, else what went wrong. Slow (minutes):
     * never on the main thread.
     */
    public String install(Progress p, CancellationToken cancel) throws IOException {
        String why = termux.unavailableReason();
        if (why != null) return "Termux is needed: " + why;
        byte[] py;
        try (InputStream in = ctx.getAssets().open("voice/woof/server.py")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            py = bo.toByteArray();
        }
        p.step("Copying the voice server to Termux");
        ExecResult w = termux.run("mkdir -p " + DIR + "/underdog_woof && base64 -d > " + DIR + "/server.py && echo OK",
                Base64.encodeToString(py, Base64.NO_WRAP), 60_000, cancel);
        if (!w.stdout.contains("OK")) return "Could not write to Termux: " + w.stderr + w.error;

        p.step("Installing Python and numpy (a few minutes the first time)");
        ExecResult r = termux.run("(command -v python >/dev/null || yes | pkg install -y python) "
                + "&& (python -c 'import numpy' 2>/dev/null || yes | pkg install -y python-numpy || pip install numpy) "
                + "&& (command -v curl >/dev/null || yes | pkg install -y curl) "
                + "&& python -c 'import numpy; print(\"NUMPY\", numpy.__version__)'", null, 20 * 60_000, cancel);
        if (!r.jobId.isEmpty()) return "Still installing in Termux (job " + r.jobId + "): wait a little, then tap Install again";
        if (!r.stdout.contains("NUMPY")) return "Python / numpy install failed: " + tail(r.stdout + "\n" + r.stderr + r.error);

        p.step("Downloading the model (about 41 MB)");
        StringBuilder dl = new StringBuilder("cd " + DIR + " && ");
        for (String f : FILES) {
            // Kept when already complete; downloaded again (resumed) otherwise.
            dl.append("curl -fL --retry 3 -C - -o '").append(f).append("' '").append(HF).append(f).append("' && ");
        }
        dl.append("[ $(stat -c %s woof_int8.npz) -gt 30000000 ] && echo DOWNLOADED");
        r = termux.run(dl.toString(), null, 20 * 60_000, cancel);
        if (!r.jobId.isEmpty()) return "Still downloading in Termux (job " + r.jobId + "): wait a little, then tap Install again";
        if (!r.stdout.contains("DOWNLOADED")) return "Download failed: " + tail(r.stdout + "\n" + r.stderr + r.error);

        p.step("Checking the model");
        r = termux.run("cd " + DIR + " && python -c 'from underdog_woof import Woof; t=Woof(voice=\"juno\"); a=t(\"Hello.\", seed=0); print(\"WOOFOK\", len(a))'",
                null, 5 * 60_000, cancel);
        if (!r.stdout.contains("WOOFOK")) return "The model did not run: " + tail(r.stdout + "\n" + r.stderr + r.error);
        settings.put(READY_KEY, true);

        p.step("Starting the voice");
        return start(cancel);
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 1200 ? "…" + s.substring(s.length() - 1200) : s;
    }
}
