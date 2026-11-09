package com.suvo.agent.plugins;

import android.net.Uri;

import com.suvo.agent.AppGraph;
import com.suvo.agent.connectors.Connector;
import com.suvo.agent.connectors.WebSession;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.tools.BaseTool;
import com.suvo.agent.tools.J;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Learned site APIs ("recipes"): the agent does a thing once in the browser, finds the request the
 * site made for it (browser action network), saves it with {placeholders}, and from then on calls
 * it directly — no pages, no tapping. Calls run inside an open page of the site (its cookies and
 * fresh page tokens, e.g. Facebook's fb_dtsg), or natively with the browser's cookies. Recipes
 * live in the workspace (apis/&lt;site&gt;.json), so the user can see and edit them.
 */
public final class SiteApis {

    private SiteApis() {
    }

    /** Page tokens many sites put into their own requests; usable as {fb_dtsg}, {lsd}, {user_id}, {csrf} in any recipe. */
    static final String TOKENS_JS = "var out={};var h=document.documentElement.innerHTML;"
            + "function rq(n){try{return require(n).token}catch(e){return null}}"
            + "out.fb_dtsg=rq('DTSGInitialData')||(h.match(/\"DTSGInitialData\",\\[\\],\\{\"token\":\"([^\"]+)/)||[])[1]||"
            + "(document.querySelector('input[name=fb_dtsg]')||{}).value||'';"
            + "out.lsd=rq('LSD')||(h.match(/\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)/)||[])[1]||'';"
            + "out.user_id=(document.cookie.match(/(?:c_user|ds_user_id)=(\\d+)/)||[])[1]||'';"
            + "out.csrf=(document.cookie.match(/csrftoken=([^;]+)/)||[])[1]||(document.querySelector('meta[name=csrf-token]')||{}).content||'';"
            + "return out;";

    static File file(AppGraph g, String host) {
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceAll("[^a-z0-9.-]", "_");
        return new File(g.workspace.dir("apis"), h + ".json");
    }

    static JSONObject load(AppGraph g, String host) {
        try {
            File f = file(g, host);
            if (!f.isFile()) return new JSONObject();
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void store(AppGraph g, String host, JSONObject all) throws Exception {
        java.nio.file.Files.write(file(g, host).toPath(), all.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    /** {name} → value (JSON-escaped inside JSON bodies when json = true). */
    static String fill(String template, JSONObject vars, boolean json) {
        if (template == null) return null;
        Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String k = m.group(1);
            if (!vars.has(k)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String v = String.valueOf(vars.opt(k));
            if (json) {
                String q = JSONObject.quote(v);
                v = q.substring(1, q.length() - 1);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The engine of site_api and facebook. {@code fixedHost} null = any site. */
    static ToolResult run(AppGraph g, ToolInvocation inv, String fixedHost) throws Exception {
        String a = inv.str("action");
        String host = fixedHost != null ? fixedHost : inv.str("site").trim();
        if (host.isEmpty() && !"list".equals(a)) {
            String cur = WebSession.url(g.ctx, Connector.openWeb());
            host = cur.isEmpty() ? "" : String.valueOf(Uri.parse(cur).getHost());
        }
        host = host.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        switch (a) {
            case "list": {
                if (host.isEmpty()) {
                    JSONArray sites = new JSONArray();
                    File[] fs = g.workspace.dir("apis").listFiles();
                    if (fs != null) for (File f : fs) sites.put(f.getName().replace(".json", ""));
                    return ToolResult.ok(sites.length() + " site(s) with saved APIs", J.obj("sites", sites));
                }
                JSONObject all = load(g, host);
                JSONArray out = new JSONArray();
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject r = all.optJSONObject(k);
                    out.put(J.obj("name", k, "what", r.optString("description"), "method", r.optString("method"), "url", r.optString("url"),
                            "needs", needs(r)));
                }
                return ToolResult.ok(out.length() + " saved API(s) for " + host, J.obj("apis", out));
            }
            case "save": {
                String name = inv.str("name").trim();
                if (name.isEmpty() || inv.str("url").isEmpty()) return ToolResult.fail("name and url are required");
                Uri u = Uri.parse(inv.str("url").replaceAll("\\{[^}]*\\}", "x"));
                String h = u.getHost() == null ? host : u.getHost();
                if (fixedHost != null && !h.endsWith(fixedHost.replaceFirst("^www\\.", ""))) return ToolResult.fail("Only " + fixedHost + " addresses");
                JSONObject all = load(g, h);
                JSONObject r = J.obj("description", inv.str("description"), "method", inv.str("method", "GET").toUpperCase(Locale.ROOT),
                        "url", inv.str("url"), "in_page", inv.bool("in_page", true), "saved", System.currentTimeMillis());
                if (inv.args.has("headers")) r.put("headers", inv.args.optJSONObject("headers"));
                if (inv.args.has("body")) r.put("body", inv.str("body"));
                if (inv.args.has("content_type")) r.put("content_type", inv.str("content_type"));
                all.put(name, r);
                store(g, h, all);
                return ToolResult.ok("Saved " + name + " for " + h + " — call it with action call", J.obj("needs", needs(r), "file", g.workspace.relative(file(g, h))));
            }
            case "delete": {
                JSONObject all = load(g, host);
                if (all.remove(inv.str("name")) == null) return ToolResult.fail("No saved API " + inv.str("name") + " for " + host);
                store(g, host, all);
                return ToolResult.ok("Deleted");
            }
            case "call": {
                JSONObject all = load(g, host);
                JSONObject r = all.optJSONObject(inv.str("name"));
                if (r == null) return ToolResult.fail("No saved API \"" + inv.str("name") + "\" for " + host + " — list them, or save one first");
                JSONObject vars = inv.args.optJSONObject("vars");
                vars = vars == null ? new JSONObject() : new JSONObject(vars.toString());
                return call(g, inv, host, r, vars);
            }
            default:
                return ToolResult.fail("Unknown action " + a);
        }
    }

    private static JSONArray needs(JSONObject r) {
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").matcher(r.optString("url") + " " + r.optString("body") + " " + r.optString("headers"));
        while (m.find()) {
            String k = m.group(1);
            if (!k.equals("fb_dtsg") && !k.equals("lsd") && !k.equals("user_id") && !k.equals("csrf")) seen.add(k);
        }
        for (String k : seen) out.put(k);
        return out;
    }

    private static ToolResult call(AppGraph g, ToolInvocation inv, String host, JSONObject r, JSONObject vars) throws Exception {
        Connector c = Connector.openWeb();
        String method = r.optString("method", "GET");
        boolean inPage = r.optBoolean("in_page", true);
        String ct = r.optString("content_type", "");
        boolean jsonBody = ct.contains("json") || r.optString("body").trim().startsWith("{");
        if (inPage) {
            // An open page of the site: its cookies and its fresh tokens.
            String cur = WebSession.url(g.ctx, c);
            String curHost = cur.isEmpty() ? "" : String.valueOf(Uri.parse(cur).getHost());
            String bare = host.replaceFirst("^www\\.", "");
            if (!curHost.endsWith(bare)) {
                inv.progress.update("Opening " + host);
                WebSession.open(g.ctx, c, "https://" + host + "/", 30_000);
            }
            String tokens = WebSession.eval(g.ctx, c, TOKENS_JS, 15_000);
            if (tokens != null && tokens.startsWith("{")) {
                JSONObject t = new JSONObject(tokens);
                for (Iterator<String> it = t.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!vars.has(k)) vars.put(k, t.optString(k));
                }
            }
        }
        String url = fill(r.optString("url"), vars, false);
        String body = r.has("body") ? fill(r.optString("body"), vars, jsonBody) : null;
        JSONObject headers = r.optJSONObject("headers");
        if (headers != null) headers = new JSONObject(fill(headers.toString(), vars, true));
        if (url.contains("{") && Pattern.compile("\\{[A-Za-z_]").matcher(url).find()) return ToolResult.fail("Missing vars in the url: " + url);
        int max = 12000;
        if (inPage) {
            JSONObject init = J.obj("method", method, "credentials", "include");
            JSONObject h = headers == null ? new JSONObject() : headers;
            if (!ct.isEmpty() && !h.has("content-type") && !h.has("Content-Type")) h.put("content-type", ct);
            init.put("headers", h);
            if (body != null && !"GET".equals(method)) init.put("body", body);
            String js = "const r=await fetch(" + JSONObject.quote(url) + "," + init + ");const t=await r.text();"
                    + "return {status:r.status,type:r.headers.get('content-type'),body:t.slice(0," + max + ")};";
            String out = WebSession.eval(g.ctx, c, js, 60_000);
            if (out == null || out.startsWith("ERROR")) return ToolResult.fail(String.valueOf(out));
            JSONObject o = new JSONObject(out);
            int st = o.optInt("status");
            return st >= 200 && st < 300 ? ToolResult.ok("HTTP " + st, J.obj("response", o.optString("body")))
                    : ToolResult.fail("HTTP " + st + ": " + o.optString("body"));
        }
        SiteHttp http = new SiteHttp(g.ctx, "https://" + host, 800);
        java.util.Map<String, String> hm = new java.util.LinkedHashMap<>();
        if (headers != null) for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
            String k = it.next();
            hm.put(k, headers.optString(k));
        }
        SiteHttp.Response res = http.send(method, url, hm, body == null || "GET".equals(method) ? null : body.getBytes(StandardCharsets.UTF_8),
                ct.isEmpty() ? (jsonBody ? "application/json" : "application/x-www-form-urlencoded") : ct);
        String text = res.body.length() > max ? res.body.substring(0, max) + "…" : res.body;
        return res.ok() ? ToolResult.ok("HTTP " + res.status, J.obj("response", text)) : ToolResult.fail("HTTP " + res.status + ": " + text);
    }

    static final String SCHEMA_PROPS = "\"name\":{\"type\":\"string\",\"description\":\"recipe name, e.g. post_status\"},"
            + "\"description\":{\"type\":\"string\"},"
            + "\"method\":{\"type\":\"string\",\"default\":\"GET\"},"
            + "\"url\":{\"type\":\"string\",\"description\":\"save: address with {placeholders}\"},"
            + "\"headers\":{\"type\":\"object\"},"
            + "\"body\":{\"type\":\"string\",\"description\":\"save: request body with {placeholders} (form or JSON text)\"},"
            + "\"content_type\":{\"type\":\"string\"},"
            + "\"in_page\":{\"type\":\"boolean\",\"default\":true,\"description\":\"save: run inside a page of the site (cookies + page tokens)\"},"
            + "\"vars\":{\"type\":\"object\",\"description\":\"call: values for the placeholders\"}";

    static final String HOW = "Learn once, then call directly: 1) do the thing once with the browser tool, 2) browser action "
            + "network shows the request the site made (method, endpoint, request body), 3) save it here with the changing parts "
            + "as {placeholders} (keep the rest as it was), 4) next time call it with vars. Page tokens are filled for you: "
            + "{fb_dtsg} {lsd} {user_id} {csrf}. Actions: list, save, call, delete.";

    /** site_api: learned API recipes for any website. */
    public static final class Tool extends BaseTool {
        public Tool(AppGraph g) {
            super(g, ToolSpec.builder("site_api")
                    .description("Call a website's own API directly, as the user (logged in through your browser). " + HOW
                            + " site = the host (default: the browser's current site).")
                    .schema("{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"save\",\"call\",\"delete\"]},"
                            + "\"site\":{\"type\":\"string\"}," + SCHEMA_PROPS + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .risk(RiskLevel.MEDIUM).backend("browser").timeoutMs(120_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject a) {
            return "list".equals(a.optString("action")) ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
        }

        @Override
        public String describeCall(JSONObject a) {
            return "site_api " + a.optString("action") + " " + a.optString("site") + " " + a.optString("name");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            return run(g, inv, null);
        }
    }

    /** facebook: the same, for the user's Facebook (Facebook changes its internal calls often, so they are learned). */
    public static final class Facebook extends BaseTool {
        public Facebook(AppGraph g) {
            super(g, ToolSpec.builder("facebook")
                    .description("The user's Facebook account through Facebook's own internal API (GraphQL), with their login in "
                            + "your browser. Facebook changes these calls often, so they are learned: " + HOW
                            + " Facebook's calls are POST https://www.facebook.com/api/graphql/ with a form body (fb_dtsg, "
                            + "doc_id, variables…): keep doc_id, put {fb_dtsg} and {lsd}, and placeholders inside variables. "
                            + "Saved ones are in apis/facebook.com.json.")
                    .schema("{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"save\",\"call\",\"delete\"]},"
                            + SCHEMA_PROPS + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .risk(RiskLevel.MEDIUM).backend("plugin").timeoutMs(120_000).build());
        }

        @Override
        public String unavailableReason() {
            return Plugins.unavailable(g, Plugins.FACEBOOK);
        }

        @Override
        public RiskLevel riskFor(JSONObject a) {
            return "list".equals(a.optString("action")) ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
        }

        @Override
        public String describeCall(JSONObject a) {
            return "Facebook " + a.optString("action") + " " + a.optString("name");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String login = Plugins.needLogin(Plugins.FACEBOOK);
            if (login != null) return ToolResult.fail(login);
            return run(g, inv, "www.facebook.com");
        }
    }
}
