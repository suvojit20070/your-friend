package com.suvo.agent.voice;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * OmniVoice running in a separate server you control — on this phone (e.g. in Termux/proot) or a
 * computer on your network. Two protocols:
 * <ul>
 *   <li><b>clone_multipart</b>: omnivoice-server's POST /v1/audio/speech/clone with ref_audio (your
 *   ref.wav) and ref_text (your ref.txt) — voice cloning on every request.</li>
 *   <li><b>openai_speech</b>: POST /v1/audio/speech {model, input, voice, response_format: wav} — works
 *   with omnivoice-server presets/voice design and audio.cpp's server.</li>
 * </ul>
 * This is a "remote" mode only when the URL is not on this device; the UI labels it accordingly.
 */
public final class OmniVoiceServerEngine implements TtsEngine {

    private final Settings settings;

    public OmniVoiceServerEngine(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "omnivoice_server";
    }

    @Override
    public boolean isLocal() {
        String u = settings.str(Settings.OMNI_SERVER_URL, "").toLowerCase(Locale.ROOT);
        return u.contains("://127.0.0.1") || u.contains("://localhost");
    }

    private String base() {
        String b = settings.str(Settings.OMNI_SERVER_URL, "").trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    @Override
    public String notReadyReason() {
        String b = base();
        if (b.isEmpty()) return "Set the OmniVoice server URL in Voice settings";
        if (!b.startsWith("http://") && !b.startsWith("https://")) return "Server URL must start with http:// or https://";
        if ("clone_multipart".equals(mode()) && !VoiceProfile.fromSettings(settings).hasReference()) {
            return "Clone mode needs ref.wav and ref.txt (set them in Voice settings)";
        }
        return null;
    }

    private String mode() {
        return settings.str(Settings.OMNI_SERVER_MODE, "clone_multipart");
    }

    /** GET /health; returns a short status. */
    public String health(CancellationToken cancel) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + "/health").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        cancel.onCancel(c::disconnect);
        try {
            int code = c.getResponseCode();
            return "HTTP " + code;
        } finally {
            c.disconnect();
        }
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        VoiceProfile v = VoiceProfile.fromSettings(settings);
        byte[] wav = "clone_multipart".equals(mode()) ? clone(text, v, cancel) : speech(text, v, cancel);
        return Wav.decode(wav);
    }

    private byte[] clone(String text, VoiceProfile v, CancellationToken cancel) throws IOException {
        String boundary = "----yf" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "text", text);
        if (!v.refText.isEmpty()) field(body, boundary, "ref_text", v.refText);
        field(body, boundary, "speed", String.format(Locale.ROOT, "%.2f", v.speed));
        field(body, boundary, "num_step", String.valueOf(v.steps));
        File ref = new File(v.refWavPath);
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"ref_audio\"; filename=\"ref.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(ref)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
        }
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return post(base() + "/v1/audio/speech/clone", "multipart/form-data; boundary=" + boundary, body.toByteArray(), cancel);
    }

    private byte[] speech(String text, VoiceProfile v, CancellationToken cancel) throws Exception {
        JSONObject o = new JSONObject();
        o.put("model", "omnivoice");
        o.put("input", text);
        o.put("response_format", "wav");
        o.put("speed", v.speed);
        String voice = settings.str(Settings.OMNI_SERVER_VOICE, "").trim();
        if (!voice.isEmpty()) o.put("voice", voice);
        if (!v.instruct.isEmpty()) o.put("instructions", v.instruct);
        if (!v.refText.isEmpty() && !voice.isEmpty()) o.put("reference_text", v.refText);
        return post(base() + "/v1/audio/speech", "application/json", o.toString().getBytes(StandardCharsets.UTF_8), cancel);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] post(String url, String type, byte[] body, CancellationToken cancel) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        Runnable kill = c::disconnect;
        cancel.onCancel(kill);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(180_000); // CPU synthesis can be slow
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            c.setRequestProperty("Accept", "audio/wav");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : Wav.readAll(in, 100 * 1024 * 1024);
            if (code >= 400) {
                String msg = new String(data, StandardCharsets.UTF_8);
                throw new IOException("OmniVoice server error HTTP " + code + ": " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
            }
            return data;
        } finally {
            cancel.removeListener(kill);
            c.disconnect();
        }
    }
}
