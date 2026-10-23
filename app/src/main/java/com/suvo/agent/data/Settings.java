package com.suvo.agent.data;

import android.content.Context;
import android.content.SharedPreferences;

<<<<<<< HEAD
import com.suvo.agent.core.agent.AgentConfig;
import com.suvo.agent.core.tools.ApprovalPolicy;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Typed application preferences (SharedPreferences). Listeners are notified on every change so
 * services (voice, overlay, agent) pick up new values without a restart.
 */
public final class Settings {

    public interface Listener {
        void onSettingChanged(String key);
    }

    // Appearance
    public static final String THEME = "theme";                       // system | dark | light
    public static final String ACCENT = "accent";                     // dynamic | #RRGGBB
    public static final String UI_LANGUAGE = "ui_language";           // "" (system) | en | hi-Latn | bn | hi | ja
    public static final String REDUCED_MOTION = "reduced_motion";
    public static final String ANIMATION_QUALITY = "animation_quality"; // low | medium | high
    public static final String CHARACTER_SIZE = "character_size";     // 50..150 (%)
    public static final String CHARACTER_POS_X = "character_pos_x";   // 0..1
    public static final String CHARACTER_POS_Y = "character_pos_y";
    public static final String ONBOARDED = "onboarded";

    // Personality / agent
    public static final String ACTIVE_CHARACTER = "active_character";
    public static final String RESPONSE_LANGUAGE = "response_language"; // free text, "" = match the user
    public static final String TONE = "tone";
    public static final String AUTONOMY = "autonomy";                 // ASSIST | CONFIRM | AUTONOMOUS
    public static final String MAX_STEPS = "max_steps";
    public static final String MAX_TOOL_CALLS = "max_tool_calls";
    public static final String MAX_TASK_MINUTES = "max_task_minutes";
    public static final String TOOL_CALLING = "tool_calling";
    public static final String ALWAYS_ASK_TOOLS = "always_ask_tools";
    public static final String BLOCKED_TOOLS = "blocked_tools";
    public static final String DISABLED_TOOLS = "disabled_tools";

    // Memory
    public static final String AUTO_MEMORY = "auto_memory";
    public static final String MEMORY_RETRIEVAL = "memory_retrieval";
    public static final String MEMORY_MAX_INJECTED = "memory_max_injected";
    public static final String CONTEXT_TRIGGER = "context_trigger";   // percent of window
    public static final String SUMMARIES = "summaries";

    // Privacy toggles (capability kill switches)
    public static final String ALLOW_VOICE_INPUT = "allow_voice_input";
    public static final String ALLOW_BACKGROUND_LISTENING = "allow_background_listening";
    public static final String ALLOW_CAMERA = "allow_camera";
    public static final String ALLOW_SCREENSHOT = "allow_screenshot";
    public static final String ALLOW_SCREEN_CONTROL = "allow_screen_control";
    public static final String AUTO_TOOL_EXECUTION = "auto_tool_execution";
    public static final String ALLOW_LAN_WEB = "allow_lan_web";
    public static final String ALLOW_SHIZUKU = "allow_shizuku";

    // Voice
    public static final String VOICE_OUTPUT = "voice_output";
    public static final String VOICE_ENGINE = "voice_engine";         // omnivoice_native | omnivoice_server | system
    public static final String VOICE_SPEED = "voice_speed";
    public static final String VOICE_STEPS = "voice_steps";
    public static final String VOICE_LANGUAGE = "voice_language";
    public static final String VOICE_INSTRUCT = "voice_instruct";
    public static final String OMNI_LIB_PATH = "omni_lib_path";
    public static final String OMNI_MODEL_PATH = "omni_model_path";
    public static final String OMNI_THREADS = "omni_threads";
    public static final String OMNI_SERVER_URL = "omni_server_url";
    public static final String OMNI_SERVER_MODE = "omni_server_mode"; // clone_multipart | openai_speech
    public static final String OMNI_SERVER_VOICE = "omni_server_voice";
    public static final String REF_WAV_PATH = "ref_wav_path";
    public static final String REF_TEXT = "ref_text";
    public static final String STT_LANGUAGE = "stt_language";
    public static final String HANDS_FREE = "hands_free";

