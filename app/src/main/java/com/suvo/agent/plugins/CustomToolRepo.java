package com.suvo.agent.plugins;

import android.content.ContentValues;
import android.database.Cursor;

import com.suvo.agent.AppGraph;
import com.suvo.agent.data.Db;
import com.suvo.agent.data.SecretStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistence for user-defined tools; secrets are encrypted in their own column. */
public final class CustomToolRepo {

    private final Db db;
    private final SecretStore secrets;

    public CustomToolRepo(Db db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    public long save(CustomToolDef d) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("name", d.name);
        cv.put("body", d.toJson(false).toString());
        cv.put("secret_enc", secrets.encrypt(d.secret));
        cv.put("enabled", d.enabled ? 1 : 0);
        cv.put("updated_at", now);
        if (d.id >= 0) {
            db.getWritableDatabase().update("custom_tools", cv, "id = ?", new String[]{String.valueOf(d.id)});
        } else {
            cv.put("created_at", now);
            d.id = db.getWritableDatabase().insertOrThrow("custom_tools", null, cv);
        }
        return d.id;
    }

    public void recordRun(String name, String result, String error) {
        ContentValues cv = new ContentValues();
        cv.put("last_result", result == null ? "" : (result.length() > 4000 ? result.substring(0, 4000) : result));
        cv.put("last_error", error == null ? "" : (error.length() > 4000 ? error.substring(0, 4000) : error));
        db.getWritableDatabase().update("custom_tools", cv, "name = ?", new String[]{name});
    }

    public void setEnabled(String name, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update("custom_tools", cv, "name = ?", new String[]{name});
    }

    public void delete(String name) {
        db.getWritableDatabase().delete("custom_tools", "name = ?", new String[]{name});
    }

    public CustomToolDef get(String name) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, body, secret_enc, enabled, last_result, last_error FROM custom_tools WHERE name = ?", new String[]{name})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public List<CustomToolDef> list() {
        List<CustomToolDef> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, body, secret_enc, enabled, last_result, last_error FROM custom_tools ORDER BY name", null)) {
            while (c.moveToNext()) {
                CustomToolDef d = read(c);
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    private CustomToolDef read(Cursor c) {
        try {
            CustomToolDef d = CustomToolDef.fromJson(new JSONObject(c.getString(1)));
            d.id = c.getLong(0);
            d.secret = secrets.decrypt(c.getString(2));
            d.enabled = c.getInt(3) != 0;
            d.lastResult = c.getString(4);
            d.lastError = c.getString(5);
            return d;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Registers every enabled, valid custom tool with the registry. Invalid ones are skipped. */
    public void registerEnabled(AppGraph g) {
        for (CustomToolDef d : list()) {
            if (!d.enabled || d.validate() != null) continue;
            try {
                g.tools.register(new CustomToolAdapter(g, d));
            } catch (IllegalArgumentException ignored) {
                // name collides with a built-in tool
            }
        }
    }
}
