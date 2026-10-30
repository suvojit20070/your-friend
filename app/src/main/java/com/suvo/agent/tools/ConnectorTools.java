package com.suvo.agent.tools;

import android.graphics.Bitmap;

import com.suvo.agent.AppGraph;
import com.suvo.agent.connectors.Connector;
import com.suvo.agent.connectors.ConnectorPresets;
import com.suvo.agent.connectors.EmailClient;
import com.suvo.agent.connectors.OAuthFlow;
import com.suvo.agent.connectors.WebSession;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.device.ImageUtil;
import com.suvo.agent.files.FileAccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * The agent's access to the accounts the user connected (Settings → Connectors):
 * <ul>
 * <li>{@code connectors} — which accounts there are, how to use each, and a health check.</li>
 * <li>{@code connector_api} — calls a connected service's API as the user (Gmail, YouTube,
 * Drive, Calendar, a Telegram bot, GitHub, Notion, any API); the credentials are added here and
 * never shown to the model.</li>
 * <li>{@code email} — a connected mailbox: list, search, read, attachments, send, reply, mark,
 * move, delete.</li>
 * <li>{@code web_session} — a website the user logged in to (Instagram, Facebook, X, WhatsApp
 * Web…): open pages, read them, run JavaScript (click/type/scroll), fetch from inside the page,
 * take a picture.</li>
 * </ul>
 * Each tool is only offered while an account of its kind is connected.
 */
public final class ConnectorTools {

    private ConnectorTools() {
    }

    static Connector need(AppGraph g, ToolInvocation inv, String type) throws IOException {
        String name = inv.str("connector").trim();
        Connector c = g.connectors.find(name);
        if (c == null && name.isEmpty()) {
            // Only one of this kind: use it.
            Connector only = null;
            for (Connector x : g.connectors.list()) {
                if (type == null || x.type.equals(type) || (type.equals(Connector.TOKEN) && x.type.equals(Connector.OAUTH))) {
                    if (only != null) {
                        only = null;
                        break;
                    }
                    only = x;
                }
            }
            c = only;
        }
        if (c == null) throw new IOException("No connected account \"" + name + "\". Call connectors (action list) to see the names.");
        if (type != null) {
            boolean ok = c.type.equals(type) || (type.equals(Connector.TOKEN) && c.type.equals(Connector.OAUTH));
            if (!ok) throw new IOException(c.name + " is a " + c.type + " connector; use " + toolFor(c) + " for it");
        }
        return c;
    }

    static String toolFor(Connector c) {
        switch (c.type) {
            case Connector.EMAIL:
                return "email";
            case Connector.WEB:
                return "web_session";
            default:
                return "connector_api";
        }
    }

