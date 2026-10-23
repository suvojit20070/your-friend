package com.suvo.agent.plugins;

import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.SchemaValidator;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * A user-defined tool. Contract:
 * <ul>
 *   <li><b>python</b>: the script runs in Termux; it receives its arguments as a JSON object on
 *   stdin and should print one JSON object to stdout: {"status":"ok"|"error","message":"…","data":{…}}.
 *   Plain-text output is accepted as the message. A non-zero exit code means failure.</li>
 *   <li><b>http</b>: a request to {@code url}; {{arg}} placeholders in the URL are URL-encoded,
 *   in the body they are written unquoted where a JSON value goes and replaced by the JSON-encoded argument. An optional secret is sent in {@code secretHeader}.</li>
 * </ul>
 */
public final class CustomToolDef {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{1,47}$");

    public long id = -1;
    public String name = "";
    public String description = "";
    public String version = "1.0";
    public JSONObject schema;
    public String kind = "python";
    public String code = "";
    public String method = "GET";
    public String url = "";
    public JSONObject headers = new JSONObject();
    public String bodyTemplate = "";
    public String secretHeader = "";
    /** Decrypted in memory only; never exported unless explicitly requested. */
    public String secret = "";
    public int timeoutS = 60;
    public RiskLevel risk = RiskLevel.MEDIUM;
    public boolean enabled = true;
    public String lastResult = "";
    public String lastError = "";

    public String validate() {
        if (!NAME.matcher(name).matches()) return "Name must be lowercase letters, digits or _ (2–48 chars, starting with a letter)";
        if (description.trim().length() < 5) return "Description is required";
        if (schema == null) return "Parameter schema is required";
        String s = SchemaValidator.checkSchema(schema);
        if (s != null) return "Schema: " + s;
        if (timeoutS < 1 || timeoutS > 600) return "Timeout must be 1–600 s";
        switch (kind) {
            case "python":
                if (code.trim().isEmpty()) return "Python code is required";
                if (code.length() > 200_000) return "Code is too long";
                break;
            case "http":
                if (!(url.startsWith("https://") || url.startsWith("http://"))) return "URL must start with http:// or https://";
                if (!method.matches("GET|POST|PUT|PATCH|DELETE")) return "Unsupported HTTP method";
                if (!secretHeader.isEmpty() && !secretHeader.matches("[A-Za-z0-9-]{1,64}")) return "Invalid secret header name";
                break;
            default:
                return "Kind must be python or http";
        }
        // Python tools run code and HTTP tools talk to the network; never classify them as read-only.
        if (risk == RiskLevel.READ_ONLY && kind.equals("python")) risk = RiskLevel.LOW;
        return null;
    }

    public JSONObject toJson(boolean includeSecret) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name).put("description", description).put("version", version).put("schema", schema)
                    .put("kind", kind).put("code", code).put("method", method).put("url", url).put("headers", headers)
                    .put("bodyTemplate", bodyTemplate).put("secretHeader", secretHeader).put("timeoutS", timeoutS)
                    .put("risk", risk.name()).put("enabled", enabled);
            if (includeSecret && !secret.isEmpty()) o.put("secret", secret);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static CustomToolDef fromJson(JSONObject o) {
        CustomToolDef d = new CustomToolDef();
        d.name = o.optString("name", "");
        d.description = o.optString("description", "");
        d.version = o.optString("version", "1.0");
        d.schema = o.optJSONObject("schema");
        d.kind = o.optString("kind", "python");
        d.code = o.optString("code", "");
        d.method = o.optString("method", "GET").toUpperCase(java.util.Locale.ROOT);
        d.url = o.optString("url", "");
        JSONObject h = o.optJSONObject("headers");
        d.headers = h == null ? new JSONObject() : h;
        d.bodyTemplate = o.optString("bodyTemplate", "");
        d.secretHeader = o.optString("secretHeader", "");
        d.secret = o.optString("secret", "");
        d.timeoutS = o.optInt("timeoutS", 60);
        try {
            d.risk = RiskLevel.valueOf(o.optString("risk", "MEDIUM"));
        } catch (IllegalArgumentException e) {
            d.risk = RiskLevel.MEDIUM;
        }
        d.enabled = o.optBoolean("enabled", true);
        return d;
    }
}
