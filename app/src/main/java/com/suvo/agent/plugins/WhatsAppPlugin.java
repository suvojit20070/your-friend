package com.suvo.agent.plugins;

import android.util.Base64;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.TermuxBackend;
import com.suvo.agent.tools.BaseTool;
import com.suvo.agent.tools.J;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 *
 * whatsapp: the user's own WhatsApp number as a linked device (like WhatsApp Web), through a small
 * bridge (Baileys, Node.js) that runs in Termux and listens only on this phone (127.0.0.1, with a
 * secret token). setup installs it once; link gives the pairing code the user types in WhatsApp →
 * Linked devices → Link with phone number.
 */
public final class WhatsAppPlugin extends BaseTool {

    static final int PORT = 8765;
    static final String DIR = "$HOME/.yourfriend/wa";
    private static final String TOKEN_KEY = "wa_bridge_token";

    public WhatsAppPlugin(AppGraph g) {
        super(g, ToolSpec.builder("whatsapp")
                .description("The user's own WhatsApp (their number, linked like WhatsApp Web). First time: setup (installs the "
                        + "bridge in Termux, a few minutes), then link (number with country code → a pairing code the user types "
                        + "in WhatsApp → Linked devices → Link with phone number). Then: status; chats (limit, query: recent "
                        + "chats with last message and unread count); messages (to: name, number or chat id; limit); send (to, "
                        + "text); send_file (to, file: workspace path, text = caption); read (to: mark as read); logout. Send "
                        + "only what the user asked for — WhatsApp bans numbers that spam.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"setup\",\"link\",\"status\",\"chats\",\"messages\",\"send\",\"send_file\",\"read\",\"logout\",\"restart\"]},"
                        + "\"number\":{\"type\":\"string\",\"description\":\"link: the user's number with country code\"},"
                        + "\"to\":{\"type\":\"string\",\"description\":\"contact / group name, number with country code, or chat id\"},"
                        + "\"text\":{\"type\":\"string\"},"
                        + "\"file\":{\"type\":\"string\"},"
                        + "\"query\":{\"type\":\"string\"},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100,\"default\":20}"
                        + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                .risk(RiskLevel.MEDIUM).backend("plugin").timeoutMs(20 * 60_000).build());
    }

    @Override
    public String unavailableReason() {
        return Plugins.unavailable(g, Plugins.WHATSAPP);
    }

    @Override
    public RiskLevel riskFor(JSONObject a) {
        switch (a.optString("action")) {
            case "status":
            case "chats":
            case "messages":
                return RiskLevel.READ_ONLY;
            case "send":
            case "send_file":
            case "logout":
                return RiskLevel.HIGH;
            default:
                return RiskLevel.MEDIUM;
        }
    }

    @Override
    public String describeCall(JSONObject a) {
        String act = a.optString("action");
        if ("send".equals(act)) return "WhatsApp to " + a.optString("to") + ": " + a.optString("text");
        if ("send_file".equals(act)) return "WhatsApp file " + a.optString("file") + " to " + a.optString("to");
        return "WhatsApp " + act + " " + a.optString("to", a.optString("number"));
    }

    private String token() {
        String t = g.settings.str(TOKEN_KEY, "");
        if (t.isEmpty()) {
            byte[] b = new byte[18];
            new java.security.SecureRandom().nextBytes(b);
            t = Base64.encodeToString(b, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            g.settings.put(TOKEN_KEY, t);
        }
        return t;
    }

    // ------------------------------------------------------------------ bridge calls

    private JSONObject bridge(String method, String path, JSONObject body, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("x-token", token());
        if (body != null) {
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(b);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream is = in) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            }
        }
        JSONObject o = new JSONObject(bo.size() == 0 ? "{}" : bo.toString("UTF-8"));
        if (code >= 400) throw new IllegalStateException(o.optString("error", "HTTP " + code));
        return o;
    }