    // Web
    public static final String SEARCH_PROVIDER = "search_provider";   // duckduckgo | searxng | brave
    public static final String SEARXNG_URL = "searxng_url";

    // Terminal
    public static final String SHELL_BACKEND = "shell_backend";       // local | termux | shizuku
    public static final String TERMINAL_ENV = "terminal_env";         // KEY=VALUE lines

    // Overlay
    public static final String FLOATING_COMPANION = "floating_companion";

    private static volatile Settings instance;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static Settings get(Context ctx) {
        if (instance == null) {
            synchronized (Settings.class) {
                // getApplicationContext() is null during Application.attachBaseContext.
                Context app = ctx.getApplicationContext();
                if (instance == null) instance = new Settings(app != null ? app : ctx);
            }
        }
        return instance;
    }

    private Settings(Context ctx) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener((p, key) -> {
            for (Listener l : listeners) l.onSettingChanged(key);
        });
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public String str(String k, String def) {
        return prefs.getString(k, def);
    }

    public int integer(String k, int def) {
        try {
            return prefs.getInt(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public float flt(String k, float def) {
        try {
            return prefs.getFloat(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public boolean bool(String k, boolean def) {
        try {
            return prefs.getBoolean(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public Set<String> set(String k) {
        return new HashSet<>(prefs.getStringSet(k, new HashSet<>()));
    }

    public void put(String k, String v) {
        prefs.edit().putString(k, v).apply();
    }

    public void put(String k, int v) {
        prefs.edit().putInt(k, v).apply();
    }

    public void put(String k, float v) {
        prefs.edit().putFloat(k, v).apply();
    }

    public void put(String k, boolean v) {
        prefs.edit().putBoolean(k, v).apply();
    }

    public void put(String k, Set<String> v) {
        prefs.edit().putStringSet(k, new HashSet<>(v)).apply();
    }

    // ---- Typed accessors -------------------------------------------------------------------

    public ApprovalPolicy.AutonomyMode autonomy() {
        try {
            return ApprovalPolicy.AutonomyMode.valueOf(str(AUTONOMY, "CONFIRM"));
        } catch (IllegalArgumentException e) {
            return ApprovalPolicy.AutonomyMode.CONFIRM;
        }
    }

    public AgentConfig agentConfig() {
        AgentConfig c = new AgentConfig();
        c.maxSteps = clamp(integer(MAX_STEPS, 25), 1, 200);
        c.maxToolCalls = clamp(integer(MAX_TOOL_CALLS, 60), 1, 500);
        c.maxDurationMs = clamp(integer(MAX_TASK_MINUTES, 15), 1, 240) * 60_000L;
        return c;
    }

    public void applyTo(ApprovalPolicy p) {
        p.mode = autonomy();
        p.autoExecutionEnabled = bool(AUTO_TOOL_EXECUTION, true);
        p.alwaysAsk.clear();
        p.alwaysAsk.addAll(set(ALWAYS_ASK_TOOLS));
        p.neverAllow.clear();
        p.neverAllow.addAll(set(BLOCKED_TOOLS));
    }

    public float contextTriggerFraction() {
        return clamp(integer(CONTEXT_TRIGGER, 70), 30, 95) / 100f;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Export of all non-secret settings (secrets never live here). */
    public JSONObject export() {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            try {
                Object v = e.getValue();
                if (v instanceof Set) v = new org.json.JSONArray((Set<?>) v);
                o.put(e.getKey(), v);
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    public void importFrom(JSONObject o) {
        SharedPreferences.Editor ed = prefs.edit();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Double || v instanceof Float) ed.putFloat(k, ((Number) v).floatValue());
            else if (v instanceof Long) ed.putInt(k, ((Long) v).intValue());
            else if (v instanceof String) ed.putString(k, (String) v);
            else if (v instanceof org.json.JSONArray) {
                Set<String> s = new HashSet<>();
                org.json.JSONArray a = (org.json.JSONArray) v;
                for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
                ed.putStringSet(k, s);
            }
        }
        ed.apply();
=======
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
>>>>>>> 2fdbf158535950372900ccd3a8ad3b5b0f730b15
    }
}
