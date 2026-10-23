package com.suvo.agent.core.skills;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;
import com.suvo.agent.data.SecretStore;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Wraps a user {@link Skill} as a callable {@link Tool}.
 * Supports action types: "http" and "intent". "script" is reserved for a
 * sandboxed JS engine (added in a later phase).
 */
public class SkillTool implements Tool {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Skill skill;
    private final Context appContext;
    private final SecretStore secrets;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS).build();

    public SkillTool(Context ctx, Skill skill, SecretStore secrets) {
        this.appContext = ctx.getApplicationContext();
        this.skill = skill;
        this.secrets = secrets;
    }

    @Override public String name() { return skill.name; }

    @Override public ToolSchema schema() {
        return new ToolSchema(skill.name, skill.description, skill.parameters, skill.danger);
    }

    @Override public boolean isAvailable() { return skill.enabled; }

    @Override public String run(JSONObject args) throws Exception {
        switch (skill.actionType()) {
            case "http":   return runHttp(args);
            case "intent": return runIntent(args);
            case "script": return "Error: script skills not enabled in this build yet.";
            default:       return "Error: unknown skill action '" + skill.actionType() + "'";
        }
    }

    // ---- http action ----
    private String runHttp(JSONObject args) throws IOException {
        JSONObject a = skill.action;
        String method = a.optString("method", "GET").toUpperCase();
        String url = fill(a.optString("url", ""), args);

        Request.Builder rb = new Request.Builder().url(url);

        // optional headers
        JSONObject headers = a.optJSONObject("headers");
        if (headers != null) {
            Iterator<String> it = headers.keys();
            while (it.hasNext()) {
                String k = it.next();
                rb.addHeader(k, fill(headers.optString(k), args));
            }
        }

        if (method.equals("GET") || method.equals("HEAD")) {
            rb.method(method, null);
        } else {
            String bodyTpl = a.optString("body", "{}");
            rb.method(method, RequestBody.create(fill(bodyTpl, args), JSON));
        }

        try (Response res = http.newCall(rb.build()).execute()) {
            String body = res.body() != null ? res.body().string() : "";
            if (!res.isSuccessful()) return "Error: HTTP " + res.code() + " — " + clip(body, 300);
            // optional "pick": a top-level key to extract, else return trimmed body
            String pick = a.optString("pick", "");
            if (!pick.isEmpty()) {
                try {
                    JSONObject j = new JSONObject(body);
                    if (j.has(pick)) return clip(j.get(pick).toString(), 4000);
                } catch (Exception ignore) { /* not json, fall through */ }
            }
            return clip(body, 4000);
        }
    }

    // ---- intent action (open app / url) ----
    private String runIntent(JSONObject args) {
        JSONObject a = skill.action;
        String data = fill(a.optString("data", ""), args);
        try {
            Intent i = new Intent(a.optString("intentAction", Intent.ACTION_VIEW));
            if (!data.isEmpty()) i.setData(Uri.parse(data));
            String pkg = a.optString("package", "");
            if (!pkg.isEmpty()) i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(i);
            return "Opened: " + (data.isEmpty() ? pkg : data);
        } catch (Exception e) {
            return "Error: could not open intent — " + e.getMessage();
        }
    }

    /** Replace {{arg}} and {{secret.KEY}} placeholders. Secrets never go to the model. */
    private String fill(String template, JSONObject args) {
        if (template == null) return "";
        String out = template;
        // args
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            out = out.replace("{{" + k + "}}", enc(args.optString(k)));
            out = out.replace("{{ " + k + " }}", enc(args.optString(k)));
        }
        // secrets
        while (out.contains("{{secret.")) {
            int s = out.indexOf("{{secret.");
            int e = out.indexOf("}}", s);
            if (e < 0) break;
            String key = out.substring(s + 9, e).trim();
            String val = secrets != null ? secrets.get(key, "") : "";
            out = out.substring(0, s) + val + out.substring(e + 2);
        }
        return out;
    }

    private static String enc(String s) {
        return s == null ? "" : Uri.encode(s);
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
