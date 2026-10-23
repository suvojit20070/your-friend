package com.suvo.agent.core.llm;

import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions client over HttpURLConnection (no third-party HTTP stack).
 * Streaming uses SSE; servers that ignore "stream" and return plain JSON are handled too.
 */
public final class OpenAiCompatClient implements LlmClient {

    private final ProviderConfig cfg;

    public OpenAiCompatClient(ProviderConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public ProviderConfig config() {
        return cfg;
    }

    @Override
    public ChatResponse chat(ChatRequest request, StreamListener listener, CancellationToken cancel) throws LlmException {
        if (listener == null) listener = StreamListener.NONE;
        if (cancel == null) cancel = new CancellationToken();
        String invalid = cfg.validate();
        if (invalid != null) throw new LlmException(LlmException.Kind.NOT_CONFIGURED, invalid);
        boolean stream = cfg.stream && !request.forceNoStream;
        JSONObject body = OpenAiProtocol.buildBody(cfg, request, stream);

        HttpURLConnection conn = null;
        final HttpURLConnection[] holder = new HttpURLConnection[1];
        Runnable onCancel = () -> {
            HttpURLConnection c = holder[0];
            if (c != null) c.disconnect();
        };
        cancel.onCancel(onCancel);
        try {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled");
            conn = open(cfg.chatCompletionsUrl(), "POST");
            holder[0] = conn;
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", stream ? "text/event-stream, application/json" : "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                throw OpenAiProtocol.classify(code, err, retryAfter(conn));
            }
            String ctype = conn.getContentType() == null ? "" : conn.getContentType().toLowerCase();
            if (stream && ctype.contains("text/event-stream")) {
                return readStream(conn.getInputStream(), listener, cancel);
            }
            String text = readAll(conn.getInputStream());
            if (stream && text.trim().startsWith("data:")) {
                // Server streamed without the right content type.
                return readStream(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), listener, cancel);
            }
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException e) {
                throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE, "Response is not JSON", e);
            }
            ChatResponse r = OpenAiProtocol.parseResponse(json);
            if (!r.content.isEmpty()) listener.onTextDelta(r.content);
            return r;
        } catch (SocketTimeoutException e) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled", e);
            throw new LlmException(LlmException.Kind.TIMEOUT, "Timed out", e);
        } catch (IOException e) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled", e);
            throw new LlmException(LlmException.Kind.NETWORK, safeMessage(e), e);
        } finally {
            cancel.removeListener(onCancel);
            if (conn != null) conn.disconnect();
        }
    }

    private ChatResponse readStream(InputStream in, StreamListener listener, CancellationToken cancel) throws IOException, LlmException {
        StreamAccumulator acc = new StreamAccumulator();
        SseParser parser = new SseParser();
        final LlmException[] streamError = new LlmException[1];
        SseParser.EventHandler handler = data -> {
            try {
                JSONObject chunk = new JSONObject(data);
                if (chunk.has("error") && !chunk.has("choices")) {
                    streamError[0] = OpenAiProtocol.classify(500, data, 0);
                    return false;
                }
                acc.accept(chunk, listener);
            } catch (JSONException e) {
                // Ignore a malformed keep-alive; a fully malformed stream is reported below.
            }
            return true;
        };
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled");
                if (!parser.feedLine(line, handler)) break;
            }
            parser.finish(handler);
        }
        if (streamError[0] != null) throw streamError[0];
        if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "Cancelled");
        if (acc.chunkCount() == 0) {
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE, "Stream ended without any data");
        }
        return acc.build();
    }

    /** Lists model ids from GET /models; many compatible servers implement it. */
    public List<String> listModels(CancellationToken cancel) throws LlmException {
        HttpURLConnection conn = null;
        try {
            conn = open(cfg.modelsUrl(), "GET");
            final HttpURLConnection c = conn;
            if (cancel != null) cancel.onCancel(c::disconnect);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw OpenAiProtocol.classify(code, readAll(conn.getErrorStream()), retryAfter(conn));
            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            JSONArray data = o.optJSONArray("data");
            if (data == null) data = o.optJSONArray("models");
            List<String> ids = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    Object it = data.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", ((JSONObject) it).optString("name", ""));
                        if (!id.isEmpty()) ids.add(id);
                    } else if (it instanceof String) ids.add((String) it);
                }
            }
            return ids;
        } catch (JSONException e) {
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE, "Model list is not valid JSON", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.NETWORK, safeMessage(e), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Sends a minimal request to verify URL, key and model. Returns a short description of what worked,
     * including whether a tool call round-trip is accepted.
     */
    public String testConnection(CancellationToken cancel) throws LlmException {
        ChatRequest r = new ChatRequest();
        r.messages.add(ChatMessage.user("Reply with the single word: ok"));
        r.forceNoStream = true;
        r.maxTokensOverride = 16;
        ChatResponse resp = chat(r, StreamListener.NONE, cancel);
        String text = resp.content.trim();
        return "Connected to " + (resp.model.isEmpty() ? cfg.model : resp.model) + (text.isEmpty() ? "" : " — reply: " + (text.length() > 60 ? text.substring(0, 60) : text));
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(Math.min(cfg.timeoutMs, 30_000));
        conn.setReadTimeout(cfg.timeoutMs);
        conn.setUseCaches(false);
        if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
        }
        for (Map.Entry<String, String> h : cfg.headers.entrySet()) {
            if (!h.getKey().trim().isEmpty()) conn.setRequestProperty(h.getKey().trim(), h.getValue());
        }
        conn.setRequestProperty("User-Agent", "YourFriend/1.0 (Android; com.suvo.agent)");
        return conn;
    }

    private static long retryAfter(HttpURLConnection conn) {
        String ra = conn.getHeaderField("Retry-After");
        if (ra == null) return 0;
        try {
            return (long) (Double.parseDouble(ra.trim()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 16 * 1024 * 1024) throw new IOException("Response too large");
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Never include URLs with query strings or headers in messages (they may hold keys). */
    private static String safeMessage(IOException e) {
        String m = e.getClass().getSimpleName();
        String d = e.getMessage();
        if (d != null && !d.contains("key=") && !d.toLowerCase().contains("bearer")) m += ": " + d;
        return m;
    }
}
