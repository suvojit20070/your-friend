package com.suvo.agent.core.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A skill = readable SKILL.md instructions plus metadata. Skills are guidance for the model only:
 * they can never grant permissions, run code by themselves, or change approval rules.
 */
public final class Skill {

    public String id = "";          // folder name, stable
    public String name = "";
    public String description = "";
    public String version = "1.0";
    public String author = "";
    public String license = "";
    public String source = "local"; // "builtin", "local", "import:<file>", "url:<url>"
    public List<String> triggers = new ArrayList<>();
    public List<String> requiredTools = new ArrayList<>();
    public List<String> requiredPermissions = new ArrayList<>();
    public List<String> dependencies = new ArrayList<>();
    public boolean enabled = true;
    /** Markdown body after the front matter. */
    public String instructions = "";
    /** Relative resource files bundled with the skill (listed, never executed automatically). */
    public List<String> resources = new ArrayList<>();

    public JSONObject toJson(boolean withInstructions) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("name", name).put("description", description).put("version", version)
                    .put("author", author).put("license", license).put("source", source).put("enabled", enabled)
                    .put("triggers", arr(triggers)).put("requiredTools", arr(requiredTools))
                    .put("requiredPermissions", arr(requiredPermissions)).put("dependencies", arr(dependencies))
                    .put("resources", arr(resources));
            if (withInstructions) o.put("instructions", instructions);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    private static JSONArray arr(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        return a;
    }

    /** One line for the system prompt's skill index. */
    public String indexLine() {
        StringBuilder sb = new StringBuilder("- ").append(id).append(": ").append(description);
        if (!triggers.isEmpty()) sb.append(" (use when: ").append(String.join("; ", triggers)).append(')');
        return sb.toString();
    }
}
