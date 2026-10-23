package com.suvo.agent.character;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A companion character: identity, personality prompt, voice/language preferences, and the rig
 * appearance used by the procedural renderer. Character files never contain secrets.
 */
public final class CharacterSpec {

    public String id = "";
    public String name = "";
    public String description = "";
    public String personality = "";
    /** Extra instructions appended to the system prompt (user-editable). */
    public String systemPrompt = "";
    public String greeting = "";
    public boolean builtin;
    public List<String> languages = new ArrayList<>();
    public String voiceNote = "";

    // Rig appearance
    public String base = "girl";          // girl | boy
    public String hairStyle = "long";     // long | twintails | bob | short | spiky
    public String eyeShape = "round";     // round | sharp
    public String outfit = "sailor";      // sailor | hoodie | blouse | jacket
    public String accessory = "ribbon";   // ribbon | clip | glasses | none
    public int hairColor = 0xFF3B2A4D;
    public int eyeColor = 0xFF7B5CFF;
    public int skinColor = 0xFFFFE3D3;
    public int outfitColor = 0xFF2B3A67;
    public int accentColor = 0xFFE5486B;

    // Animation behaviour
    public String defaultExpression = "neutral";
    public float idleIntensity = 1.0f;    // 0.2 .. 1.5
    public float blinkRate = 1.0f;        // multiplier
    public float expressiveness = 1.0f;   // 0.3 .. 1.5
    public boolean wander = false;        // drift around the companion area when idle

    // Behaviour preferences
    public List<String> touchReactions = new ArrayList<>();

    /** Text injected as the character layer of the system prompt. */
    public String personaPrompt() {
        StringBuilder sb = new StringBuilder();
        if (!description.isEmpty()) sb.append(description.trim()).append('\n');
        if (!personality.isEmpty()) sb.append("Personality: ").append(personality.trim()).append('\n');
        if (!languages.isEmpty()) sb.append("Languages you like to use: ").append(String.join(", ", languages)).append('\n');
        if (!systemPrompt.isEmpty()) sb.append(systemPrompt.trim());
        return sb.toString().trim();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("name", name).put("description", description).put("personality", personality)
                    .put("systemPrompt", systemPrompt).put("greeting", greeting).put("voiceNote", voiceNote)
                    .put("languages", new JSONArray(languages));
            JSONObject rig = new JSONObject();
            rig.put("base", base).put("hairStyle", hairStyle).put("eyeShape", eyeShape).put("outfit", outfit)
                    .put("accessory", accessory).put("hairColor", hex(hairColor)).put("eyeColor", hex(eyeColor))
                    .put("skinColor", hex(skinColor)).put("outfitColor", hex(outfitColor)).put("accentColor", hex(accentColor));
            o.put("rig", rig);
            JSONObject anim = new JSONObject();
            anim.put("defaultExpression", defaultExpression).put("idleIntensity", idleIntensity)
                    .put("blinkRate", blinkRate).put("expressiveness", expressiveness).put("wander", wander);
            o.put("animation", anim);
            o.put("touchReactions", new JSONArray(touchReactions));
            o.put("format", "yourfriend-character/1");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static CharacterSpec fromJson(JSONObject o) {
        CharacterSpec c = new CharacterSpec();
        c.id = o.optString("id", "");
        c.name = o.optString("name", "Friend");
        c.description = o.optString("description", "");
        c.personality = o.optString("personality", "");
        c.systemPrompt = o.optString("systemPrompt", "");
        c.greeting = o.optString("greeting", "");
        c.voiceNote = o.optString("voiceNote", "");
        JSONArray langs = o.optJSONArray("languages");
        if (langs != null) for (int i = 0; i < langs.length(); i++) c.languages.add(langs.optString(i));
        JSONObject rig = o.optJSONObject("rig");
        if (rig != null) {
            c.base = oneOf(rig.optString("base", "girl"), "girl", "boy");
            c.hairStyle = oneOf(rig.optString("hairStyle", c.base.equals("boy") ? "short" : "long"), "long", "twintails", "bob", "short", "spiky");
            c.eyeShape = oneOf(rig.optString("eyeShape", "round"), "round", "sharp");
            c.outfit = oneOf(rig.optString("outfit", "sailor"), "sailor", "hoodie", "blouse", "jacket");
            c.accessory = oneOf(rig.optString("accessory", "none"), "ribbon", "clip", "glasses", "none");
            c.hairColor = color(rig.optString("hairColor"), c.hairColor);
            c.eyeColor = color(rig.optString("eyeColor"), c.eyeColor);
            c.skinColor = color(rig.optString("skinColor"), c.skinColor);
            c.outfitColor = color(rig.optString("outfitColor"), c.outfitColor);
            c.accentColor = color(rig.optString("accentColor"), c.accentColor);
        }
        JSONObject anim = o.optJSONObject("animation");
        if (anim != null) {
            c.defaultExpression = anim.optString("defaultExpression", "neutral");
            c.idleIntensity = clamp((float) anim.optDouble("idleIntensity", 1), 0.2f, 1.5f);
            c.blinkRate = clamp((float) anim.optDouble("blinkRate", 1), 0.2f, 3f);
            c.expressiveness = clamp((float) anim.optDouble("expressiveness", 1), 0.3f, 1.5f);
            c.wander = anim.optBoolean("wander", false);
        }
        JSONArray tr = o.optJSONArray("touchReactions");
        if (tr != null) for (int i = 0; i < tr.length(); i++) c.touchReactions.add(tr.optString(i));
        return c;
    }

    private static String oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equals(v)) return v;
        return allowed[0];
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    public static int color(String s, int def) {
        if (s == null) return def;
        String t = s.trim();
        if (!t.matches("#?[0-9a-fA-F]{6}")) return def;
        if (t.startsWith("#")) t = t.substring(1);
        return 0xFF000000 | Integer.parseInt(t, 16);
    }

    public CharacterSpec copy() {
        CharacterSpec c = fromJson(toJson());
        c.builtin = builtin;
        return c;
    }
}
