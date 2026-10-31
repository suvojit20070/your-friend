package com.suvo.agent.autoreply;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * Auto reply data: which apps are watched (one rule per package: name, context key, mode), the
 * activity log, totals, and a saved history per chat so the agent keeps context across app
 * restarts. Settings that are plain switches / texts live in the app's Settings (keys below) so
 * the settings rows can edit them directly.
 */
public final class AutoReplyStore {

    // Settings keys (com.suvo.agent.data.Settings).
    public static final String ENABLED = "ar_enabled";
    public static final String OWNER = "ar_owner_name";
    public static final String INSTRUCTIONS = "ar_instructions";
    public static final String BLOCKLIST = "ar_blocklist";
    public static final String MAX_WORDS = "ar_max_words";
    public static final String USE_MEMORY = "ar_use_memory";
    public static final String DEFAULT_BLOCKLIST = "otp, password, cvv, pin, verification code, code:";

    public static final String AUTO = "AUTO", DRAFT = "DRAFT", OFF = "OFF";

    public static final class Rule {
        public String pkg = "";
        public String label = "";
        public String context = "";
        public String mode = DRAFT;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("pkg", pkg).put("label", label).put("context", context).put("mode", mode);
        }

        static Rule from(JSONObject o) {
            Rule r = new Rule();
            r.pkg = o.optString("pkg");
            r.label = o.optString("label");
            r.context = o.optString("context");
            r.mode = o.optString("mode", DRAFT);
            return r;
        }
    }

    /** Statuses of a log entry. */
    public static final String S_SENT = "sent", S_APPROVED = "approved", S_DRAFT = "draft", S_SKIPPED = "skipped",
            S_IGNORED = "ignored", S_BLOCKED = "blocked", S_BAD = "bad_format", S_LONG = "too_long", S_ERROR = "error",
            S_NO_PROVIDER = "no_provider", S_DISCARDED = "discarded";

    public static final class Entry {
        public long id;
        public long time;
        public String pkg = "", app = "", sender = "", chat = "", message = "", reply = "", status = "", detail = "";

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("time", time).put("pkg", pkg).put("app", app).put("sender", sender)
                    .put("chat", chat).put("message", message).put("reply", reply).put("status", status).put("detail", detail);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.id = o.optLong("id");
            e.time = o.optLong("time");
            e.pkg = o.optString("pkg");
            e.app = o.optString("app");
            e.sender = o.optString("sender");
            e.chat = o.optString("chat");
            e.message = o.optString("message");
            e.reply = o.optString("reply");
            e.status = o.optString("status");
            e.detail = o.optString("detail");
            return e;
        }
    }

    public interface Listener {
        void onAutoReplyChanged();
    }

    private static final int MAX_LOGS = 150, MAX_HISTORY = 16, MAX_CHATS = 120;
    private static volatile AutoReplyStore instance;

    public static AutoReplyStore get(Context ctx) {
        if (instance == null) {
            synchronized (AutoReplyStore.class) {
                if (instance == null) instance = new AutoReplyStore(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private AutoReplyStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("auto_reply", Context.MODE_PRIVATE);
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : listeners) {
            try {
                l.onAutoReplyChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ rules

    public synchronized List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("rules", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Rule.from(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public Rule rule(String pkg) {
        for (Rule r : rules()) if (r.pkg.equals(pkg)) return r;
        return null;
    }

    public synchronized void saveRule(Rule rule) {
        List<Rule> all = rules();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).pkg.equals(rule.pkg)) {
                all.set(i, rule);
                found = true;
            }
        }
        if (!found) all.add(rule);
        writeRules(all);
    }

    public synchronized void removeRule(String pkg) {
        List<Rule> all = rules();
        for (Iterator<Rule> it = all.iterator(); it.hasNext(); ) if (it.next().pkg.equals(pkg)) it.remove();
        writeRules(all);
    }

    private void writeRules(List<Rule> all) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : all) a.put(r.toJson());
        } catch (Exception ignored) {
        }
        prefs.edit().putString("rules", a.toString()).apply();
        changed();
    }

    // ------------------------------------------------------------------ log + totals

    public synchronized List<Entry> logs() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("logs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Entry.from(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out; // newest first
    }

    public synchronized Entry log(Entry e) {
        if (e.id == 0) e.id = System.currentTimeMillis() * 10 + (long) (Math.random() * 10);
        if (e.time == 0) e.time = System.currentTimeMillis();
        List<Entry> all = logs();
        all.add(0, e);
        while (all.size() > MAX_LOGS) all.remove(all.size() - 1);
        writeLogs(all);
        count(e.status);
        return e;
    }

    /** Changes the status (and reply) of an entry, e.g. a draft that was sent or discarded. */
    public synchronized void update(long id, String status, String reply) {
        List<Entry> all = logs();
        for (Entry e : all) {
            if (e.id == id) {
                e.status = status;
                if (reply != null) e.reply = reply;
                writeLogs(all);
                count(status);
                return;
            }
        }
    }

    public synchronized void clearLogs() {
        prefs.edit().putString("logs", "[]").apply();
        changed();
    }

    private void writeLogs(List<Entry> all) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : all) a.put(e.toJson());
        } catch (Exception ignored) {
        }
        prefs.edit().putString("logs", a.toString()).apply();
        changed();
    }

    private void count(String status) {
        String k;
        switch (status) {
            case S_SENT:
            case S_APPROVED:
                k = "n_sent";
                break;
            case S_DRAFT:
                k = "n_draft";
                break;
            case S_DISCARDED:
                return;
            default:
                k = "n_skipped";
                break;
        }
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply();
    }

    public int total(String which) {
        return prefs.getInt("n_" + which, 0);
    }

    public void resetTotals() {
        prefs.edit().putInt("n_sent", 0).putInt("n_draft", 0).putInt("n_skipped", 0).apply();
        changed();
    }

    // ------------------------------------------------------------------ chat history

    public static String chatKey(String pkg, String chat) {
        return pkg + "|" + (chat == null ? "" : chat.trim());
    }

    public synchronized List<String> history(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(prefs.getString("history", "{}")).optJSONArray(key);
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Appends lines to a chat's history, keeping the last few; old chats are dropped first. */
    public synchronized void remember(String key, String... lines) {
        try {
            JSONObject all = new JSONObject(prefs.getString("history", "{}"));
            JSONArray a = all.optJSONArray(key);
            if (a == null) a = new JSONArray();
            for (String l : lines) if (l != null && !l.trim().isEmpty()) a.put(l.trim());
            JSONArray kept = new JSONArray();
            for (int i = Math.max(0, a.length() - MAX_HISTORY); i < a.length(); i++) kept.put(a.get(i));
            all.remove(key);
            all.put(key, kept); // re-insert: most recent chats last
            while (all.length() > MAX_CHATS) {
                Iterator<String> it = all.keys();
                if (!it.hasNext()) break;
                all.remove(it.next());
            }
            prefs.edit().putString("history", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized int chats() {
        try {
            return new JSONObject(prefs.getString("history", "{}")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized void clearHistory() {
        prefs.edit().putString("history", "{}").apply();
        changed();
    }

    // ------------------------------------------------------------------ notification access

    public static boolean hasAccess(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(ctx.getPackageName() + "/");
    }
}
