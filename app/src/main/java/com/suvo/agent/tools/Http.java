package com.suvo.agent.tools;

import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.core.util.UrlSafety;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Small HTTP client for web tools: manual redirects (each hop re-checked), size caps, cancellation. */
public final class Http {

    public static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36 YourFriend/1.0";

    public static final class Response {
        public int code;
        public String contentType = "";
        public byte[] body = new byte[0];
        public String finalUrl = "";
        public long contentLength = -1;

        public String text() {
            Charset cs = StandardCharsets.UTF_8;
            String ct = contentType.toLowerCase(Locale.ROOT);
            int i = ct.indexOf("charset=");
            if (i >= 0) {
                try {
                    cs = Charset.forName(ct.substring(i + 8).replace("\"", "").split(";")[0].trim());
                } catch (RuntimeException ignored) {
                }
            }
            return new String(body, cs);
        }
    }

    public interface Progress {
        void onProgress(long bytes, long total);
    }

    private Http() {
    }

    public static Response get(String url, Map<String, String> headers, int maxBytes, int timeoutMs, boolean allowLan,
                               CancellationToken cancel) throws IOException {
        return request("GET", url, headers, null, null, maxBytes, timeoutMs, allowLan, cancel);
    }

    public static Response request(String method, String url, Map<String, String> headers, byte[] body, String bodyType,
                                   int maxBytes, int timeoutMs, boolean allowLan, CancellationToken cancel) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            String why = UrlSafety.check(current, allowLan);
            if (why != null) throw new IOException(why);
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            Runnable kill = c::disconnect;
            if (cancel != null) cancel.onCancel(kill);
            try {
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod(method);
                c.setConnectTimeout(Math.min(timeoutMs, 20_000));
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8");
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if (body != null) {
                    c.setDoOutput(true);
                    if (bodyType != null) c.setRequestProperty("Content-Type", bodyType);
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body);
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                    if (code == 303) {
                        method = "GET";
                        body = null;
                    }
                    continue;
                }
                Response r = new Response();
                r.code = code;
                r.finalUrl = current;
                r.contentType = c.getContentType() == null ? "" : c.getContentType();
                r.contentLength = c.getContentLengthLong();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                r.body = in == null ? new byte[0] : read(in, maxBytes, cancel);
                return r;
            } finally {
                if (cancel != null) cancel.removeListener(kill);
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] read(InputStream in, int max, CancellationToken cancel) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("Cancelled");
                bos.write(buf, 0, Math.min(n, Math.max(0, max - bos.size())));
                if (bos.size() >= max) break;
            }
            return bos.toByteArray();
        }
    }

    /** Streams a URL to a file with a hard size limit. Returns bytes written. */
    public static long download(String url, File dest, long maxBytes, int timeoutMs, boolean allowLan,
                                CancellationToken cancel, Progress progress) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            String why = UrlSafety.check(current, allowLan);
            if (why != null) throw new IOException(why);
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            Runnable kill = c::disconnect;
            if (cancel != null) cancel.onCancel(kill);
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20_000);
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("User-Agent", UA);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                    continue;
                }
                if (code >= 400) throw new IOException("HTTP " + code);
                long total = c.getContentLengthLong();
                if (total > maxBytes) throw new IOException("File is larger than the limit (" + (maxBytes / 1048576) + " MB)");
                long written = 0;
                File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
                try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long lastReport = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.isCancelled()) throw new IOException("Cancelled");
                        written += n;
                        if (written > maxBytes) throw new IOException("Download exceeded the size limit");
                        fos.write(buf, 0, n);
                        if (progress != null && written - lastReport > 512 * 1024) {
                            lastReport = written;
                            progress.onProgress(written, total);
                        }
                    }
                } catch (IOException e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    throw e;
                }
                if (dest.exists() && !dest.delete()) throw new IOException("Could not replace " + dest.getName());
                if (!tmp.renameTo(dest)) throw new IOException("Could not finalize download");
                return written;
            } finally {
                if (cancel != null) cancel.removeListener(kill);
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
