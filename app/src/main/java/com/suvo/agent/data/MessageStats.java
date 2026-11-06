package com.suvo.agent.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Your Friend — Original project by Suvo.
 *
 * Tokens of each answer, shown under it in the chat ("230.4 T/S · 5k IN · 1k OUT"): the input
 * and output tokens of the request that produced that message (the provider's own numbers when it
 * reports them, otherwise the app's count), its speed, and which provider answered. Kept by
 * message id in one small JSON file (the newest few thousand answers).
 */
public final class MessageStats {

    /** One answer's request. */
    public static final class Entry {
        public long providerId = -1;
        public long in, out;
        /** Output tokens per second while generating (0 = unknown). */
        public double tps;
        /** True when in / out are the provider's own numbers. */
        public boolean fromProvider;

        JSONObject toJson() throws org.json.JSONException {
            return new JSONObject().put("p", providerId).put("i", in).put("o", out).put("t", tps).put("r", fromProvider);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.providerId = o.optLong("p", -1);
            e.in = o.optLong("i");
            e.out = o.optLong("o");
            e.tps = o.optDouble("t", 0);
            e.fromProvider = o.optBoolean("r");
            return e;
        }
    }

    private static final int MAX = 4000;
    private static MessageStats instance;

    public static synchronized MessageStats get(Context ctx) {
        if (instance == null) instance = new MessageStats(ctx.getApplicationContext());
        return instance;
    }

    private final File file;
    private final LinkedHashMap<Long, Entry> map = new LinkedHashMap<Long, Entry>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, MessageStats.Entry> eldest) {
            return size() > MAX;
        }
    };
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yf-msgstats");
        t.setDaemon(true);
        return t;
    });
    private boolean saveQueued;

    private MessageStats(Context ctx) {
        file = new File(ctx.getFilesDir(), "message_stats.json");
        try {
            if (file.exists()) {
                JSONObject root = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject o = root.optJSONObject(k);
                    if (o != null) map.put(Long.parseLong(k), Entry.fromJson(o));
                }
            }
        } catch (Exception ignored) {
            // A broken file starts fresh.
        }
    }

    public synchronized void put(long messageId, Entry e) {
        if (messageId <= 0 || e == null) return;
        map.put(messageId, e);
        if (saveQueued) return;
        saveQueued = true;
        saver.schedule(this::save, 1500, TimeUnit.MILLISECONDS);
    }

    public synchronized Entry find(long messageId) {
        return map.get(messageId);
    }

    private void save() {
        String json;
        synchronized (this) {
            saveQueued = false;
            try {
                JSONObject root = new JSONObject();
                for (java.util.Map.Entry<Long, MessageStats.Entry> x : map.entrySet()) root.put(String.valueOf(x.getKey()), x.getValue().toJson());
                json = root.toString();
            } catch (org.json.JSONException e) {
                return;
            }
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) java.nio.file.Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Next change saves again.
        }
    }
}
