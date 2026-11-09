package com.suvo.agent.plugins;

import android.content.Context;
import android.webkit.CookieManager;

import com.suvo.agent.connectors.WebSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * Native requests to a site's own (internal) API as the logged-in user: the cookies are the ones of
 * the agent's browser (the user logs in there once, on this phone), the user agent is the browser's,
 * and cookies the site sets are written back, so browser and plugin stay one session. Requests go
 * out from the phone itself, at a human pace (one per site at a time, with a small random gap).
 */
public final class SiteHttp {

    /** One answer. */
    public static final class Response {
        public final int status;
        public final String body;
        public final Map<String, List<String>> headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final Context ctx;
    /** Cookie origin, e.g. https://www.instagram.com */
    private final String origin;
    private final long minGapMs;
    private long last;

    public SiteHttp(Context ctx, String origin, long minGapMs) {
        this.ctx = ctx.getApplicationContext();
        this.origin = origin;
        this.minGapMs = minGapMs;
    }

    /** A cookie of the site (as the browser has it), or null. */
    public String cookie(String name) {
        String all = CookieManager.getInstance().getCookie(origin);
        if (all == null) return null;
        for (String kv : all.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).trim().equals(name)) return kv.substring(eq + 1).trim();
        }
        return null;
    }

    public String userAgent() {
        return WebSession.userAgent(ctx, null);
    }

    public static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException ignored) {
            // UTF-8 always exists.
        }
        return sb.toString();
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public Response get(String url, Map<String, String> headers) throws IOException {
        return send("GET", url, headers, null, null);
    }

    public Response postForm(String url, Map<String, String> headers, Map<String, String> fields) throws IOException {
        return send("POST", url, headers, form(fields).getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
    }

    public Response postJson(String url, Map<String, String> headers, String json) throws IOException {
        return send("POST", url, headers, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** Any request: body may be null. */
    public synchronized Response send(String method, String url, Map<String, String> headers, byte[] body, String contentType)
            throws IOException {
        pace();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20_000);
        c.setReadTimeout(90_000);
        c.setInstanceFollowRedirects(true);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);
        c.setRequestProperty("User-Agent", userAgent());
        c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8");
        c.setRequestProperty("Origin", origin);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
        }
        int code = c.getResponseCode();
        // The site's new cookies go to the browser too: one session for both.
        List<String> set = c.getHeaderFields().get("Set-Cookie");
        if (set == null) set = c.getHeaderFields().get("set-cookie");
        if (set != null) {
            CookieManager cm = CookieManager.getInstance();
            for (String s : set) cm.setCookie(url, s);
            cm.flush();
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            try (InputStream is = in) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 12 * 1024 * 1024) break;
                }
                text = bo.toString("UTF-8");
            }
        }
        Map<String, List<String>> hdrs = c.getHeaderFields();
        c.disconnect();
        return new Response(code, text, hdrs);
    }

    /** One request at a time per site, with a human-like gap. */
    private void pace() {
        long gap = minGapMs + (long) (Math.random() * minGapMs * 0.6);
        long wait = last + gap - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        last = System.currentTimeMillis();
    }
}
