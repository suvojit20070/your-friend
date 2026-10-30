package com.suvo.agent.connectors;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import com.suvo.agent.AppGraph;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * "Sign in with …" for OAuth connectors, the way desktop apps do it (RFC 8252): the sign-in page
 * opens in the user's browser (Google does not allow sign-in inside an app's WebView), and the
 * service sends the browser back to a tiny one-shot server on this phone (http://127.0.0.1:port).
 * PKCE protects the code; the access and refresh tokens are then stored encrypted. Later calls
 * refresh the access token by themselves.
 *
 * For Google the user creates a free OAuth client of type "Desktop app" in Google Cloud Console
 * (and adds their own address as a test user); its client ID and secret go into the connector.
 */
public final class OAuthFlow {

    public interface Callback {
        /** Main thread not guaranteed. */
        void onDone(Connector c, String error);
    }

    private OAuthFlow() {
    }

    private static final SecureRandom RND = new SecureRandom();

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * Opens the sign-in page and waits (on a background thread, up to 5 minutes) for the browser
     * to come back; then exchanges the code, saves the connector and calls {@code cb}.
     */
    public static void start(Context ctx, Connector c, Callback cb) {
        Context app = ctx.getApplicationContext();
        ServerSocket server;
        try {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            cb.onDone(c, "Could not start the sign-in listener: " + e.getMessage());
            return;
        }
        String redirect = "http://127.0.0.1:" + server.getLocalPort();
        String verifier = random(48);
        String state = random(16);
        String challenge;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            challenge = Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            cb.onDone(c, e.getMessage());
            return;
        }
        StringBuilder url = new StringBuilder(c.authUrl);
        url.append(c.authUrl.contains("?") ? '&' : '?')
                .append("response_type=code")
                .append("&client_id=").append(enc(c.clientId))
                .append("&redirect_uri=").append(enc(redirect))
                .append("&scope=").append(enc(c.scopes.trim()))
                .append("&state=").append(enc(state))
                .append("&code_challenge=").append(enc(challenge))
                .append("&code_challenge_method=S256");
        if (ConnectorPresets.isGoogle(c)) url.append("&access_type=offline&prompt=consent&include_granted_scopes=true");

        Thread t = new Thread(() -> {
            String error = null;
            try (ServerSocket ss = server) {
                ss.setSoTimeout(5 * 60_000);
                String code = null;
                // The browser may also ask for /favicon.ico: keep listening until the real answer.
                for (int i = 0; i < 6 && code == null && error == null; i++) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(10_000);
                        Map<String, String> q = readQuery(s.getInputStream());
                        if (q == null) {
                            reply(s.getOutputStream(), 404, "");
                            continue;
                        }
                        if (q.containsKey("error")) {
                            error = "Sign-in refused: " + q.get("error");
                        } else if (!state.equals(q.get("state"))) {
                            error = "Sign-in answer did not match (state)";
                        } else {
                            code = q.get("code");
                            if (code == null) error = "No sign-in code in the answer";
                        }
                        reply(s.getOutputStream(), 200, page(error == null
                                ? "Connected. You can go back to Your Friend."
                                : "Sign-in did not work: " + error));
                    }
                }
                if (error == null && code == null) error = "Sign-in was not finished";
                if (error == null) {
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("grant_type", "authorization_code");
                    form.put("code", code);
                    form.put("redirect_uri", redirect);
                    form.put("client_id", c.clientId);
                    if (!c.clientSecret.isEmpty()) form.put("client_secret", c.clientSecret);
                    form.put("code_verifier", verifier);
                    applyTokens(c, postForm(c.tokenUrl, form));
                    fillAccount(c);
                    AppGraph.get().connectors.save(c);
                    bringBack(app);
                }
            } catch (SocketTimeoutException e) {
                error = "Sign-in timed out";
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            cb.onDone(c, error);
        }, "yf-oauth");
        t.setDaemon(true);
        t.start();
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(open);
        } catch (RuntimeException e) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            cb.onDone(c, "No browser to open the sign-in page");
        }
    }

    /** Back to the Connectors screen after the browser sign-in (allowed while the app may draw overlays). */
    private static void bringBack(Context app) {
        try {
            Intent i = new Intent(app, com.suvo.agent.ui.ConnectorsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
        } catch (RuntimeException ignored) {
            // Android blocked it: the page in the browser tells the user to go back.
        }
    }

    /** A valid access token, refreshed when it is about to run out. Blocking (network). */
    public static synchronized String accessToken(Connector c) throws IOException {
        boolean fresh = !c.token.isEmpty() && (c.expiresAt == 0 || System.currentTimeMillis() < c.expiresAt - 60_000);
        if (fresh) return c.token;
        if (c.refreshToken.isEmpty()) {
            if (!c.token.isEmpty()) return c.token;
            throw new IOException("Not signed in to " + c.name + " — reconnect it in Connectors");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", c.refreshToken);
        form.put("client_id", c.clientId);
        if (!c.clientSecret.isEmpty()) form.put("client_secret", c.clientSecret);
        try {
            applyTokens(c, postForm(c.tokenUrl, form));
        } catch (Exception e) {
            throw new IOException("Could not refresh the sign-in of " + c.name + ": " + e.getMessage()
                    + " (reconnect it in Connectors)", e);
        }
        AppGraph.get().connectors.save(c);
        return c.token;
    }

    private static void applyTokens(Connector c, JSONObject o) throws IOException {
        String at = o.optString("access_token", "");
        if (at.isEmpty()) throw new IOException(o.optString("error_description", o.optString("error", "no access token")));
        c.token = at;
        String rt = o.optString("refresh_token", "");
        if (!rt.isEmpty()) c.refreshToken = rt;
        long exp = o.optLong("expires_in", 0);
        c.expiresAt = exp > 0 ? System.currentTimeMillis() + exp * 1000 : 0;
    }

    /** Google: who signed in (email). Best effort. */
    private static void fillAccount(Connector c) {
        if (!ConnectorPresets.isGoogle(c)) return;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://openidconnect.googleapis.com/v1/userinfo").openConnection();
            h.setRequestProperty("Authorization", "Bearer " + c.token);
            h.setConnectTimeout(15_000);
            h.setReadTimeout(15_000);
            if (h.getResponseCode() == 200) {
                JSONObject o = new JSONObject(readAll(h.getInputStream()));
                String email = o.optString("email", "");
                if (!email.isEmpty()) c.account = email;
            }
            h.disconnect();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject postForm(String tokenUrl, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpURLConnection h = (HttpURLConnection) new URL(tokenUrl).openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(20_000);
            h.setReadTimeout(30_000);
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            h.setRequestProperty("Accept", "application/json");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = h.getOutputStream()) {
                os.write(b);
            }
            int code = h.getResponseCode();
            String text = readAll(code >= 400 ? h.getErrorStream() : h.getInputStream());
            JSONObject o;
            try {
                o = new JSONObject(text);
            } catch (Exception e) {
                // Some services answer form-encoded.
                o = new JSONObject();
                for (String part : text.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) o.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
                }
            }
            if (code >= 400 && !o.has("error")) o.put("error", "HTTP " + code);
            return o;
        } finally {
            h.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0 && bos.size() < 2_000_000) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    /** The query of "GET /?a=b HTTP/1.1"; null for anything else (favicon…). */
    private static Map<String, String> readQuery(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n' && line.length() < 8192) line.append((char) ch);
        String l = line.toString().trim();
        if (!l.startsWith("GET ")) return null;
        int sp = l.indexOf(' ', 4);
        String target = sp > 0 ? l.substring(4, sp) : l.substring(4);
        int qm = target.indexOf('?');
        if (qm < 0) return null;
        String path = target.substring(0, qm);
        if (!path.equals("/") && !path.isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : target.substring(qm + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
        }
        return out;
    }

    private static String page(String msg) {
        String safe = msg.replace("&", "&amp;").replace("<", "&lt;");
        return "<!doctype html><meta name=viewport content='width=device-width'><title>Your Friend</title>"
                + "<body style='font-family:sans-serif;padding:32px;text-align:center'><h2>" + safe + "</h2></body>";
    }

    private static void reply(OutputStream os, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        os.write(head.getBytes(StandardCharsets.US_ASCII));
        os.write(b);
        os.flush();
    }
}
