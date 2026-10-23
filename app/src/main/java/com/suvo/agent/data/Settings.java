package com.suvo.agent.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.suvo.agent.core.llm.LlmConfig;

/** Non-secret settings (provider base/model, flags). API key lives in SecretStore. */
public class Settings {

    private final SharedPreferences p;
    private final SecretStore secrets;

    public Settings(Context ctx) {
        p = ctx.getSharedPreferences("aura_settings", Context.MODE_PRIVATE);
        secrets = new SecretStore(ctx);
    }

    public SecretStore secrets() { return secrets; }

    // ---- LLM provider ----
    public LlmConfig llm() {
        LlmConfig c = new LlmConfig();
        c.name = p.getString("llm.name", "default");
        c.baseUrl = p.getString("llm.baseUrl", "");
        c.model = p.getString("llm.model", "");
        c.stream = p.getBoolean("llm.stream", true);
        c.maxTokens = p.getInt("llm.maxTokens", 0);
        c.temperature = Double.parseDouble(p.getString("llm.temp", "0.8"));
        c.apiKey = secrets.get("llm.apiKey", "");
        return c;
    }

    public void saveLlm(LlmConfig c) {
        p.edit()
         .putString("llm.name", c.name)
         .putString("llm.baseUrl", c.baseUrl)
         .putString("llm.model", c.model)
         .putBoolean("llm.stream", c.stream)
         .putInt("llm.maxTokens", c.maxTokens)
         .putString("llm.temp", String.valueOf(c.temperature))
         .apply();
        secrets.put("llm.apiKey", c.apiKey == null ? "" : c.apiKey);
    }

    // ---- misc ----
    public String activePersonaId() { return p.getString("persona.active", "friend"); }
    public void setActivePersonaId(String id) { p.edit().putString("persona.active", id).apply(); }

    public boolean dynamicColor() { return p.getBoolean("ui.dynamicColor", true); }
    public void setDynamicColor(boolean on) { p.edit().putBoolean("ui.dynamicColor", on).apply(); }

    public String character() { return p.getString("ui.character", "girl"); } // girl | boy
    public void setCharacter(String style) { p.edit().putString("ui.character", style).apply(); }

    public String uiLanguage() { return p.getString("ui.lang", ""); } // "" = system
    public void setUiLanguage(String code) { p.edit().putString("ui.lang", code).apply(); }

    public boolean toolEnabled(String name, boolean def) {
        return p.getBoolean("tool." + name, def);
    }
    public void setToolEnabled(String name, boolean on) {
        p.edit().putBoolean("tool." + name, on).apply();
    }
}
