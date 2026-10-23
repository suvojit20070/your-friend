package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ToolCall;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Conversations and their messages. Messages are never deleted by compaction, only flagged. */
public final class ConversationRepo {

    public static final class Conversation {
        public long id;
        public String title;
        public String characterId;
        public String summary;
        public long createdAt;
        public long updatedAt;
        public boolean pinned;
        public int messageCount;
    }

    public static final class StoredMessage {
        public long id;
        public long conversationId;
        public ChatMessage message;
        public String taskId;
        public boolean compacted;
        public long createdAt;
    }

    private final Db db;

    public ConversationRepo(Db db) {
        this.db = db;
    }

    public long create(String title, String characterId) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("character_id", characterId == null ? "" : characterId);
        cv.put("created_at", now);
        cv.put("updated_at", now);
        return db.getWritableDatabase().insertOrThrow("conversations", null, cv);
    }

    public Conversation get(long id) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS n FROM conversations c WHERE c.id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    /** Conversations ordered by pin then recency; {@code query} searches titles and message text. */
    public List<Conversation> list(String query, int limit) {
        List<Conversation> out = new ArrayList<>();
        String sql;
        String[] args;
        if (query == null || query.trim().isEmpty()) {
            sql = "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS n FROM conversations c "
                    + "ORDER BY c.pinned DESC, c.updated_at DESC LIMIT ?";
            args = new String[]{String.valueOf(limit)};
        } else {
            String like = "%" + query.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            sql = "SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS n FROM conversations c "
                    + "WHERE c.title LIKE ? ESCAPE '\\' OR c.id IN (SELECT conversation_id FROM messages WHERE search_text LIKE ? ESCAPE '\\') "
                    + "ORDER BY c.pinned DESC, c.updated_at DESC LIMIT ?";
            args = new String[]{like, like, String.valueOf(limit)};
        }
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    private static Conversation read(Cursor c) {
        Conversation cv = new Conversation();
        cv.id = c.getLong(c.getColumnIndexOrThrow("id"));
        cv.title = c.getString(c.getColumnIndexOrThrow("title"));
        cv.characterId = c.getString(c.getColumnIndexOrThrow("character_id"));
        cv.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        cv.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        cv.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        int p = c.getColumnIndex("pinned");
        cv.pinned = p >= 0 && c.getInt(p) != 0;
        int n = c.getColumnIndex("n");
        cv.messageCount = n >= 0 ? c.getInt(n) : 0;
        return cv;
    }

    public void rename(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        db.getWritableDatabase().update("conversations", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void setPinned(long id, boolean pinned) {
        ContentValues cv = new ContentValues();
        cv.put("pinned", pinned ? 1 : 0);
        db.getWritableDatabase().update("conversations", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.delete("messages", "conversation_id = ?", new String[]{String.valueOf(id)});
            w.delete("tasks", "conversation_id = ?", new String[]{String.valueOf(id)});
            w.delete("conversations", "id = ?", new String[]{String.valueOf(id)});
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    public void deleteAll() {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.delete("messages", null, null);
            w.delete("tasks", null, null);
            w.delete("conversations", null, null);
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    public long append(long conversationId, ChatMessage m, String taskId) {
        long now = System.currentTimeMillis();
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("conversation_id", conversationId);
            cv.put("role", m.role.wire);
            cv.put("body", m.toStorageJson().toString());
            cv.put("search_text", searchText(m));
            cv.put("task_id", taskId == null ? "" : taskId);
            cv.put("created_at", now);
            long id = w.insertOrThrow("messages", null, cv);
            ContentValues up = new ContentValues();
            up.put("updated_at", now);
            w.update("conversations", up, "id = ?", new String[]{String.valueOf(conversationId)});
            w.setTransactionSuccessful();
            m.localId = id;
            return id;
        } finally {
            w.endTransaction();
        }
    }

    private static String searchText(ChatMessage m) {
        if (m.role == ChatMessage.Role.TOOL) return "";
        StringBuilder sb = new StringBuilder(m.contentOrEmpty());
        for (ToolCall c : m.toolCalls) sb.append(' ').append(c.name);
        String s = sb.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    public List<StoredMessage> messages(long conversationId, boolean includeCompacted) {
        List<StoredMessage> out = new ArrayList<>();
        String sql = "SELECT id, conversation_id, body, task_id, compacted, created_at FROM messages WHERE conversation_id = ?"
                + (includeCompacted ? "" : " AND compacted = 0") + " ORDER BY id";
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(conversationId)})) {
            while (c.moveToNext()) {
                StoredMessage sm = new StoredMessage();
                sm.id = c.getLong(0);
                sm.conversationId = c.getLong(1);
                try {
                    sm.message = ChatMessage.fromStorageJson(new JSONObject(c.getString(2)));
                } catch (JSONException | IllegalArgumentException e) {
                    // A corrupted row is shown as a notice rather than crashing the conversation.
                    sm.message = ChatMessage.assistant("[unreadable message]");
                }
                sm.message.localId = sm.id;
                sm.taskId = c.getString(3);
                sm.compacted = c.getInt(4) != 0;
                sm.createdAt = c.getLong(5);
                out.add(sm);
            }
        }
        return out;
    }

    public String summary(long conversationId) {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT summary FROM conversations WHERE id = ?",
                new String[]{String.valueOf(conversationId)})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    /**
     * Saves the new running summary and flags the summarized messages in ONE transaction, so either
     * both happen or neither does: raw messages are never hidden before their summary is durable.
     */
    public void applyCompaction(long conversationId, String summary, List<Long> compactedIds) {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("summary", summary);
            cv.put("summary_updated_at", System.currentTimeMillis());
            int n = w.update("conversations", cv, "id = ?", new String[]{String.valueOf(conversationId)});
            if (n != 1) throw new IllegalStateException("conversation missing");
            ContentValues flag = new ContentValues();
            flag.put("compacted", 1);
            for (Long id : compactedIds) {
                w.update("messages", flag, "id = ? AND conversation_id = ?", new String[]{String.valueOf(id), String.valueOf(conversationId)});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    public int count() {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM conversations", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
