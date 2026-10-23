package com.suvo.agent.core.llm;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-configured OpenAI-compatible provider. The API key is held in memory only; persistence
 * stores it encrypted separately (see SecretStore) and {@link #toJson(boolean)} omits it by default.
 */
public final class ProviderConfig {

    public long id = -1;
    public String name = "";
    public String baseUrl = "";
    public String apiKey = "";
    public String model = "";
    public Map<String, String> headers = new LinkedHashMap<>();
    public int timeoutMs = 120_000;
    public int maxTokens = 0;          // 0 = do not send
    public double temperature = 0.7;
    public boolean sendTemperature = true;
    public double topP = 1.0;
    public boolean sendTopP = false;
    public boolean stream = true;
    public boolean enabled = true;
    public boolean isDefault = false;

    // Capabilities. These are declared by the user (or learned from errors), never assumed.
    public boolean supportsTools = true;
    public boolean supportsParallelToolCalls = true;
    public boolean supportsVision = false;
    public boolean supportsSystemRole = true;
    public boolean supportsStreamUsage = false;
    public int contextWindowTokens = 32_000;
    /**
     * Thinking level sent as "reasoning_effort": "" = provider default (nothing sent), or
     * none / low / medium / high. Supported by OpenAI reasoning models, Gemini's OpenAI endpoint,
     * OpenRouter and others; providers that reject it can be set back to "".
     */
    public String reasoningEffort = "";

    public String chatCompletionsUrl() {
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    public String modelsUrl() {
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) b = b.substring(0, b.length() - "/chat/completions".length());
        return b + "/models";
    }

    /** Validation errors in user-readable form, or null when valid. */
    public String validate() {
        if (name == null || name.trim().isEmpty()) return "Provider name is required";
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (!(b.startsWith("https://") || b.startsWith("http://"))) return "Base URL must start with http:// or https://";
        if (model == null || model.trim().isEmpty()) return "Model ID is required";
        if (timeoutMs < 1000 || timeoutMs > 600_000) return "Timeout must be between 1 and 600 seconds";
        if (temperature < 0 || temperature > 2) return "Temperature must be between 0 and 2";
        if (maxTokens < 0) return "Max tokens cannot be negative";
        if (contextWindowTokens < 1024) return "Context window must be at least 1024 tokens";
        for (String k : headers.keySet()) {
            if (k.trim().isEmpty() || k.contains("\n") || k.contains(":")) return "Invalid header name: " + k;
        }
        return null;
    }

    public JSONObject toJson(boolean includeSecret) {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name).put("baseUrl", baseUrl).put("model", model)
                    .put("timeoutMs", timeoutMs).put("maxTokens", maxTokens)
                    .put("temperature", temperature).put("sendTemperature", sendTemperature)
                    .put("topP", topP).put("sendTopP", sendTopP)
                    .put("stream", stream).put("enabled", enabled).put("isDefault", isDefault)
                    .put("supportsTools", supportsTools).put("supportsParallelToolCalls", supportsParallelToolCalls)
                    .put("supportsVision", supportsVision).put("supportsSystemRole", supportsSystemRole)
                    .put("supportsStreamUsage", supportsStreamUsage)
                    .put("contextWindowTokens", contextWindowTokens)
                    .put("reasoningEffort", reasoningEffort);
            JSONObject h = new JSONObject();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                // Header values may contain credentials; exported only with secrets.
                h.put(e.getKey(), includeSecret ? e.getValue() : "");
            }
            o.put("headers", h);
            if (includeSecret) o.put("apiKey", apiKey);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static ProviderConfig fromJson(JSONObject o) {
        ProviderConfig p = new ProviderConfig();
        p.name = o.optString("name", "");
        p.baseUrl = o.optString("baseUrl", "");
        p.model = o.optString("model", "");
        p.apiKey = o.optString("apiKey", "");
        p.timeoutMs = o.optInt("timeoutMs", 120_000);
        p.maxTokens = o.optInt("maxTokens", 0);
        p.temperature = o.optDouble("temperature", 0.7);
        p.sendTemperature = o.optBoolean("sendTemperature", true);
        p.topP = o.optDouble("topP", 1.0);
        p.sendTopP = o.optBoolean("sendTopP", false);
        p.stream = o.optBoolean("stream", true);
        p.enabled = o.optBoolean("enabled", true);
        p.isDefault = o.optBoolean("isDefault", false);
        p.supportsTools = o.optBoolean("supportsTools", true);
        p.supportsParallelToolCalls = o.optBoolean("supportsParallelToolCalls", true);
        p.supportsVision = o.optBoolean("supportsVision", false);
        p.supportsSystemRole = o.optBoolean("supportsSystemRole", true);
        p.supportsStreamUsage = o.optBoolean("supportsStreamUsage", false);
        p.contextWindowTokens = o.optInt("contextWindowTokens", 32_000);
        p.reasoningEffort = o.optString("reasoningEffort", "");
        JSONObject h = o.optJSONObject("headers");
        if (h != null) {
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                p.headers.put(k, h.optString(k, ""));
            }
        }
        return p;
    }

    public ProviderConfig copy() {
        ProviderConfig c = fromJson(toJson(true));
        c.id = id;
        return c;
    }
}
