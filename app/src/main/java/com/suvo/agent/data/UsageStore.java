package com.suvo.agent.data;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Your Friend — Original project by Suvo.
 *
 * Usage of every LLM provider, counted by the app itself (see TokenCount): requests, successes,
 * failures by kind (same kind of error counted together), input / output tokens, speed (tokens per
 * second while generating) and the time to the first token. The provider's own usage numbers are
 * kept beside them only for comparison. Stored as one small JSON file; saved a moment after a
 * change, off the main thread.
 */
public final class UsageStore {

    /** Usage of one provider. */
    public static final class Stats {
        public long providerId;
        public String name = "";
        public String model = "";
        public long requests, ok, failed, cancelled;
        public long inTokens, outTokens;
        /** What the provider itself reported (-1 = it never reported). */
        public long providerIn = -1, providerOut = -1;
        /** Generation time and tokens of streamed answers (for tokens per second). */
        public long genMs, genTokens;
        /** Time to first token, summed over requests that streamed. */
        public long firstTokenMs, firstTokenCount;
        public long totalMs;
        public long lastUsedAt, lastErrorAt;
        public String lastError = "";
        /** Error kind (e.g. "RATE_LIMIT 429") → count, sample message. */
        public final Map<String, long[]> errors = new LinkedHashMap<>();
        public final Map<String, String> errorSamples = new LinkedHashMap<>();

        public double tokensPerSecond() {
            return genMs <= 0 ? 0 : genTokens * 1000.0 / genMs;
        }

        public long avgFirstTokenMs() {
            return firstTokenCount == 0 ? 0 : firstTokenMs / firstTokenCount;
        }

        public long avgRequestMs() {
            long n = ok + failed;
            return n == 0 ? 0 : totalMs / n;
        }

        public int successPercent() {
            long n = ok + failed;
            return n == 0 ? 100 : (int) Math.round(ok * 100.0 / n);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", providerId).put("name", name).put("model", model)
                    .put("requests", requests).put("ok", ok).put("failed", failed).put("cancelled", cancelled)
                    .put("in", inTokens).put("out", outTokens).put("pin", providerIn).put("pout", providerOut)
                    .put("gen_ms", genMs).put("gen_tok", genTokens).put("ftt_ms", firstTokenMs).put("ftt_n", firstTokenCount)
                    .put("total_ms", totalMs).put("last", lastUsedAt).put("last_err_at", lastErrorAt).put("last_err", lastError);
            JSONObject e = new JSONObject();
            for (Map.Entry<String, long[]> x : errors.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("n", x.getValue()[0]).put("at", x.getValue()[1]).put("msg", errorSamples.getOrDefault(x.getKey(), ""));
                e.put(x.getKey(), v);
            }
            o.put("errors", e);
            return o;
        }

