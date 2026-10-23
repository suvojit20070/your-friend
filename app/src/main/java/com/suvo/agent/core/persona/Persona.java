package com.suvo.agent.core.persona;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A character the user defined. The PERSONALITY lives entirely in {@link #systemPrompt}
 * (the user writes it) — the app adds no fixed personality of its own.
 * voiceRef / model point to the character's OmniVoice reference and Live2D/VRM model.
 */
public class Persona {
    public String id = "aura";
    public String name = "Aura";
    public String systemPrompt = "";   // user-written; this IS the personality
    public String model = "";          // path to Live2D/VRM model folder (optional)
    public String voiceRef = "";       // OmniVoice reference clip id (optional)
    public String voiceLang = "";      // default language code for voice, "" = auto

    public static Persona fromJson(JSONObject o) throws JSONException {
        Persona p = new Persona();
        p.id = o.optString("id", "aura");
        p.name = o.optString("name", "Aura");
        p.systemPrompt = o.optString("systemPrompt", "");
        p.model = o.optString("model", "");
        p.voiceRef = o.optString("voiceRef", "");
        p.voiceLang = o.optString("voiceLang", "");
        return p;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id).put("name", name)
                .put("systemPrompt", systemPrompt)
                .put("model", model).put("voiceRef", voiceRef).put("voiceLang", voiceLang);
    }
}
