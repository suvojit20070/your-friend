package com.suvo.agent.voice;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.SecretStore;
import com.suvo.agent.data.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 *
 * Fish Audio (cloud) text to speech: POST {base}/v1/tts with the user's API key, voice
 * (reference_id) and model. Audio comes back as raw 16-bit PCM in a chunked (streamed) response,
 * and {@link #stream} hands it on piece by piece as it arrives — playback starts before the whole
 * sentence is made. Only the base URL is preset; key, voice and model are the user's.
 */
public final class FishAudioEngine implements TtsEngine {

    public static final String BASE_URL = "fish_base_url";
    public static final String DEFAULT_BASE = "https://api.fish.audio";
    /** The API key, encrypted with {@link SecretStore}. */
    public static final String API_KEY = "fish_api_key_enc";
    public static final String REFERENCE_ID = "fish_reference_id";
    public static final String MODEL = "fish_model";
    /** low | balanced | normal */
    public static final String LATENCY = "fish_latency";

    static final int RATE = 24000;

    /** Receives audio while it streams in. */
    public interface Sink {
        void onAudio(float[] samples, int sampleRate) throws InterruptedException;
    }

    private final Settings settings;

    public FishAudioEngine(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "fish_audio";
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    private String base() {
        String b = settings.str(BASE_URL, DEFAULT_BASE).trim();
        if (b.isEmpty()) b = DEFAULT_BASE;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private String key() {
        try {
            return SecretStore.get().decrypt(settings.str(API_KEY, "")).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static void saveKey(Settings settings, String key) {
        settings.put(API_KEY, key == null || key.trim().isEmpty() ? "" : SecretStore.get().encrypt(key.trim()));
    }

    public boolean hasKey() {
        return !key().isEmpty();
    }

    @Override
    public String notReadyReason() {
        String b = base();
        if (!b.startsWith("https://") && !b.startsWith("http://")) return "Fish Audio base URL must start with https://";
        if (key().isEmpty()) return "Set your Fish Audio API key in Voice settings";
        if (settings.str(REFERENCE_ID, "").trim().isEmpty()) return "Set the Fish Audio voice (reference_id) in Voice settings";
        return null;
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        final int[] rate = {RATE};
        java.util.List<float[]> parts = new java.util.ArrayList<>();
        stream(text, cancel, (s, r) -> {
            parts.add(s);
            rate[0] = r;
        });
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int off = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        if (n == 0) throw new IOException("Fish Audio returned no audio");
        return new Pcm(out, rate[0]);
    }

    /** Synthesizes {@code text}, calling {@code sink} with audio as soon as each piece arrives. */
    public void stream(String text, CancellationToken cancel, Sink sink) throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        JSONObject o = new JSONObject();
        o.put("text", text);
        o.put("reference_id", settings.str(REFERENCE_ID, "").trim());
        o.put("format", "pcm");
        o.put("sample_rate", RATE);
        o.put("latency", settings.str(LATENCY, "balanced"));
        float speed = Math.max(0.5f, Math.min(2.0f, settings.flt(Settings.VOICE_SPEED, 1.0f)));
        if (Math.abs(speed - 1f) > 0.01f) o.put("prosody", new JSONObject().put("speed", speed));
        String lang = settings.str(Settings.VOICE_LANGUAGE, "").trim();
        if (!lang.isEmpty() && lang.length() <= 35 && lang.matches("[A-Za-z0-9_-]+")) o.put("language", lang);
        byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(base() + "/v1/tts").openConnection();
        Runnable kill = c::disconnect;
        cancel.onCancel(kill);
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key());
            c.setRequestProperty("Content-Type", "application/json");
            String model = settings.str(MODEL, "").trim();
            if (!model.isEmpty()) c.setRequestProperty("model", model);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream es = c.getErrorStream();
                String msg = es == null ? "" : new String(readSome(es, 2000), StandardCharsets.UTF_8);
                throw new IOException("Fish Audio HTTP " + code + explain(code) + (msg.isEmpty() ? "" : ": " + msg));
            }
            try (InputStream in = c.getInputStream()) {
                // 16-bit little-endian mono; hand on ~0.1 s at a time (a byte may be split across reads).
                byte[] buf = new byte[RATE / 5];
                int carry = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.isCancelled()) return;
                    int start = 0;
                    int count = n + (carry >= 0 ? 1 : 0);
                    float[] out = new float[count / 2];
                    int k = 0;
                    if (carry >= 0) {
                        out[k++] = (short) ((carry & 0xFF) | (buf[0] << 8)) / 32768f;
                        start = 1;
                        carry = -1;
                    }
                    int i = start;
                    for (; i + 1 < n; i += 2) out[k++] = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8)) / 32768f;
                    if (i < n) carry = buf[i] & 0xFF;
                    if (k > 0) {
                        if (k < out.length) out = java.util.Arrays.copyOf(out, k);
                        sink.onAudio(out, RATE);
                    }
                }
            }
        } finally {
            cancel.removeListener(kill);
            c.disconnect();
        }
    }

    private static String explain(int code) {
        switch (code) {
            case 401:
                return " (wrong API key)";
            case 402:
                return " (no credit / quota left)";
            case 404:
                return " (voice reference_id not found)";
            case 429:
                return " (too many requests, try again soon)";
            default:
                return "";
        }
    }

    private static byte[] readSome(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while (b.size() < max && (n = in.read(buf)) > 0) b.write(buf, 0, Math.min(n, max - b.size()));
        return b.toByteArray();
    }
}
