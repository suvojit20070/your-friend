package com.suvo.agent.data;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/** SSH hosts the user configured explicitly. The agent can only connect to hosts in this list. */
public final class SshHostRepo {

    public static final class Host {
        public long id = -1;
        public String name = "";
        public String host = "";
        public int port = 22;
        public String username = "";
        /** "password" or "key". */
        public String auth = "password";
        /** Password or private key (PEM), decrypted in memory only. */
        public String secret = "";
        /** Pinned host key fingerprint ("" = learn on first connect, then pin). */
        public String knownHostKey = "";
    }

    private final Db db;
    private final SecretStore secrets;

    public SshHostRepo(Db db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    public long save(Host h) {
        ContentValues cv = new ContentValues();
        cv.put("name", h.name.trim());
        cv.put("host", h.host.trim());
        cv.put("port", h.port);
        cv.put("username", h.username.trim());
        cv.put("auth", h.auth);
        cv.put("secret_enc", secrets.encrypt(h.secret));
        cv.put("known_host_key", h.knownHostKey == null ? "" : h.knownHostKey);
        if (h.id >= 0) {
            db.getWritableDatabase().update("ssh_hosts", cv, "id = ?", new String[]{String.valueOf(h.id)});
        } else {
            cv.put("created_at", System.currentTimeMillis());
            h.id = db.getWritableDatabase().insertOrThrow("ssh_hosts", null, cv);
        }
        return h.id;
    }

    public void pinHostKey(long id, String fingerprint) {
        ContentValues cv = new ContentValues();
        cv.put("known_host_key", fingerprint);
        db.getWritableDatabase().update("ssh_hosts", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        db.getWritableDatabase().delete("ssh_hosts", "id = ?", new String[]{String.valueOf(id)});
    }

    public List<Host> list() {
        List<Host> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, name, host, port, username, auth, secret_enc, known_host_key FROM ssh_hosts ORDER BY name", null)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public Host byName(String name) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, name, host, port, username, auth, secret_enc, known_host_key FROM ssh_hosts WHERE name = ?", new String[]{name})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    private Host read(Cursor c) {
        Host h = new Host();
        h.id = c.getLong(0);
        h.name = c.getString(1);
        h.host = c.getString(2);
        h.port = c.getInt(3);
        h.username = c.getString(4);
        h.auth = c.getString(5);
        h.secret = secrets.decrypt(c.getString(6));
        h.knownHostKey = c.getString(7);
        return h;
    }
}
