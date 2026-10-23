package com.suvo.agent.plugins;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.TextUtil;
import com.suvo.agent.data.Settings;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.TermuxBackend;
import com.suvo.agent.tools.Http;
import com.suvo.agent.tools.J;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs a {@link CustomToolDef} under the same validation/approval pipeline as built-in tools. */
public final class CustomToolAdapter implements Tool {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)\\}\\}");

    private final AppGraph g;
    private final CustomToolDef def;
    private final ToolSpec spec;

    public CustomToolAdapter(AppGraph g, CustomToolDef def) {
        this.g = g;
        this.def = def;
        ToolSpec.Builder b = ToolSpec.builder(def.name).id("custom." + def.name).builtin(false)
                .description("[custom " + def.kind + " tool] " + def.description).version(def.version)
                .schema(def.schema).risk(def.risk).backend(def.kind).timeoutMs(def.timeoutS * 1000L + 5000);
        if (def.kind.equals("python")) b.requires(Capability.TERMUX);
        else b.requires(Capability.NETWORK);
        this.spec = b.build();
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String unavailableReason() {
        if (def.kind.equals("python")) {
            Boolean py = g.runtimes.isInstalled("python");
            if (py != null && !py) return "Python is not installed in Termux";
        }
        return null;
    }

    @Override
    public String describeCall(JSONObject args) {
        return "Custom tool " + def.name + " " + args;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        ToolResult r = def.kind.equals("python") ? runPython(inv) : runHttp(inv);
        g.customTools.recordRun(def.name, r.succeeded() ? r.toModelContent(4000) : "", r.succeeded() ? "" : r.message);
        return r;
    }

    private ToolResult runPython(ToolInvocation inv) {
        String dir = TermuxBackend.HOME + "/yourfriend/tools/" + def.name;
        // The script is written by a heredoc with a random delimiter; arguments arrive on stdin.
        String delim = "YF_EOF_" + Long.toHexString(System.nanoTime());
        String cmd = "mkdir -p " + dir + " && cat > " + dir + "/tool.py <<'" + delim + "'\n" + def.code + "\n" + delim + "\n"
                + "cd " + dir + " && python3 tool.py";
        ExecResult r = g.termux.run(cmd, inv.args.toString(), def.timeoutS * 1000L, inv.cancel);
        if (!r.ran()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, r.error);
        if (r.timedOut) return ToolResult.of(ToolResult.Status.TIMED_OUT, "Custom tool timed out");
        String out = r.stdout.trim();
        JSONObject parsed = null;
        int a = out.lastIndexOf("\n{");
        String candidate = out.startsWith("{") ? out : a >= 0 ? out.substring(a + 1) : "";
        if (!candidate.isEmpty()) {
            try {
                parsed = new JSONObject(candidate);
            } catch (JSONException ignored) {
            }
        }
        if (r.exitCode != 0) {
            return ToolResult.fail("Exit code " + r.exitCode + ": " + TextUtil.truncate(r.stderr.isEmpty() ? out : r.stderr, 3000));
        }
        if (parsed != null) {
            String status = parsed.optString("status", "ok");
            String msg = parsed.optString("message", "");
            JSONObject data = parsed.optJSONObject("data");
            return "error".equalsIgnoreCase(status) ? ToolResult.fail(msg, data) : ToolResult.ok(msg, data);
        }
        return ToolResult.ok(TextUtil.truncate(out, 8000));
    }

    private ToolResult runHttp(ToolInvocation inv) throws Exception {
        String url = fill(def.url, inv.args, true);
        Map<String, String> headers = new HashMap<>();
        Iterator<String> it = def.headers.keys();
        while (it.hasNext()) {
            String k = it.next();
            headers.put(k, fill(def.headers.optString(k), inv.args, false));
        }
        if (!def.secretHeader.isEmpty() && !def.secret.isEmpty()) headers.put(def.secretHeader, def.secret);
        byte[] body = null;
        if (!def.method.equals("GET") && !def.method.equals("DELETE")) {
            String b = def.bodyTemplate.isEmpty() ? inv.args.toString() : fillJson(def.bodyTemplate, inv.args);
            body = b.getBytes(StandardCharsets.UTF_8);
        }
        Http.Response resp = Http.request(def.method, url, headers, body, "application/json", 2_000_000,
                def.timeoutS * 1000, g.settings.bool(Settings.ALLOW_LAN_WEB, false), inv.cancel);
        String text = resp.text();
        Object json = null;
        try {
            json = new org.json.JSONTokener(text).nextValue();
        } catch (JSONException ignored) {
        }
        JSONObject data = J.obj("http_status", resp.code, "body", json instanceof JSONObject || json instanceof org.json.JSONArray ? json : TextUtil.truncate(text, 12000));
        return resp.code < 400 ? ToolResult.ok("HTTP " + resp.code, data) : ToolResult.fail("HTTP " + resp.code, data);
    }

    static String fill(String template, JSONObject args, boolean urlEncode) throws Exception {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = args.has(m.group(1)) ? String.valueOf(args.opt(m.group(1))) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(urlEncode ? URLEncoder.encode(v, "UTF-8") : v.replaceAll("[\\r\\n]", " ")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Body templates put placeholders where a JSON value belongs, unquoted: {"q": {{query}}, "n": {{count}}}.
     * Each placeholder becomes the JSON encoding of the argument (strings are quoted and escaped).
     */
    static String fillJson(String template, JSONObject args) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = args.opt(m.group(1));
            String enc = v == null ? "null" : v instanceof String ? JSONObject.quote((String) v) : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(enc));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static RiskLevel minRisk(CustomToolDef d) {
        return d.kind.equals("python") && d.risk == RiskLevel.READ_ONLY ? RiskLevel.LOW : d.risk;
    }
}
