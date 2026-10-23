package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.suvo.agent.core.agent.TaskState;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persists task progress after every step; separate from long-term memory. */
public final class TaskRepo {

    private final Db db;

    public TaskRepo(Db db) {
        this.db = db;
    }

    public void save(TaskState t) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("id", t.id);
        cv.put("conversation_id", t.conversationId);
        cv.put("status", t.status.name());
        cv.put("body", t.toJson().toString());
        cv.put("created_at", t.startedAt == 0 ? now : t.startedAt);
        cv.put("updated_at", now);
        db.getWritableDatabase().insertWithOnConflict("tasks", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    public TaskState get(String id) {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT body FROM tasks WHERE id = ?", new String[]{id})) {
            return c.moveToFirst() ? parse(c.getString(0)) : null;
        }
    }

    public List<TaskState> recent(int limit) {
        List<TaskState> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT body FROM tasks ORDER BY updated_at DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                TaskState t = parse(c.getString(0));
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    /** The latest non-terminal task of a conversation (e.g. interrupted by a process kill). */
    public TaskState latestOpen(long conversationId) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT body FROM tasks WHERE conversation_id = ? AND status NOT IN ('COMPLETED','FAILED','CANCELLED','LIMIT_REACHED') ORDER BY updated_at DESC LIMIT 1",
                new String[]{String.valueOf(conversationId)})) {
            return c.moveToFirst() ? parse(c.getString(0)) : null;
        }
    }

    /** Tasks that were running when the process died are marked failed with a clear reason. */
    public int markInterrupted() {
        List<TaskState> open = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT body FROM tasks WHERE status IN ('RUNNING','PAUSED','WAITING_APPROVAL','PENDING')", null)) {
            while (c.moveToNext()) {
                TaskState t = parse(c.getString(0));
                if (t != null) open.add(t);
            }
        }
        for (TaskState t : open) {
            t.status = TaskState.Status.FAILED;
            t.error = "Interrupted: the app was stopped by Android while this task was running.";
            t.finishedAt = System.currentTimeMillis();
            save(t);
        }
        return open.size();
    }

    private static TaskState parse(String body) {
        try {
            return TaskState.fromJson(new JSONObject(body));
        } catch (JSONException e) {
            return null;
        }
    }
}
