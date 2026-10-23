package com.suvo.agent.voice;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.SecretStore;
import com.suvo.agent.data.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Voice from a Hugging Face Space through its Gradio API — for example the user's own
 * "Suvo75/tanu-voice" Space, which runs OmniVoice with a cloned reference voice on ZeroGPU.
 *
 * Protocol (Gradio 4/5 HTTP API): POST {space}/gradio_api/call/{api} with {"data": [...]} returns
 * an event id; GET {space}/gradio_api/call/{api}/{event_id} streams server-sent events until
 * "event: complete" with the outputs. The first output is the generated audio file, downloaded as
 * WAV and played with lip sync like every other engine.
 *
 * The Space's /tts inputs are: text, language, num_step, format, api_key. A Hugging Face token is
 * sent as "Authorization: Bearer" when set: it is needed for private Spaces and makes ZeroGPU count
 * usage against your own account's quota (larger when logged in, larger again with PRO). It is not
 * unlimited: when the day's quota is used up the Space returns an error and the app falls back to
 * the phone's voice if that option is on.
 */
public final class HfSpaceEngine implements TtsEngine {

    public static final String URL_KEY = "hf_space_url";
    public static final String TOKEN_KEY = "hf_token_enc";
    public static final String API_KEY_KEY = "hf_space_api_key_enc";
    public static final String API_NAME_KEY = "hf_space_api";

    private final Settings settings;
    private volatile String lastInfo = "";

    public HfSpaceEngine(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "hf_space";
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    public String lastInfo() {
        return lastInfo;
    }

    /** Accepts "owner/space", a huggingface.co/spaces/... link or the *.hf.space address. */
    public static String baseUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String lower = s.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("huggingface.co/spaces/");
        if (idx >= 0) {
            String rest = s.substring(idx + "huggingface.co/spaces/".length());
            String[] parts = rest.split("/");
            if (parts.length >= 2) return subdomain(parts[0], parts[1]);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s;
        String[] parts = s.split("/");
        if (parts.length == 2) return subdomain(parts[0], parts[1]);
        return "";
    }

    private static String subdomain(String owner, String name) {
        String sub = (owner + "-" + name).toLowerCase(Locale.ROOT).replace('_', '-').replace('.', '-');
        return "https://" + sub + ".hf.space";
    }

    private String secret(String key) {
        String enc = settings.str(key, "");
        if (enc.isEmpty()) return "";
        try {
            return SecretStore.get().decrypt(enc);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String notReadyReason() {
        if (baseUrl(settings.str(URL_KEY, "")).isEmpty()) {
            return "Set your Hugging Face Space (e.g. Suvo75/tanu-voice) in Voice settings";
        }
        return null;
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        String why = notReadyReason();
        if (why != null) throw new IllegalStateException(why);
        String base = baseUrl(settings.str(URL_KEY, ""));
        String api = settings.str(API_NAME_KEY, "tts").replaceAll("[^a-zA-Z0-9_]", "");
        if (api.isEmpty()) api = "tts";
        String lang = settings.str(Settings.VOICE_LANGUAGE, "");
        JSONArray data = new JSONArray()
                .put(text)
                .put(lang.isEmpty() ? "hi" : lang)
                .put(Math.max(4, Math.min(64, settings.integer(Settings.VOICE_STEPS, 32))))
                .put("wav")
                .put(secret(API_KEY_KEY));
        String token = secret(TOKEN_KEY);

        // A sleeping Space answers 503/404 until it has booted; keep trying for a while.
        String eventId = null;
        String prefix = "/gradio_api";
        IOException last = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (eventId == null && !cancel.isCancelled()) {
            try {
                eventId = startCall(base + prefix + "/call/" + api, data, token, cancel);
            } catch (HttpError e) {
                if (e.code == 404 && prefix.length() > 0) {
                    prefix = ""; // Gradio 4 serves /call/... without the /gradio_api prefix
                    continue;
                }
                if ((e.code == 503 || e.code == 502 || e.code == 504) && System.currentTimeMillis() < deadline) {
                    last = e;
                    Thread.sleep(4000);
                    continue;
                }
                throw new IOException(describe(e), e);
            }
        }
        if (eventId == null) throw last != null ? last : new IOException("Cancelled");

        JSONArray out = awaitResult(base + prefix + "/call/" + api + "/" + eventId, token, cancel);
        if (out.length() > 2) lastInfo = String.valueOf(out.opt(2));
        String fileUrl = fileUrl(base, prefix, out.opt(0));
        if (fileUrl == null && out.length() > 1) fileUrl = fileUrl(base, prefix, out.opt(1));
        if (fileUrl == null) throw new IOException("The Space returned no audio file: " + out);
        byte[] wav = get(fileUrl, token, cancel);
        return Wav.decode(wav);
    }

    private static String describe(HttpError e) {
        if (e.code == 401 || e.code == 403) return "The Space refused access (HTTP " + e.code + "). For a private Space, add your Hugging Face token.";
        if (e.code == 429) return "Hugging Face rate limit or ZeroGPU quota reached (HTTP 429). Try later or add a Hugging Face token.";
        return "Space error HTTP " + e.code + ": " + e.body;
    }

    private static String fileUrl(String base, String prefix, Object o) {
        if (o instanceof JSONObject) {
            JSONObject f = (JSONObject) o;
            String url = f.optString("url", "");
            if (!url.isEmpty() && !"null".equals(url)) return url;
            String path = f.optString("path", "");
            if (!path.isEmpty()) return base + prefix + "/file=" + path;
        } else if (o instanceof String) {
            String s = (String) o;
            if (s.startsWith("http")) return s;
            if (s.startsWith("/")) return base + prefix + "/file=" + s;
        }
        return null;
    }

    static final class HttpError extends IOException {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static HttpURLConnection open(String url, String token, CancellationToken cancel) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(240_000);
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "YourFriend/1 (Android)");
        cancel.onCancel(c::disconnect);
        return c;
    }

    private static String startCall(String url, JSONArray data, String token, CancellationToken cancel) throws IOException {
        HttpURLConnection c = open(url, token, cancel);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("data", data).toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            String text = readText(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new HttpError(code, text);
            return new JSONObject(text).getString("event_id");
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected answer from the Space: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** Reads the server-sent events until "complete" (outputs) or "error". */
    private static JSONArray awaitResult(String url, String token, CancellationToken cancel) throws IOException {
        HttpURLConnection c = open(url, token, cancel);
        try {
            c.setRequestProperty("Accept", "text/event-stream");
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code, readText(c.getErrorStream()));
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String event = "";
            String line;
            while ((line = r.readLine()) != null) {
                if (cancel.isCancelled()) throw new IOException("Cancelled");
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String payload = line.substring(5).trim();
                    if ("complete".equals(event)) {
                        try {
                            return new JSONArray(payload);
                        } catch (org.json.JSONException e) {
                            throw new IOException("Unexpected result from the Space: " + payload);
                        }
                    }
                    if ("error".equals(event)) {
                        String msg = payload.equals("null") ? "the Space reported an error (often: ZeroGPU quota used up or a bad input)" : payload;
                        throw new IOException("Space error: " + msg);
                    }
                }
            }
            throw new IOException("The Space closed the connection without a result");
        } finally {
            c.disconnect();
        }
    }

    private static byte[] get(String url, String token, CancellationToken cancel) throws IOException {
        HttpURLConnection c = open(url, token, cancel);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code, readText(c.getErrorStream()));
            return Wav.readAll(c.getInputStream(), 60 * 1024 * 1024);
        } finally {
            c.disconnect();
        }
    }

    private static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        return new String(Wav.readAll(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
    }
}
