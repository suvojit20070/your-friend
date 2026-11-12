package com.suvo.agent.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Your Friend — Original project by Suvo.
 *
 * A Model Context Protocol client over "Streamable HTTP": every message is a JSON-RPC POST to the
 * server's URL; the answer comes back as JSON, or as a short server-sent-event stream whose data
 * lines carry the JSON-RPC response. A session id the server gives (Mcp-Session-Id) goes on every
 * later request. Supports initialize, tools/list (all pages) and tools/call.
 */
public final class McpClient {

    public static final String PROTOCOL = "2025-03-26";

    private final String url;
    private final Map<String, String> headers;
    private final AtomicInteger ids = new AtomicInteger();
    private String session;
    private boolean initialized;
    public String serverName = "", serverVersion = "", instructions = "";

    public McpClient(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }

    /** Thrown for a JSON-RPC error answer. */
    public static final class McpException extends IOException {
        public final int code;

        McpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private synchronized void ensureInit() throws IOException {
        if (initialized) return;
        JSONObject params = new JSONObject();
        try {
            params.put("protocolVersion", PROTOCOL);
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject().put("name", "Your Friend").put("version", "1.0"));
        } catch (JSONException ignored) {
        }
        JSONObject r = request("initialize", params, 30_000);
        JSONObject info = r.optJSONObject("serverInfo");
        if (info != null) {
            serverName = info.optString("name");
            serverVersion = info.optString("version");
        }
        instructions = r.optString("instructions");
        notify("notifications/initialized");
        initialized = true;
    }

    /** Every tool the server offers ({name, description, inputSchema, annotations}). */
    public JSONArray listTools() throws IOException {
        ensureInit();
        JSONArray all = new JSONArray();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JSONObject params = new JSONObject();
            try {
                if (cursor != null) params.put("cursor", cursor);
            } catch (JSONException ignored) {
            }
            JSONObject r = request("tools/list", params, 30_000);
            JSONArray tools = r.optJSONArray("tools");
            for (int i = 0; tools != null && i < tools.length(); i++) all.put(tools.opt(i));
            cursor = r.optString("nextCursor", "");
            if (cursor.isEmpty()) break;
        }
        return all;
    }

    /** Calls a tool. The result: {content:[…], isError, structuredContent?}. */
    public JSONObject callTool(String name, JSONObject arguments, long timeoutMs) throws IOException {
        ensureInit();
        JSONObject params = new JSONObject();
        try {
            params.put("name", name);
            params.put("arguments", arguments == null ? new JSONObject() : arguments);
        } catch (JSONException ignored) {
        }
        try {
            return request("tools/call", params, timeoutMs);
        } catch (McpException e) {
            throw e;
        } catch (IOException e) {
            // The session may have expired (server restarted): start again once.
            if (session != null) {
                synchronized (this) {
                    session = null;
                    initialized = false;
                }
                ensureInit();
                return request("tools/call", params, timeoutMs);
            }
            throw e;
        }
    }

    private void notify(String method) {
        try {
            JSONObject msg = new JSONObject().put("jsonrpc", "2.0").put("method", method);
            HttpURLConnection c = open(15_000);
            send(c, msg);
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {
            // Notifications have no answer; a server that rejects it still works.
        }
    }

    private HttpURLConnection open(long timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20_000);
        c.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(10_000, timeoutMs)));
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json, text/event-stream");
        c.setRequestProperty("MCP-Protocol-Version", PROTOCOL);
        if (session != null) c.setRequestProperty("Mcp-Session-Id", session);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        return c;
    }

    private static void send(HttpURLConnection c, JSONObject msg) throws IOException {
        byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
    }

    private JSONObject request(String method, JSONObject params, long timeoutMs) throws IOException {
        int id = ids.incrementAndGet();
        JSONObject msg;
        try {
            msg = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = open(timeoutMs);
        try {
            send(c, msg);
            int code = c.getResponseCode();
            String sid = c.getHeaderField("Mcp-Session-Id");
            if (sid != null && !sid.isEmpty()) session = sid;
            if (code == 401 || code == 403) throw new IOException("The server refused the login (HTTP " + code + "): check the token / header");
            if (code == 404 && session != null) throw new IOException("Session expired (HTTP 404)");
            if (code >= 400) throw new IOException("HTTP " + code + ": " + read(c.getErrorStream(), 400));
            String type = String.valueOf(c.getContentType()).toLowerCase(java.util.Locale.ROOT);
            JSONObject answer = type.contains("text/event-stream") ? fromStream(c.getInputStream(), id) : parse(read(c.getInputStream(), 8_000_000));
            if (answer == null) throw new IOException("The server sent no answer for " + method);
            JSONObject err = answer.optJSONObject("error");
            if (err != null) throw new McpException(err.optInt("code"), err.optString("message", "error"));
            JSONObject result = answer.optJSONObject("result");
            return result == null ? new JSONObject() : result;
        } finally {
            c.disconnect();
        }
    }

    /** An SSE stream: the data lines of the message that answers {@code id}. */
    private static JSONObject fromStream(InputStream in, int id) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty() && data.length() > 0) {
                    JSONObject o = parse(data.toString());
                    data.setLength(0);
                    if (o != null && o.optInt("id", -1) == id && (o.has("result") || o.has("error"))) return o;
                }
            }
            if (data.length() > 0) {
                JSONObject o = parse(data.toString());
                if (o != null && o.optInt("id", -1) == id) return o;
            }
        }
        return null;
    }

    private static JSONObject parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        try {
            if (t.startsWith("[")) {
                // A batch: the first response in it.
                JSONArray a = new JSONArray(t);
                return a.length() == 0 ? null : a.optJSONObject(0);
            }
            return t.isEmpty() ? null : new JSONObject(t);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String read(InputStream in, int max) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < max) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