    /** The bridge's answer, starting the bridge first when it is not running (once). */
    private JSONObject call(String method, String path, JSONObject body, int timeoutMs, CancellationToken cancel) throws Exception {
        try {
            return bridge(method, path, body, timeoutMs);
        } catch (java.net.ConnectException e) {
            String why = start(cancel);
            if (why != null) throw new IllegalStateException(why);
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1000);
                try {
                    return bridge(method, path, body, timeoutMs);
                } catch (java.net.ConnectException again) {
                    // still starting
                }
            }
            throw new IllegalStateException("The WhatsApp bridge did not start: see " + DIR + "/bridge.log in Termux, or run setup again");
        }
    }

    /** Starts the bridge in Termux (in the background, kept awake). Null on success, else why not. */
    private String start(CancellationToken cancel) {
        TermuxBackend t = g.termux.forUser();
        String why = t.unavailableReason();
        if (why != null) return why;
        ExecResult r = t.run("cd " + DIR + " 2>/dev/null || { echo NOSETUP; exit 3; }; [ -d node_modules/@whiskeysockets ] || { echo NOSETUP; exit 3; }; "
                + "pkill -f 'node bridge.js' 2>/dev/null; YF_TOKEN='" + token() + "' YF_PORT=" + PORT
                + " setsid nohup node bridge.js >> bridge.log 2>&1 < /dev/null & "
                + "(command -v termux-wake-lock >/dev/null && termux-wake-lock) ; sleep 1; echo STARTED", null, 30_000, cancel);
        if (r.stdout.contains("NOSETUP")) return "The WhatsApp bridge is not installed yet: run action setup first";
        if (!r.stdout.contains("STARTED")) return "Could not start the bridge: " + r.stderr + r.error;
        return null;
    }

    private String setup(ToolInvocation inv) throws Exception {
        TermuxBackend t = g.termux.forUser();
        String why = t.unavailableReason();
        if (why != null) return "Termux is needed for WhatsApp: " + why;
        byte[] js;
        try (InputStream in = g.ctx.getAssets().open("plugins/whatsapp/bridge.js")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            js = bo.toByteArray();
        }
        inv.progress.update("Copying the bridge to Termux");
        ExecResult w = t.run("mkdir -p " + DIR + " && base64 -d > " + DIR + "/bridge.js && echo OK",
                Base64.encodeToString(js, Base64.NO_WRAP), 60_000, inv.cancel);
        if (!w.stdout.contains("OK")) return "Could not write the bridge: " + w.stderr + w.error;
        inv.progress.update("Installing Node.js and the WhatsApp library (a few minutes)");
        ExecResult r = t.run("cd " + DIR + " && (command -v node >/dev/null || (yes | pkg install -y nodejs-lts || yes | pkg install -y nodejs)) "
                + "&& (command -v git >/dev/null || yes | pkg install -y git) "
                + "&& [ -f package.json ] || echo '{\"name\":\"yf-wa\",\"private\":true}' > package.json; "
                + "cd " + DIR + " && npm install --no-audit --no-fund @whiskeysockets/baileys@6 pino@9 && echo INSTALLED",
                null, 15 * 60_000, inv.cancel);
        if (r.jobId != null) {
            return "Still installing in Termux (job " + r.jobId + "). Wait for it with termux_job (action wait), then call whatsapp setup again.";
        }
        if (!r.stdout.contains("INSTALLED")) return "Install failed: " + tail(r.stdout + "\n" + r.stderr + r.error);
        String s = start(inv.cancel);
        return s == null ? null : s;
    }

    private static String tail(String s) {
        return s.length() > 1500 ? "…" + s.substring(s.length() - 1500) : s;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String a = inv.str("action");
        int limit = (int) inv.num("limit", 20);
        try {
            switch (a) {
                case "setup": {
                    String err = setup(inv);
                    if (err != null) return ToolResult.fail(err);
                    Thread.sleep(3000);
                    JSONObject st = call("GET", "/status", null, 10_000, inv.cancel);
                    return ToolResult.ok(st.optBoolean("registered") ? "WhatsApp bridge running and linked"
                            : "WhatsApp bridge installed and running. Now link: ask the user for their number and call link", st);
                }
                case "restart": {
                    String err = start(inv.cancel);
                    return err == null ? ToolResult.ok("Restarted the WhatsApp bridge") : ToolResult.fail(err);
                }
                case "link": {
                    String n = inv.str("number").replaceAll("[^0-9]", "");
                    if (n.length() < 8) return ToolResult.fail("number with country code is required (e.g. 919876543210)");
                    JSONObject o = call("POST", "/pair", J.obj("number", n), 60_000, inv.cancel);
                    if (o.optBoolean("already")) return ToolResult.ok("Already linked", o);
                    String code = o.optString("code");
                    return ToolResult.ok("Pairing code: " + code + " — on the phone with that number open WhatsApp → Settings → Linked devices → "
                            + "Link a device → Link with phone number instead, and type " + code + ". Then check with status.", J.obj("code", code));
                }
                case "status":
                    return ToolResult.ok("Status", call("GET", "/status", null, 10_000, inv.cancel));
                case "chats": {
                    String q = inv.str("query");
                    return ToolResult.ok("Chats", call("GET", "/chats?limit=" + limit + (q.isEmpty() ? "" : "&q=" + URLEncoder.encode(q, "UTF-8")),
                            null, 15_000, inv.cancel));
                }
                case "messages": {
                    if (inv.str("to").isEmpty()) return ToolResult.fail("to is required");
                    return ToolResult.ok("Messages", call("GET", "/messages?limit=" + limit + "&to=" + URLEncoder.encode(inv.str("to"), "UTF-8"),
                            null, 15_000, inv.cancel));
                }
                case "send": {
                    if (inv.str("to").isEmpty() || inv.str("text").isEmpty()) return ToolResult.fail("to and text are required");
                    JSONObject o = call("POST", "/send", J.obj("to", inv.str("to"), "text", inv.str("text")), 30_000, inv.cancel);
                    return ToolResult.ok("Sent to " + o.optString("name"), o);
                }
                case "send_file": {
                    File f = g.workspace.resolve(inv.str("file"));
                    if (!f.isFile()) return ToolResult.fail("No file " + inv.str("file") + " in the workspace");
                    if (f.length() > 45L * 1024 * 1024) return ToolResult.fail("Too big (over 45 MB)");
                    byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                    String mime = java.net.URLConnection.guessContentTypeFromName(f.getName());
                    JSONObject o = call("POST", "/send", J.obj("to", inv.str("to"), "text", inv.str("text"), "filename", f.getName(),
                            "mimetype", mime == null ? "application/octet-stream" : mime,
                            "b64", Base64.encodeToString(data, Base64.NO_WRAP)), 120_000, inv.cancel);
                    return ToolResult.ok("Sent " + f.getName() + " to " + o.optString("name"), o);
                }
                case "read":
                    return ToolResult.ok("Marked as read", call("POST", "/read", J.obj("to", inv.str("to")), 15_000, inv.cancel));
                case "logout":
                    return ToolResult.ok("Unlinked from WhatsApp", call("POST", "/logout", new JSONObject(), 20_000, inv.cancel));
                default:
                    return ToolResult.fail("Unknown action " + a);
            }
        } catch (IllegalStateException e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
