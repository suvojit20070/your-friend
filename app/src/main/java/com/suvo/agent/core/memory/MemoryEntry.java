package com.suvo.agent.core.memory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One durable memory. */
public final class MemoryEntry {

    public long id = -1;
    public String content = "";
    public MemoryCategory category = MemoryCategory.FACT;
    public long createdAt;
    public long updatedAt;
    /** Conversation or task id the memory came from ("" when manual). */
    public String source = "";
    /** 1 (trivial) .. 5 (critical). */
    public int importance = 3;
    public List<String> tags = new ArrayList<>();
    /** 0 = never expires. */
    public long expiresAt;
    /** Whether the user pinned / explicitly asked to save it (never auto-deleted). */
    public boolean pinned;
    public int useCount;
    public long lastUsedAt;

    public boolean isExpired(long now) {
        return expiresAt > 0 && now > expiresAt;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("content", content).put("category", category.name())
                    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("source", source)
                    .put("importance", importance).put("expiresAt", expiresAt).put("pinned", pinned)
                    .put("useCount", useCount).put("lastUsedAt", lastUsedAt);
            JSONArray t = new JSONArray();
            for (String s : tags) t.put(s);
            o.put("tags", t);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static MemoryEntry fromJson(JSONObject o) {
        MemoryEntry m = new MemoryEntry();
        m.id = o.optLong("id", -1);
        m.content = o.optString("content", "");
        m.category = MemoryCategory.parse(o.optString("category"), MemoryCategory.FACT);
        m.createdAt = o.optLong("createdAt");
        m.updatedAt = o.optLong("updatedAt");
        m.source = o.optString("source", "");
        m.importance = Math.max(1, Math.min(5, o.optInt("importance", 3)));
        m.expiresAt = o.optLong("expiresAt");
        m.pinned = o.optBoolean("pinned");
        m.useCount = o.optInt("useCount");
        m.lastUsedAt = o.optLong("lastUsedAt");
        JSONArray t = o.optJSONArray("tags");
        if (t != null) for (int i = 0; i < t.length(); i++) m.tags.add(t.optString(i));
        return m;
    }

    public String tagsCsv() {
        return String.join(",", tags);
    }
}
