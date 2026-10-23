package com.suvo.agent.core.skills;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A user-defined tool loaded from a JSON file. Declarative and safe:
 * it can do an HTTP call, open an intent (app/url), or run a sandboxed script.
 * It never touches device APIs directly — those are built-in tools only.
 *
 * Example JSON:
 * {
 *   "name": "web_search",
 *   "description": "Search the web",
 *   "danger": "low",
 *   "action": { "type": "http", "method": "GET",
 *               "url": "https://api.x.com/s?q={{query}}&key={{secret.SEARCH_KEY}}",
 *               "pick": "results" },
 *   "parameters": { "type": "object",
 *                   "properties": { "query": { "type": "string" } },
 *                   "required": ["query"] }
 * }
 */
public class Skill {
    public String name;
    public String description = "";
    public String danger = "low";
    public JSONObject parameters;   // JSON schema
    public JSONObject action;       // {type: http|intent|script, ...}
    public boolean enabled = true;

    public static Skill fromJson(JSONObject o) throws JSONException {
        Skill s = new Skill();
        s.name = o.getString("name");
        s.description = o.optString("description", "");
        s.danger = o.optString("danger", "low");
        s.parameters = o.optJSONObject("parameters");
        s.action = o.optJSONObject("action");
        s.enabled = o.optBoolean("enabled", true);
        if (s.action == null) throw new JSONException("skill '" + s.name + "' has no action");
        return s;
    }

    public String actionType() {
        return action != null ? action.optString("type", "http") : "http";
    }
}