        static Stats fromJson(JSONObject o) {
            Stats s = new Stats();
            s.providerId = o.optLong("id");
            s.name = o.optString("name");
            s.model = o.optString("model");
            s.requests = o.optLong("requests");
            s.ok = o.optLong("ok");
            s.failed = o.optLong("failed");
            s.cancelled = o.optLong("cancelled");
            s.inTokens = o.optLong("in");
            s.outTokens = o.optLong("out");
            s.providerIn = o.optLong("pin", -1);
            s.providerOut = o.optLong("pout", -1);
            s.genMs = o.optLong("gen_ms");
            s.genTokens = o.optLong("gen_tok");
            s.firstTokenMs = o.optLong("ftt_ms");
            s.firstTokenCount = o.optLong("ftt_n");
            s.totalMs = o.optLong("total_ms");
            s.lastUsedAt = o.optLong("last");
            s.lastErrorAt = o.optLong("last_err_at");
            s.lastError = o.optString("last_err");
            JSONObject e = o.optJSONObject("errors");
            if (e != null) {
                for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = e.optJSONObject(k);
                    if (v == null) continue;
                    s.errors.put(k, new long[]{v.optLong("n"), v.optLong("at")});
                    s.errorSamples.put(k, v.optString("msg"));
                }
            }
            return s;
        }
    }

    private static UsageStore instance;

    public static synchronized UsageStore get(Context ctx) {
        if (instance == null) instance = new UsageStore(ctx.getApplicationContext());
        return instance;
    }

    private final File file;
    private final Map<Long, Stats> stats = new LinkedHashMap<>();
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yf-usage");
        t.setDaemon(true);
        return t;
    });
    private boolean saveQueued;

    private UsageStore(Context ctx) {
        file = new File(ctx.getFilesDir(), "provider_usage.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(s);
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                JSONObject o = root.optJSONObject(it.next());
                if (o == null) continue;
                Stats st = Stats.fromJson(o);
                stats.put(st.providerId, st);
            }
        } catch (Exception ignored) {
            // A broken file starts fresh.
        }
    }

    private Stats statsFor(long id, String name, String model) {
        Stats s = stats.get(id);
        if (s == null) {
            s = new Stats();
            s.providerId = id;
            stats.put(id, s);
        }
        if (name != null && !name.isEmpty()) s.name = name;
        if (model != null && !model.isEmpty()) s.model = model;
        return s;
    }

    /** A finished request (times from one monotonic clock). {@code firstTokenAt} 0 = nothing was streamed. */
    public synchronized void success(long id, String name, String model, int inTokens, int outTokens, long startedAt,
                                     long firstTokenAt, long endedAt, int providerIn, int providerOut) {
        Stats s = statsFor(id, name, model);
        s.requests++;
        s.ok++;
        s.inTokens += Math.max(0, inTokens);
        s.outTokens += Math.max(0, outTokens);
        if (providerIn >= 0) s.providerIn = Math.max(0, s.providerIn) + providerIn;
        if (providerOut >= 0) s.providerOut = Math.max(0, s.providerOut) + providerOut;
        s.totalMs += Math.max(0, endedAt - startedAt);
        if (firstTokenAt > 0 && endedAt > firstTokenAt) {
            s.firstTokenMs += firstTokenAt - startedAt;
            s.firstTokenCount++;
            s.genMs += endedAt - firstTokenAt;
            s.genTokens += Math.max(0, outTokens);
        } else if (outTokens > 0 && endedAt > startedAt) {
            // Not streamed: the whole request is generation time.
            s.genMs += endedAt - startedAt;
            s.genTokens += outTokens;
        }
        s.lastUsedAt = System.currentTimeMillis();
        queueSave();
    }

    /** A failed request; {@code kind} groups errors of the same type ("RATE_LIMIT 429"). */
    public synchronized void failure(long id, String name, String model, int inTokens, String kind, String message,
                                     long startedAt, long endedAt) {
        Stats s = statsFor(id, name, model);
        s.requests++;
        s.failed++;
        s.inTokens += Math.max(0, inTokens);
        s.totalMs += Math.max(0, endedAt - startedAt);
        long[] e = s.errors.get(kind);
        if (e == null) {
            e = new long[2];
            s.errors.put(kind, e);
        }
        long now = System.currentTimeMillis();
        e[0]++;
        e[1] = now;
        String msg = message == null ? "" : message.trim();
        if (msg.length() > 240) msg = msg.substring(0, 240) + "…";
        s.errorSamples.put(kind, msg);
        s.lastError = kind + (msg.isEmpty() ? "" : ": " + msg);
        s.lastErrorAt = now;
        s.lastUsedAt = now;
        queueSave();
    }

    public synchronized void cancelled(long id, String name, String model) {
        Stats s = statsFor(id, name, model);
        s.requests++;
        s.cancelled++;
        s.lastUsedAt = System.currentTimeMillis();
        queueSave();
    }

    /** A copy of one provider's usage, or null. */
    public synchronized Stats get(long id) {
        Stats s = stats.get(id);
        if (s == null) return null;
        try {
            return Stats.fromJson(s.toJson());
        } catch (JSONException e) {
            return null;
        }
    }

    public synchronized List<Stats> all() {
        List<Stats> out = new ArrayList<>();
        for (Stats s : stats.values()) {
            try {
                out.add(Stats.fromJson(s.toJson()));
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    public synchronized void reset(long id) {
        stats.remove(id);
        queueSave();
    }

    public synchronized void resetAll() {
        stats.clear();
        queueSave();
    }

    private void queueSave() {
        if (saveQueued) return;
        saveQueued = true;
        saver.schedule(this::save, 2, TimeUnit.SECONDS);
    }

    private void save() {
        String json;
        synchronized (this) {
            saveQueued = false;
            JSONObject root = new JSONObject();
            try {
                for (Stats s : stats.values()) root.put(String.valueOf(s.providerId), s.toJson());
            } catch (JSONException e) {
                return;
            }
            json = root.toString();
        }
        try {
            File tmp = new File(file.getPath() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) java.nio.file.Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
