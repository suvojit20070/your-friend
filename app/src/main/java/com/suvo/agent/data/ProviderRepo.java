package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.suvo.agent.core.llm.ProviderConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Provider configurations. API keys and header values are stored encrypted, separately from the body. */
public final class ProviderRepo {

    private final Db db;
    private final SecretStore secrets;

    public ProviderRepo(Db db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    public long save(ProviderConfig p) {
        long now = System.currentTimeMillis();
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("body", p.toJson(false).toString());
            cv.put("api_key_enc", secrets.encrypt(p.apiKey));
            JSONObject headers = new JSONObject();
            for (java.util.Map.Entry<String, String> e : p.headers.entrySet()) {
                try {
                    headers.put(e.getKey(), e.getValue());
                } catch (JSONException ignored) {
                }
            }
            cv.put("headers_enc", headers.length() == 0 ? "" : secrets.encrypt(headers.toString()));
            cv.put("is_default", p.isDefault ? 1 : 0);
            cv.put("enabled", p.enabled ? 1 : 0);
            cv.put("updated_at", now);
            if (p.isDefault) {
                ContentValues clear = new ContentValues();
                clear.put("is_default", 0);
                w.update("providers", clear, null, null);
            }
            if (p.id >= 0) {
                w.update("providers", cv, "id = ?", new String[]{String.valueOf(p.id)});
            } else {
                cv.put("created_at", now);
                p.id = w.insertOrThrow("providers", null, cv);
            }
            w.setTransactionSuccessful();
            return p.id;
        } finally {
            w.endTransaction();
        }
    }

    public void delete(long id) {
        db.getWritableDatabase().delete("providers", "id = ?", new String[]{String.valueOf(id)});
    }

    public void setDefault(long id) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues clear = new ContentValues();
            clear.put("is_default", 0);
            w.update("providers", clear, null, null);
            ContentValues set = new ContentValues();
            set.put("is_default", 1);
            w.update("providers", set, "id = ?", new String[]{String.valueOf(id)});
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    public List<ProviderConfig> list() {
        List<ProviderConfig> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, body, api_key_enc, headers_enc, is_default, enabled FROM providers ORDER BY is_default DESC, id", null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public ProviderConfig get(long id) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, body, api_key_enc, headers_enc, is_default, enabled FROM providers WHERE id = ?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    /** The default enabled provider, else the first enabled one, else null. */
    public ProviderConfig active() {
        ProviderConfig first = null;
        for (ProviderConfig p : list()) {
            if (!p.enabled) continue;
            if (p.isDefault) return p;
            if (first == null) first = p;
        }
        return first;
    }

    private ProviderConfig read(Cursor c) {
        ProviderConfig p;
        try {
            p = ProviderConfig.fromJson(new JSONObject(c.getString(1)));
        } catch (JSONException e) {
            p = new ProviderConfig();
            p.name = "(damaged provider entry)";
        }
        p.id = c.getLong(0);
        p.apiKey = secrets.decrypt(c.getString(2));
        p.headers.clear();
        String h = secrets.decrypt(c.getString(3));
        if (!h.isEmpty()) {
            try {
                JSONObject o = new JSONObject(h);
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    p.headers.put(k, o.optString(k));
                }
            } catch (JSONException ignored) {
            }
        }
        p.isDefault = c.getInt(4) != 0;
        p.enabled = c.getInt(5) != 0;
        return p;
    }
}
