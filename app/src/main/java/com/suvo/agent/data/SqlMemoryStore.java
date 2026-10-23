package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryStore;
import com.suvo.agent.core.util.TextUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite + FTS4 implementation of persistent memory (survives restarts and context truncation). */
public final class SqlMemoryStore implements MemoryStore {

    private final Db db;

    public SqlMemoryStore(Db db) {
        this.db = db;
    }

    @Override
    public MemoryEntry add(MemoryEntry e) {
        long now = System.currentTimeMillis();
        if (e.createdAt == 0) e.createdAt = now;
        e.updatedAt = now;
        e.id = db.getWritableDatabase().insertOrThrow("memories", null, values(e));
        return e;
    }

    @Override
    public boolean update(MemoryEntry e) {
        e.updatedAt = System.currentTimeMillis();
        return db.getWritableDatabase().update("memories", values(e), "id = ?", new String[]{String.valueOf(e.id)}) == 1;
    }

    @Override
    public boolean delete(long id) {
        return db.getWritableDatabase().delete("memories", "id = ?", new String[]{String.valueOf(id)}) == 1;
    }

    public int deleteAll() {
        return db.getWritableDatabase().delete("memories", null, null);
    }

    @Override
    public MemoryEntry get(long id) {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT * FROM memories WHERE id = ?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    @Override
    public List<MemoryEntry> searchCandidates(String query, MemoryCategory cat, int limit) {
        long now = System.currentTimeMillis();
        Set<String> tokens = TextUtil.tokens(query);
        Map<Long, MemoryEntry> out = new LinkedHashMap<>();
        if (!tokens.isEmpty()) {
            // FTS4 OR query over word prefixes; tokens are letters/digits only, so no syntax injection.
            StringBuilder match = new StringBuilder();
            for (String t : tokens) {
                if (match.length() > 0) match.append(" OR ");
                match.append(t.replace("\"", "")).append('*');
            }
            String sql = "SELECT m.* FROM memories m JOIN memories_fts f ON f.docid = m.id WHERE memories_fts MATCH ?"
                    + " AND (m.expires_at = 0 OR m.expires_at > ?)" + (cat != null ? " AND m.category = ?" : "") + " LIMIT ?";
            List<String> args = new ArrayList<>(Arrays.asList(match.toString(), String.valueOf(now)));
            if (cat != null) args.add(cat.name());
            args.add(String.valueOf(limit));
            try (Cursor c = db.getReadableDatabase().rawQuery(sql, args.toArray(new String[0]))) {
                while (c.moveToNext()) {
                    MemoryEntry e = read(c);
                    out.put(e.id, e);
                }
            } catch (SQLiteException ignored) {
                // Unusual tokens can break MATCH syntax; fall back to LIKE below.
            }
            if (out.size() < limit) {
                for (String t : tokens) {
                    String sql2 = "SELECT * FROM memories WHERE (content LIKE ? OR tags LIKE ?) AND (expires_at = 0 OR expires_at > ?)"
                            + (cat != null ? " AND category = ?" : "") + " LIMIT ?";
                    String like = "%" + t + "%";
                    List<String> a2 = new ArrayList<>(Arrays.asList(like, like, String.valueOf(now)));
                    if (cat != null) a2.add(cat.name());
                    a2.add(String.valueOf(limit));
                    try (Cursor c = db.getReadableDatabase().rawQuery(sql2, a2.toArray(new String[0]))) {
                        while (c.moveToNext() && out.size() < limit) {
                            MemoryEntry e = read(c);
                            out.put(e.id, e);
                        }
                    }
                }
            }
        }
        // Always include pinned memories and standing instructions as candidates.
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT * FROM memories WHERE (pinned = 1 OR category = 'INSTRUCTION') AND (expires_at = 0 OR expires_at > ?) LIMIT 30",
                new String[]{String.valueOf(now)})) {
            while (c.moveToNext()) {
                MemoryEntry e = read(c);
                if (cat == null || e.category == cat) out.put(e.id, e);
            }
        }
        return new ArrayList<>(out.values());
    }

    @Override
    public List<MemoryEntry> all(MemoryCategory cat, int limit) {
        List<MemoryEntry> out = new ArrayList<>();
        String sql = "SELECT * FROM memories" + (cat != null ? " WHERE category = ?" : "") + " ORDER BY pinned DESC, updated_at DESC LIMIT ?";
        String[] args = cat != null ? new String[]{cat.name(), String.valueOf(limit)} : new String[]{String.valueOf(limit)};
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    @Override
    public void markUsed(List<Long> ids, long when) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (Long id : ids) {
                w.execSQL("UPDATE memories SET use_count = use_count + 1, last_used_at = ? WHERE id = ?", new Object[]{when, id});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    @Override
    public int count() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM memories", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Removes expired, unpinned entries. */
    public int pruneExpired() {
        return db.getWritableDatabase().delete("memories", "pinned = 0 AND expires_at > 0 AND expires_at < ?",
                new String[]{String.valueOf(System.currentTimeMillis())});
    }

    private static ContentValues values(MemoryEntry e) {
        ContentValues cv = new ContentValues();
        cv.put("content", e.content);
        cv.put("category", e.category.name());
        cv.put("source", e.source == null ? "" : e.source);
        cv.put("importance", Math.max(1, Math.min(5, e.importance)));
        cv.put("tags", e.tagsCsv());
        cv.put("pinned", e.pinned ? 1 : 0);
        cv.put("expires_at", e.expiresAt);
        cv.put("use_count", e.useCount);
        cv.put("last_used_at", e.lastUsedAt);
        cv.put("created_at", e.createdAt);
        cv.put("updated_at", e.updatedAt);
        return cv;
    }

    private static MemoryEntry read(Cursor c) {
        MemoryEntry e = new MemoryEntry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.content = c.getString(c.getColumnIndexOrThrow("content"));
        e.category = MemoryCategory.parse(c.getString(c.getColumnIndexOrThrow("category")), MemoryCategory.FACT);
        e.source = c.getString(c.getColumnIndexOrThrow("source"));
        e.importance = c.getInt(c.getColumnIndexOrThrow("importance"));
        String tags = c.getString(c.getColumnIndexOrThrow("tags"));
        if (tags != null && !tags.isEmpty()) {
            for (String t : tags.split(",")) if (!t.trim().isEmpty()) e.tags.add(t.trim());
        }
        e.pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) != 0;
        e.expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at"));
        e.useCount = c.getInt(c.getColumnIndexOrThrow("use_count"));
        e.lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"));
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }
}
