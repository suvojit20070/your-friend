package com.suvo.agent.data;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;

/**
 * Single SQLite database for conversations, memory (with FTS4), tasks, audit history, providers and
 * custom tools. Migrations are incremental and transactional; a corrupted database file is moved
 * aside (not deleted) and a fresh one is created so the app keeps working.
 */
public final class Db extends SQLiteOpenHelper {

    private static final String TAG = "YF.Db";
    public static final String NAME = "yourfriend.db";
    /** v1 initial schema; v2 adds conversation pinning and audit/task indexes; v3 adds ssh hosts. */
    public static final int VERSION = 3;

    private static volatile Db instance;

    public static Db get(Context ctx) {
        if (instance == null) {
            synchronized (Db.class) {
                if (instance == null) instance = new Db(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private Db(Context ctx) {
        super(ctx, NAME, null, VERSION, new BackupOnCorruption(ctx));
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV1(db);
        migrate(db, 1, VERSION);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        migrate(db, oldVersion, newVersion);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Downgrades happen only when an older APK is installed over a newer one. Keep the data file
        // intact by refusing destructive changes: newer columns/tables are simply ignored.
        Log.w(TAG, "Database downgrade " + oldVersion + " -> " + newVersion + " ignored");
    }

    private static void migrate(SQLiteDatabase db, int from, int to) {
        for (int v = from; v < to; v++) {
            db.beginTransaction();
            try {
                switch (v) {
                    case 1:
                        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_task ON audit(task_id)");
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_updated ON tasks(updated_at)");
                        break;
                    case 2:
                        db.execSQL("CREATE TABLE IF NOT EXISTS ssh_hosts ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, host TEXT NOT NULL, "
                                + "port INTEGER NOT NULL DEFAULT 22, username TEXT NOT NULL, auth TEXT NOT NULL, "
                                + "secret_enc TEXT, known_host_key TEXT, created_at INTEGER NOT NULL)");
                        break;
                    default:
                        break;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    private static void createV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '', "
                + "character_id TEXT NOT NULL DEFAULT '', summary TEXT NOT NULL DEFAULT '', "
                + "summary_updated_at INTEGER NOT NULL DEFAULT 0, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, "
                + "role TEXT NOT NULL, body TEXT NOT NULL, search_text TEXT NOT NULL DEFAULT '', "
                + "task_id TEXT NOT NULL DEFAULT '', compacted INTEGER NOT NULL DEFAULT 0, "
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id, id)");

        db.execSQL("CREATE TABLE memories ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, category TEXT NOT NULL, "
                + "source TEXT NOT NULL DEFAULT '', importance INTEGER NOT NULL DEFAULT 3, tags TEXT NOT NULL DEFAULT '', "
                + "pinned INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL DEFAULT 0, "
                + "use_count INTEGER NOT NULL DEFAULT 0, last_used_at INTEGER NOT NULL DEFAULT 0, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE VIRTUAL TABLE memories_fts USING fts4(content, tags)");
        db.execSQL("CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN "
                + "INSERT INTO memories_fts(docid, content, tags) VALUES (new.id, new.content, new.tags); END");
        db.execSQL("CREATE TRIGGER memories_au AFTER UPDATE ON memories BEGIN "
                + "UPDATE memories_fts SET content = new.content, tags = new.tags WHERE docid = new.id; END");
        db.execSQL("CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN "
                + "DELETE FROM memories_fts WHERE docid = old.id; END");

        db.execSQL("CREATE TABLE tasks ("
                + "id TEXT PRIMARY KEY, conversation_id INTEGER NOT NULL, status TEXT NOT NULL, "
                + "body TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE audit ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, task_id TEXT NOT NULL DEFAULT '', "
                + "call_id TEXT NOT NULL DEFAULT '', tool TEXT NOT NULL, phase TEXT NOT NULL, "
                + "detail TEXT NOT NULL DEFAULT '', args TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_audit_time ON audit(time)");

        db.execSQL("CREATE TABLE providers ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, api_key_enc TEXT NOT NULL DEFAULT '', "
                + "headers_enc TEXT NOT NULL DEFAULT '', is_default INTEGER NOT NULL DEFAULT 0, "
                + "enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE custom_tools ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, body TEXT NOT NULL, "
                + "secret_enc TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, "
                + "last_result TEXT NOT NULL DEFAULT '', last_error TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
    }

    /** Moves a corrupted database aside (keeps it for recovery) instead of silently deleting it. */
    private static final class BackupOnCorruption implements DatabaseErrorHandler {
        private final Context ctx;

        BackupOnCorruption(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }

        @Override
        public void onCorruption(SQLiteDatabase dbObj) {
            String path = dbObj.getPath();
            Log.e(TAG, "Database corruption detected: " + path);
            try {
                dbObj.close();
            } catch (Exception ignored) {
            }
            File f = new File(path);
            File backup = new File(ctx.getFilesDir(), "corrupt-" + System.currentTimeMillis() + "-" + f.getName());
            if (f.exists() && !f.renameTo(backup)) {
                Log.e(TAG, "Could not back up corrupted database; it will be recreated");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            new File(path + "-wal").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(path + "-shm").delete();
        }
    }
}