    static boolean has(AppGraph g, String... types) {
        for (Connector c : g.connectors.list()) {
            for (String t : types) if (c.type.equals(t)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------

    public static final class Connectors extends BaseTool {
        public Connectors(AppGraph g) {
            super(g, ToolSpec.builder("connectors")
                    .description("The accounts the user connected for you (Gmail, YouTube, Google, Telegram, Instagram, any API or website): "
                            + "action list shows each account's name, kind, the tool to use and what its API offers; action check "
                            + "tests one (sign-in still valid, servers reachable).")
                    .schema("{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"check\"],\"default\":\"list\"},"
                            + "\"connector\":{\"type\":\"string\",\"description\":\"Account name for check\"}},\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.READ_ONLY).backend("connectors").timeoutMs(60_000).build());
        }

        @Override
        public String unavailableReason() {
            return g.connectors.list().isEmpty() ? "no accounts connected (Settings → Connectors)" : null;
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            if ("check".equals(inv.str("action", "list"))) {
                Connector c = need(g, inv, null);
                String problem = check(g, c);
                return problem == null ? ToolResult.ok(c.name + " works" + (c.account.isEmpty() ? "" : " (" + c.account + ")"))
                        : ToolResult.fail(c.name + ": " + problem);
            }
            JSONArray out = new JSONArray();
            for (Connector c : g.connectors.list()) {
                JSONObject o = J.obj("name", c.name, "service", ConnectorPresets.title(c.service), "kind", c.type,
                        "account", c.account, "tool", toolFor(c), "signed_in", c.isConnected());
                if (!c.baseUrl.isEmpty()) J.put(o, "api_base", c.baseUrl);
                if (!c.homeUrl.isEmpty()) J.put(o, "site", c.homeUrl);
                if (!c.hosts.isEmpty()) J.put(o, "hosts", c.hosts);
                if (!c.apiHint.isEmpty()) J.put(o, "how_to", c.apiHint);
                out.put(o);
            }
            return ToolResult.ok(out.length() + " connected account(s)", J.obj("accounts", out));
        }
    }

    /** Null when the account works, else what is wrong. Blocking. */
    public static String check(AppGraph g, Connector c) {
        try {
            switch (c.type) {
                case Connector.EMAIL:
                    return new EmailClient(c).test();
                case Connector.WEB:
                    return WebSession.hasCookies(c) ? null : "not logged in (open it in Connectors and log in)";
                case Connector.OAUTH: {
                    OAuthFlow.accessToken(c);
                    if (ConnectorPresets.isGoogle(c)) {
                        Resp r = Api.call(c, "GET", "https://openidconnect.googleapis.com/v1/userinfo", null, null, null, 200_000, true);
                        return r.code == 200 ? null : "HTTP " + r.code + ": " + r.text(300);
                    }
                    return null;
                }
                default: {
                    if ("telegram_bot".equals(c.service)) {
                        Resp r = Api.call(c, "GET", "getMe", null, null, null, 200_000, false);
                        return r.code == 200 ? null : "HTTP " + r.code + ": " + r.text(300);
                    }
                    return c.isConnected() ? null : "no token";
                }
            }
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    // ------------------------------------------------------------------------------------------

    static final class Resp {
        int code;
        String contentType = "";
        String location = "";
        byte[] body = new byte[0];

        String text(int max) {
            String s = new String(body, StandardCharsets.UTF_8);
            return s.length() > max ? s.substring(0, max) + "\n… (cut, " + s.length() + " chars)" : s;
        }

        boolean isText() {
            String t = contentType.toLowerCase(Locale.ROOT);
            return t.isEmpty() || t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("javascript")
                    || t.contains("x-www-form-urlencoded");
        }
    }

    /** Authenticated HTTPS calls for API connectors; redirects are not followed (no token leaks). */
    static final class Api {

        static String resolve(Connector c, String pathOrUrl) throws IOException {
            String p = pathOrUrl == null ? "" : pathOrUrl.trim();
            String url;
            if (p.startsWith("http://") || p.startsWith("https://")) {
                url = p;
            } else {
                String base = c.baseUrl;
                if (base.isEmpty()) throw new IOException(c.name + " has no API address; give a full https:// URL");
                if (p.startsWith("/") && base.endsWith("/")) p = p.substring(1);
                url = base + p;
            }
            if ("path".equals(c.authStyle)) url = url.replace("{token}", c.token);
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) throw new IOException("Only https:// for connected accounts");
            if (!c.allowsHost(u.getHost())) {
                throw new IOException(u.getHost() + " is not one of " + c.name + "'s hosts (" + c.hosts + ")");
            }
            return url;
        }

        static Resp call(Connector c, String method, String pathOrUrl, JSONObject headers, byte[] body, String bodyType,
                         int maxBytes, boolean oauthBearer) throws IOException {
            String url = resolve(c, pathOrUrl);
            String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
            if ("query".equals(c.authStyle) && !c.token.isEmpty()) {
                url += (url.contains("?") ? "&" : "?") + URLEncoder.encode(c.authName.isEmpty() ? "key" : c.authName, "UTF-8")
                        + "=" + URLEncoder.encode(c.token, "UTF-8");
            }
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            try {
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(20_000);
                h.setReadTimeout(60_000);
                try {
                    h.setRequestMethod(m);
                } catch (ProtocolException e) {
                    h.setRequestMethod("POST");
                    h.setRequestProperty("X-HTTP-Method-Override", m);
                }
                h.setRequestProperty("User-Agent", Http.UA);
                h.setRequestProperty("Accept", "application/json, */*");
                if (headers != null) {
                    Iterator<String> it = headers.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (k.equalsIgnoreCase("authorization")) continue; // added below, never by the model
                        h.setRequestProperty(k, headers.optString(k));
                    }
                }
                if (Connector.OAUTH.equals(c.type) || oauthBearer) {
                    h.setRequestProperty("Authorization", "Bearer " + OAuthFlow.accessToken(c));
                } else if ("bearer".equals(c.authStyle) && !c.token.isEmpty()) {
                    h.setRequestProperty("Authorization", "Bearer " + c.token);
                } else if ("header".equals(c.authStyle) && !c.token.isEmpty()) {
                    h.setRequestProperty(c.authName.isEmpty() ? "X-API-Key" : c.authName, c.token);
                }
                if (body != null) {
                    h.setDoOutput(true);
                    if (bodyType != null) h.setRequestProperty("Content-Type", bodyType);
                    h.setFixedLengthStreamingMode(body.length);
                    try (OutputStream os = h.getOutputStream()) {
                        os.write(body);
                    }
                }
                Resp r = new Resp();
                r.code = h.getResponseCode();
                r.contentType = h.getContentType() == null ? "" : h.getContentType();
                r.location = h.getHeaderField("Location") == null ? "" : h.getHeaderField("Location");
                InputStream in = r.code >= 400 ? h.getErrorStream() : h.getInputStream();
                r.body = read(in, maxBytes);
                return r;
            } finally {
                h.disconnect();
            }
        }

        static byte[] read(InputStream in, int max) throws IOException {
            if (in == null) return new byte[0];
            try (InputStream is = in) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, Math.min(n, Math.max(0, max - bos.size())));
                    if (bos.size() >= max) break;
                }
                return bos.toByteArray();
            }
        }
    }

    public static final class ConnectorApi extends BaseTool {
        public ConnectorApi(AppGraph g) {
            super(g, ToolSpec.builder("connector_api")
                    .description("Call the API of a connected account as the user (Gmail/YouTube/Drive/Calendar via Google sign-in, "
                            + "Telegram bot, GitHub, Notion, any API the user added). The sign-in / key is added automatically — never "
                            + "ask for it. path: relative to the account's API address (see connectors list → api_base and how_to) "
                            + "or a full https URL on its hosts. Send JSON with json, a form with form, uploads with files "
                            + "(field → workspace path), raw text with body. Binary answers can be saved with save_to (workspace path).")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"connector\":{\"type\":\"string\",\"description\":\"Account name from connectors list\"},"
                            + "\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"POST\",\"PUT\",\"PATCH\",\"DELETE\"],\"default\":\"GET\"},"
                            + "\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":4000},"
                            + "\"query\":{\"type\":\"object\",\"description\":\"URL query parameters\"},"
                            + "\"headers\":{\"type\":\"object\"},"
                            + "\"json\":{\"type\":\"object\",\"description\":\"JSON body\"},"
                            + "\"form\":{\"type\":\"object\",\"description\":\"Form fields (urlencoded, or multipart with files)\"},"
                            + "\"files\":{\"type\":\"object\",\"description\":\"Uploads: form field → workspace file path\"},"
                            + "\"body\":{\"type\":\"string\",\"description\":\"Raw body (e.g. a JSON array)\"},"
                            + "\"content_type\":{\"type\":\"string\"},"
                            + "\"save_to\":{\"type\":\"string\",\"description\":\"Save the answer to this workspace path\"},"
                            + "\"max_chars\":{\"type\":\"integer\",\"minimum\":500,\"maximum\":200000,\"default\":20000}"
                            + "},\"required\":[\"connector\",\"path\"],\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.MEDIUM).backend("connectors").timeoutMs(120_000).build());
        }

        @Override
        public String unavailableReason() {
            return has(g, Connector.TOKEN, Connector.OAUTH) ? null : "no API accounts connected (Settings → Connectors)";
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String m = args.optString("method", "GET").toUpperCase(Locale.ROOT);
            if (m.equals("GET")) return RiskLevel.READ_ONLY;
            if (m.equals("DELETE")) return RiskLevel.HIGH;
            return RiskLevel.MEDIUM;
        }

        @Override
        public String describeCall(JSONObject args) {
            return args.optString("method", "GET") + " " + args.optString("path") + " as " + args.optString("connector");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            Connector c = need(g, inv, Connector.TOKEN);
            String path = inv.str("path");
            JSONObject q = inv.args.optJSONObject("query");
            if (q != null && q.length() > 0) {
                StringBuilder sb = new StringBuilder(path);
                Iterator<String> it = q.keys();
                boolean first = !path.contains("?");
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = q.get(k);
                    List<Object> vals = new ArrayList<>();
                    if (v instanceof JSONArray) for (int i = 0; i < ((JSONArray) v).length(); i++) vals.add(((JSONArray) v).get(i));
                    else vals.add(v);
                    for (Object one : vals) {
                        sb.append(first ? '?' : '&').append(URLEncoder.encode(k, "UTF-8")).append('=')
                                .append(URLEncoder.encode(String.valueOf(one), "UTF-8"));
                        first = false;
                    }
                }
                path = sb.toString();
            }
            byte[] body = null;
            String type = inv.str("content_type", null);
            JSONObject json = inv.args.optJSONObject("json");
            JSONObject form = inv.args.optJSONObject("form");
            JSONObject files = inv.args.optJSONObject("files");
            FileAccess fa = new FileAccess(g.workspace, g.saf);
            if (files != null && files.length() > 0) {
                String boundary = "----YourFriend" + Long.toHexString(System.nanoTime());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (form != null) {
                    Iterator<String> it = form.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object v = form.get(k);
                        bos.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + k + "\"\r\n\r\n"
                                + (v instanceof JSONObject || v instanceof JSONArray ? v.toString() : String.valueOf(v)) + "\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
                Iterator<String> it = files.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String p = files.getString(k);
                    byte[] data = fa.read(p, 50 * 1024 * 1024);
                    String fname = p.substring(p.lastIndexOf('/') + 1);
                    bos.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + k + "\"; filename=\"" + fname
                            + "\"\r\nContent-Type: " + FileAccess.mimeFor(fname) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    bos.write(data);
                    bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                body = bos.toByteArray();
                type = "multipart/form-data; boundary=" + boundary;
            } else if (json != null) {
                body = json.toString().getBytes(StandardCharsets.UTF_8);
                if (type == null) type = "application/json; charset=utf-8";
            } else if (form != null) {
                StringBuilder sb = new StringBuilder();
                Iterator<String> it = form.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (sb.length() > 0) sb.append('&');
                    sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(String.valueOf(form.get(k)), "UTF-8"));
                }
                body = sb.toString().getBytes(StandardCharsets.UTF_8);
                if (type == null) type = "application/x-www-form-urlencoded";
            } else if (inv.args.has("body")) {
                body = inv.str("body").getBytes(StandardCharsets.UTF_8);
                if (type == null) type = "text/plain; charset=utf-8";
            }
            String method = inv.str("method", "GET").toUpperCase(Locale.ROOT);
            inv.progress.update(method + " " + c.name);
            Resp r = Api.call(c, method, path, inv.args.optJSONObject("headers"), body, type, 60 * 1024 * 1024, false);
            String saveTo = inv.str("save_to").trim();
            JSONObject data = J.obj("status", r.code, "content_type", r.contentType);
            if (!r.location.isEmpty()) J.put(data, "location", r.location);
            if (!saveTo.isEmpty() && r.code < 400) {
                String where = fa.write(saveTo, r.body, false);
                J.put(data, "saved", where);
                J.put(data, "bytes", r.body.length);
                return ToolResult.ok("HTTP " + r.code + ", saved " + r.body.length + " bytes to " + where, data);
            }
            int max = (int) inv.num("max_chars", 20000);
            if (r.isText()) J.put(data, "body", r.text(max));
            else J.put(data, "body", "(binary, " + r.body.length + " bytes — pass save_to to keep it)");
            String msg = "HTTP " + r.code;
            return r.code < 400 ? ToolResult.ok(msg, data) : ToolResult.fail(msg + " from " + c.name, data);
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class Email extends BaseTool {
        public Email(AppGraph g) {
            super(g, ToolSpec.builder("email")
                    .description("A connected mailbox (Gmail, Outlook, Yahoo, any IMAP/SMTP). Actions: folders; list (newest first); "
                            + "search (query: from, to, subject, text, since/before YYYY-MM-DD, unread); read (uid → text, attachments); "
                            + "attachment (save one to the workspace); send (to, cc, bcc, subject, body, html, attachments = workspace "
                            + "paths; with reply_to_uid it is a reply); mark (read / starred); move (to_folder); delete (to trash). "
                            + "Messages are addressed by uid within a folder (default INBOX; Gmail: \"[Gmail]/All Mail\", "
                            + "\"[Gmail]/Sent Mail\").")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"connector\":{\"type\":\"string\"},"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"folders\",\"list\",\"search\",\"read\",\"attachment\",\"send\",\"mark\",\"move\",\"delete\"]},"
                            + "\"folder\":{\"type\":\"string\",\"default\":\"INBOX\"},"
                            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"default\":20},"
                            + "\"unread_only\":{\"type\":\"boolean\"},"
                            + "\"query\":{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},"
                            + "\"subject\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},\"since\":{\"type\":\"string\"},"
                            + "\"before\":{\"type\":\"string\"},\"unread\":{\"type\":\"boolean\"}}},"
                            + "\"uid\":{\"type\":\"integer\"},"
                            + "\"mark_read\":{\"type\":\"boolean\",\"default\":false},"
                            + "\"file_name\":{\"type\":\"string\"},\"save_to\":{\"type\":\"string\"},"
                            + "\"to\":{\"type\":\"string\"},\"cc\":{\"type\":\"string\"},\"bcc\":{\"type\":\"string\"},"
                            + "\"subject\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"},\"html\":{\"type\":\"boolean\",\"default\":false},"
                            + "\"attachments\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                            + "\"reply_to_uid\":{\"type\":\"integer\"},"
                            + "\"read\":{\"type\":\"boolean\"},\"starred\":{\"type\":\"boolean\"},"
                            + "\"to_folder\":{\"type\":\"string\"}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.MEDIUM).backend("connectors").timeoutMs(120_000).build());
        }

        @Override
        public String unavailableReason() {
            return has(g, Connector.EMAIL) ? null : "no mailbox connected (Settings → Connectors)";
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            switch (args.optString("action")) {
                case "send":
                case "delete":
                    return RiskLevel.HIGH;
                case "mark":
                case "move":
                case "attachment":
                    return RiskLevel.LOW;
                default:
                    return RiskLevel.READ_ONLY;
            }
        }

        @Override
        public String describeCall(JSONObject args) {
            String a = args.optString("action");
            if ("send".equals(a)) {
                return "Send email" + (args.has("reply_to_uid") ? " (reply)" : "") + " to " + args.optString("to") + ": "
                        + args.optString("subject") + "\n\n" + abbreviate(args.optString("body"), 400);
            }
            if ("delete".equals(a)) return "Delete email uid " + args.optLong("uid") + " in " + args.optString("folder", "INBOX");
            return "email " + a + " " + args.optString("connector");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            Connector c = need(g, inv, Connector.EMAIL);
            EmailClient mail = new EmailClient(c);
            String folder = inv.str("folder", "INBOX");
            int limit = (int) inv.num("limit", 20);
            long uid = inv.num("uid", -1);
            String a = inv.str("action");
            FileAccess fa = new FileAccess(g.workspace, g.saf);
            switch (a) {
                case "folders":
                    return ToolResult.ok("Folders of " + c.name, J.obj("folders", mail.folders()));
                case "list": {
                    JSONArray l = mail.list(folder, limit, inv.bool("unread_only", false));
                    return ToolResult.ok(l.length() + " message(s) in " + folder, J.obj("messages", l));
                }
                case "search": {
                    JSONObject q = inv.args.optJSONObject("query");
                    JSONArray l = mail.search(folder, q == null ? new JSONObject() : q, limit);
                    return ToolResult.ok(l.length() + " match(es) in " + folder, J.obj("messages", l));
                }
                case "read":
                    requireUid(uid);
                    return ToolResult.ok("Message " + uid, mail.read(folder, uid, inv.bool("mark_read", false), 30_000));
                case "attachment": {
                    requireUid(uid);
                    String name = inv.str("file_name");
                    if (name.isEmpty()) return ToolResult.fail("file_name is required (see read → attachments)");
                    byte[] data = mail.attachment(folder, uid, name, 40 * 1024 * 1024);
                    String to = inv.str("save_to", "mail/" + name);
                    String where = fa.write(to, data, false);
                    return ToolResult.ok("Saved " + data.length + " bytes to " + where, J.obj("path", where));
                }
                case "send": {
                    List<EmailClient.Attachment> files = new ArrayList<>();
                    JSONArray at = inv.args.optJSONArray("attachments");
                    for (int i = 0; at != null && i < at.length(); i++) {
                        String p = at.getString(i);
                        String fname = p.substring(p.lastIndexOf('/') + 1);
                        files.add(new EmailClient.Attachment(fname, FileAccess.mimeFor(fname), fa.read(p, 25 * 1024 * 1024)));
                    }
                    long reply = inv.num("reply_to_uid", -1);
                    String id = mail.send(inv.str("to"), inv.str("cc"), inv.str("bcc"), inv.str("subject"), inv.str("body"),
                            inv.bool("html", false), files, folder, reply);
                    return ToolResult.ok("Sent from " + c.username, J.obj("message_id", id));
                }
                case "mark":
                    requireUid(uid);
                    mail.setFlags(folder, uid, inv.args.has("read") ? inv.bool("read", true) : null,
                            inv.args.has("starred") ? inv.bool("starred", false) : null);
                    return ToolResult.ok("Updated message " + uid);
                case "move":
                    requireUid(uid);
                    if (inv.str("to_folder").isEmpty()) return ToolResult.fail("to_folder is required");
                    mail.move(folder, uid, inv.str("to_folder"));
                    return ToolResult.ok("Moved message " + uid + " to " + inv.str("to_folder"));
                case "delete":
                    requireUid(uid);
                    return ToolResult.ok("Message " + uid + ": " + mail.delete(folder, uid));
                default:
                    return ToolResult.fail("Unknown action " + a);
            }
        }

        private static void requireUid(long uid) throws IOException {
            if (uid <= 0) throw new IOException("uid is required (from list or search)");
        }
    }

    static String abbreviate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ------------------------------------------------------------------------------------------

    public static final class Web extends BaseTool {
        public Web(AppGraph g) {
            super(g, ToolSpec.builder("web_session")
                    .description("Use a website the user is logged in to (Instagram, Facebook, X, YouTube, Telegram Web, WhatsApp Web, "
                            + "LinkedIn, Reddit, any site they added) as the user. The browser is shown to the user while you work. "
                            + "Actions: open (url on that site; in single-page apps an address that only changes #… switches inside the "
                            + "page — e.g. Telegram Web chat links from text → links), text (title, url, visible text, links, buttons, "
                            + "inputs), tap (a real finger tap on the element matching selector, or on the smallest visible element "
                            + "containing text), type (tap selector/text first if given, then type value like a keyboard), key (Enter, "
                            + "Tab, Backspace, Escape, ArrowUp, ArrowDown), scroll (dy pixels, page or the scrollable element around "
                            + "selector/text; negative = up), back, screenshot (picture of the page), url, js (JavaScript: a function "
                            + "body that returns a value, or an expression; prefer tap/type/key for clicking and typing), fetch "
                            + "(request from inside the page with its cookies). After tap/type/key/scroll call text to see the result.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"connector\":{\"type\":\"string\"},"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"open\",\"text\",\"tap\",\"type\",\"key\",\"scroll\",\"back\",\"screenshot\",\"url\",\"js\",\"fetch\"]},"
                            + "\"url\":{\"type\":\"string\"},"
                            + "\"selector\":{\"type\":\"string\",\"description\":\"CSS selector\"},"
                            + "\"text\":{\"type\":\"string\",\"description\":\"Visible text / label of the element\"},"
                            + "\"value\":{\"type\":\"string\",\"description\":\"Text to type\"},"
                            + "\"key\":{\"type\":\"string\"},"
                            + "\"dy\":{\"type\":\"integer\",\"default\":800},"
                            + "\"script\":{\"type\":\"string\",\"maxLength\":20000},"
                            + "\"method\":{\"type\":\"string\",\"default\":\"GET\"},"
                            + "\"headers\":{\"type\":\"object\"},"
                            + "\"body\":{\"type\":\"string\"},"
                            + "\"max_chars\":{\"type\":\"integer\",\"minimum\":500,\"maximum\":100000,\"default\":12000}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.MEDIUM).backend("connectors").timeoutMs(120_000).build());
        }

        @Override
        public String unavailableReason() {
            return has(g, Connector.WEB) ? null : "no website logins connected (Settings → Connectors)";
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String a = args.optString("action");
            if ("js".equals(a) || "tap".equals(a) || "type".equals(a) || "key".equals(a)) return RiskLevel.MEDIUM;
            if ("fetch".equals(a)) return "GET".equalsIgnoreCase(args.optString("method", "GET")) ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
            return RiskLevel.READ_ONLY;
        }

        @Override
        public String describeCall(JSONObject args) {
            String a = args.optString("action");
            if ("js".equals(a)) return "Run in " + args.optString("connector") + ": " + abbreviate(args.optString("script"), 300);
            if ("tap".equals(a)) return "Tap \"" + args.optString("text", args.optString("selector")) + "\" on " + args.optString("connector");
            if ("type".equals(a)) return "Type on " + args.optString("connector") + ": " + abbreviate(args.optString("value"), 300);
            return "web_session " + a + " " + args.optString("connector") + " " + args.optString("url");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            Connector c = need(g, inv, Connector.WEB);
            int max = (int) inv.num("max_chars", 12000);
            String a = inv.str("action");
            switch (a) {
                case "open": {
                    String url = inv.str("url").isEmpty() ? c.homeUrl : inv.str("url");
                    inv.progress.update("Opening " + url);
                    String state = WebSession.open(g.ctx, c, url, 30_000);
                    String page = WebSession.text(g.ctx, c, max);
                    return ToolResult.ok(state, J.obj("page", page));
                }
                case "text":
                    return ToolResult.ok("Page", J.obj("page", WebSession.text(g.ctx, c, max)));
                case "tap": {
                    String r = WebSession.tap(g.ctx, c, inv.str("selector"), inv.str("text"));
                    return r.startsWith("ERROR") ? ToolResult.fail(r + " — call text to see what is on the page") : ToolResult.ok(r);
                }
                case "type": {
                    if (!inv.args.has("value")) return ToolResult.fail("value (the text to type) is required");
                    String r = WebSession.type(g.ctx, c, inv.str("selector"), inv.str("text"), inv.str("value"));
                    r = r == null ? "null" : r;
                    return r.contains("ERROR") ? ToolResult.fail(r) : ToolResult.ok(r.replace("\"", ""));
                }
                case "key": {
                    String r = WebSession.key(g.ctx, c, inv.str("key"));
                    return r.startsWith("ERROR") ? ToolResult.fail(r) : ToolResult.ok(r);
                }
                case "scroll": {
                    String r = WebSession.scroll(g.ctx, c, inv.str("selector"), inv.str("text"), (int) inv.num("dy", 800));
                    return r != null && r.startsWith("ERROR") ? ToolResult.fail(r) : ToolResult.ok("Scrolled", J.obj("scroll", r));
                }
                case "back":
                    return ToolResult.ok(WebSession.back(g.ctx, c));
                case "url":
                    return ToolResult.ok(WebSession.url(g.ctx, c));
                case "js": {
                    String r = WebSession.eval(g.ctx, c, inv.str("script"), 60_000);
                    if (r != null && r.startsWith("ERROR:")) return ToolResult.fail(r);
                    String shown = abbreviate(r == null ? "null" : r, max);
                    return ToolResult.ok("null".equals(shown) ? "Ran; the script returned nothing (return a value to see one)" : "Ran",
                            J.obj("result", shown, "url", WebSession.url(g.ctx, c)));
                }
                case "fetch": {
                    String url = inv.str("url");
                    WebSession.checkUrl(c, url);
                    JSONObject init = new JSONObject();
                    init.put("method", inv.str("method", "GET").toUpperCase(Locale.ROOT));
                    init.put("credentials", "include");
                    JSONObject headers = inv.args.optJSONObject("headers");
                    if (headers != null) init.put("headers", headers);
                    if (inv.args.has("body")) init.put("body", inv.str("body"));
                    String script = "const r=await fetch(" + JSONObject.quote(url) + "," + init + ");"
                            + "const t=await r.text();return {status:r.status,content_type:r.headers.get('content-type'),"
                            + "body:t.slice(0," + max + ")};";
                    String r = WebSession.eval(g.ctx, c, script, 60_000);
                    if (r != null && r.startsWith("ERROR:")) return ToolResult.fail(r + " (open a page of the site first)");
                    return ToolResult.ok("Fetched", J.obj("response", r));
                }
                case "screenshot": {
                    Bitmap b = WebSession.screenshot(g.ctx, c);
                    Bitmap small = ImageUtil.scaleToMax(b, 1280);
                    byte[] jpg = ImageUtil.jpeg(small, 80);
                    return ToolResult.okWithImage("Picture of " + WebSession.url(g.ctx, c), J.obj("bytes", jpg.length),
                            ImageUtil.toImage(jpg, "auto"));
                }
                default:
                    return ToolResult.fail("Unknown action " + a);
            }
        }
    }
}
