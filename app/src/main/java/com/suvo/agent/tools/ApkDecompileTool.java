package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.apkscan.ApkScanner;
import com.suvo.agent.apkscan.Decompiler;
import com.suvo.agent.core.apkscan.ScanLogic;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * apk_decompile: look inside an Android app (installed, or an .apk file in the workspace) with
 * jadx. "scan" runs the full security check (manifest, the suspicious classes only, AI verdict);
 * "info" the manifest facts; "classes" the app's class names (filtered, page by page); "source" one
 * class as Java; "find" the classes whose name or code mentions something (code search reads at
 * most a few dozen of the app's own classes).
 */
public final class ApkDecompileTool extends BaseTool {

    public ApkDecompileTool(AppGraph g) {
        super(g, ToolSpec.builder("apk_decompile")
                .description("Look inside an Android app: an installed package name (com.example.app) or an .apk file path in "
                        + "your workspace. action=scan: full security check (risk level, summary, reasons, how high-level "
                        + "permissions are used) — reads only the suspicious classes, not the whole app. action=info: manifest "
                        + "facts (permissions, services with special powers, packed code, native libraries). action=classes: class "
                        + "names (query filters, skips libraries unless libs=true, page=0,1,…). action=source: one class decompiled "
                        + "to Java (class=full name). action=find: classes whose name or code contains query (code search reads "
                        + "at most 40 of the app's own classes). Decompiling is slow the first time per app; later calls are quick.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"scan\",\"info\",\"classes\",\"source\",\"find\"]},"
                        + "\"app\":{\"type\":\"string\",\"description\":\"package name or workspace path of an .apk\"},"
                        + "\"class\":{\"type\":\"string\",\"description\":\"source: full class name\"},"
                        + "\"query\":{\"type\":\"string\",\"description\":\"classes / find: text to look for\"},"
                        + "\"libs\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"page\":{\"type\":\"integer\",\"minimum\":0,\"default\":0},"
                        + "\"max_chars\":{\"type\":\"integer\",\"minimum\":1000,\"maximum\":40000,\"default\":12000}"
                        + "},\"required\":[\"action\",\"app\"],\"additionalProperties\":false}")
                .risk(RiskLevel.READ_ONLY).backend("apk").timeoutMs(10 * 60_000L).build());
    }

    @Override
    public String describeCall(JSONObject a) {
        return "APK " + a.optString("action") + ": " + a.optString("app") + (a.has("class") ? " " + a.optString("class") : "");
    }

    /** A package name stays; a path is turned into the workspace file. */
    private String resolveApp(String app) throws Exception {
        String a = app.trim();
        if (a.endsWith(".apk") || a.contains("/")) {
            File f = new File(a);
            if (!f.isAbsolute() || !f.isFile()) f = g.workspace.resolve(a);
            if (!f.isFile()) throw new IllegalArgumentException("No such APK file: " + app);
            return f.getAbsolutePath();
        }
        return a;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String action = inv.str("action");
        String app = resolveApp(inv.str("app"));
        if ("scan".equals(action)) {
            ApkScanner.Report r = ApkScanner.scan(g, app, inv.progress::update, inv.cancel);
            if (r.label.isEmpty() && !r.error.isEmpty()) return ToolResult.fail(r.error);
            return ToolResult.ok(r.label + ": " + r.risk.toUpperCase(Locale.ROOT) + " risk", new JSONObject().put("report", r.text()));
        }
        if ("info".equals(action)) {
            ApkScanner.Report r = ApkScanner.facts(g.ctx, app);
            if (r.label.isEmpty() && !r.error.isEmpty()) return ToolResult.fail(r.error);
            return ToolResult.ok(r.label + " " + r.version, new JSONObject().put("info", r.text()));
        }
        inv.progress.update("Decompiling");
        Decompiler d = Decompiler.open(ApkScanner.apkFile(g.ctx, app), new File(g.ctx.getCacheDir(), "jadx"));
        int maxChars = Math.max(1000, Math.min(40000, (int) inv.num("max_chars", 12000)));
        String q = inv.str("query").trim();
        String ql = q.toLowerCase(Locale.ROOT);
        switch (action) {
            case "classes": {
                boolean libs = inv.bool("libs", false);
                List<String> hit = new ArrayList<>();
                for (String c : d.classNames()) {
                    if (!libs && ScanLogic.isLibrary(c)) continue;
                    if (!ql.isEmpty() && !c.toLowerCase(Locale.ROOT).contains(ql)) continue;
                    hit.add(c);
                }
                int page = Math.max(0, (int) inv.num("page", 0)), size = 200;
                int from = Math.min(hit.size(), page * size), to = Math.min(hit.size(), from + size);
                JSONObject data = new JSONObject().put("classes", new JSONArray(hit.subList(from, to))).put("total", hit.size())
                        .put("page", page);
                if (to < hit.size()) data.put("next_page", page + 1);
                return ToolResult.ok(hit.size() + " class(es)" + (libs ? "" : " (libraries hidden)"), data);
            }
            case "source": {
                String c = inv.str("class").trim();
                if (c.isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "class is required");
                String src = d.source(c);
                if (src == null) return ToolResult.fail("No class " + c + " — use action=classes with a query to find its name");
                boolean cut = src.length() > maxChars;
                JSONObject data = new JSONObject().put("class", c).put("java", cut ? src.substring(0, maxChars) : src);
                if (cut) data.put("note", "Cut at " + maxChars + " of " + src.length() + " characters (raise max_chars to see more)");
                List<String> hints = ScanLogic.apiHints(src);
                if (!hints.isEmpty()) data.put("notable_apis", new JSONArray(hints));
                return ToolResult.ok(c, data);
            }
            case "find": {
                if (q.isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "query is required");
                JSONArray byName = new JSONArray(), byCode = new JSONArray();
                int read = 0;
                for (String c : d.classNames()) {
                    if (ScanLogic.isLibrary(c)) continue;
                    if (c.toLowerCase(Locale.ROOT).contains(ql)) {
                        if (byName.length() < 50) byName.put(c);
                    } else if (read < 40 && ScanLogic.nameScore(c) > 0) {
                        // Code search costs a decompile per class: only the suspicious-looking ones.
                        if (inv.cancel.isCancelled()) break;
                        read++;
                        String src = d.source(c);
                        if (src != null && src.contains(q)) byCode.put(c);
                    }
                }
                return ToolResult.ok(byName.length() + " by name, " + byCode.length() + " by code",
                        new JSONObject().put("by_name", byName).put("by_code", byCode).put("code_searched_classes", read));
            }
            default:
                return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action " + action);
        }
    }
}
