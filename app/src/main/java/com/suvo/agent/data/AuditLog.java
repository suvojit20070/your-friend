package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.suvo.agent.core.tools.ToolEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Durable execution audit trail (arguments are redacted before they get here). */
public final class AuditLog implements ToolEvent.Sink {

    public static final class Row {
        public long id;
        public long time;
        public String taskId;
        public String callId;
        public String tool;
        public String phase;
        public String detail;
        public String args;
    }

    private final Db db;
    private final CopyOnWriteArrayList<ToolEvent.Sink> live = new CopyOnWriteArrayList<>();
    private int sinceTrim;

    public AuditLog(Db db) {
        this.db = db;
    }

    public void addLiveListener(ToolEvent.Sink s) {
        live.addIfAbsent(s);
    }

    public void removeLiveListener(ToolEvent.Sink s) {
        live.remove(s);
    }

    @Override
    public void onEvent(ToolEvent e) {
        ContentValues cv = new ContentValues();
        cv.put("time", e.timestamp);
        cv.put("task_id", e.taskId == null ? "" : e.taskId);
        cv.put("call_id", e.callId == null ? "" : e.callId);
        cv.put("tool", e.toolName);
        cv.put("phase", e.phase.name());
        cv.put("detail", e.detail.length() > 2000 ? e.detail.substring(0, 2000) : e.detail);
        cv.put("args", e.redactedArgs);
        try {
            db.getWritableDatabase().insert("audit", null, cv);
            if (++sinceTrim > 200) {
                sinceTrim = 0;
                trim(5000);
            }
        } catch (RuntimeException ignored) {
            // Audit must never break tool execution.
        }
        for (ToolEvent.Sink s : live) s.onEvent(e);
    }

    public List<Row> recent(String toolOrNull, int limit) {
        List<Row> out = new ArrayList<>();
        String sql = "SELECT id, time, task_id, call_id, tool, phase, detail, args FROM audit"
                + (toolOrNull != null ? " WHERE tool = ?" : "") + " ORDER BY id DESC LIMIT ?";
        String[] args = toolOrNull != null ? new String[]{toolOrNull, String.valueOf(limit)} : new String[]{String.valueOf(limit)};
        try (Cursor c = db.getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(0);
                r.time = c.getLong(1);
                r.taskId = c.getString(2);
                r.callId = c.getString(3);
                r.tool = c.getString(4);
                r.phase = c.getString(5);
                r.detail = c.getString(6);
                r.args = c.getString(7);
                out.add(r);
            }
        }
        return out;
    }

    public void clear() {
        db.getWritableDatabase().delete("audit", null, null);
    }

    private void trim(int keep) {
        db.getWritableDatabase().execSQL("DELETE FROM audit WHERE id <= (SELECT MAX(id) FROM audit) - ?", new Object[]{keep});
    }
}
