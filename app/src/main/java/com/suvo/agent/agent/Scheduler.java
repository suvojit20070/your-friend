package com.suvo.agent.agent;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Self-activation: tasks that start the agent later on their own — reminders and follow-ups the
 * agent schedules with schedule_task (after the user approves), and the optional periodic
 * self check-in from Settings. Uses AlarmManager (exact when the user allowed exact alarms, which
 * also lets the task start its foreground service from the background; otherwise inexact).
 * Alarms are re-armed after a reboot and whenever the app starts.
 */
public final class Scheduler {

    public static final class Job {
        public long id;
        public String prompt = "";
        public long at;
        public String repeat = "none";   // none | hourly | daily | weekly | every
        public int everyMinutes;          // for "every"
        public String kind = "task";      // task | checkin
        public String createdBy = "agent";
        public long lastRun;
        public String lastStatus = "";

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("prompt", prompt).put("at", at).put("repeat", repeat)
                    .put("every", everyMinutes).put("kind", kind).put("by", createdBy).put("last_run", lastRun)
                    .put("last_status", lastStatus);
        }

        static Job fromJson(JSONObject o) {
            Job j = new Job();
            j.id = o.optLong("id");
            j.prompt = o.optString("prompt");
            j.at = o.optLong("at");
            j.repeat = o.optString("repeat", "none");
            j.everyMinutes = o.optInt("every", 0);
            j.kind = o.optString("kind", "task");
            j.createdBy = o.optString("by", "agent");
            j.lastRun = o.optLong("last_run");
            j.lastStatus = o.optString("last_status");
            return j;
        }

        public JSONObject describe() {
            try {
                return toJson();
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
    }

    public static final String EXTRA_ID = "job_id";
    public static final String ACTION_FIRE = "com.suvo.agent.SCHEDULE_FIRE";
    public static final String CHECKIN_PROMPT =
            "[Self check-in, started by the app on schedule — the user did not just write this.] Look at your memories, lessons, "
                    + "scheduled tasks and anything you promised to follow up on. If something genuinely useful should happen now "
                    + "(a reminder that is due, a follow-up, a quick check you were asked to do), do it and keep the user informed "
                    + "with a notification. If nothing is needed, reply with exactly: NOTHING";
    public static final String NOTHING = "NOTHING";
    public static final int MAX_JOBS = 40;

    private final Context ctx;
    private final SharedPreferences prefs;

    public Scheduler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = this.ctx.getSharedPreferences("schedules", Context.MODE_PRIVATE);
    }

    public synchronized List<Job> list() {
        List<Job> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("jobs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Job.fromJson(o));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public Job get(long id) {
        for (Job j : list()) if (j.id == id) return j;
        return null;
    }

    private void write(List<Job> jobs) {
        JSONArray arr = new JSONArray();
        try {
            for (Job j : jobs) arr.put(j.toJson());
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("jobs", arr.toString()).apply();
    }

    /** Adds a job and arms its alarm. Returns null when the limit is reached. */
    public synchronized Job add(Job j) {
        List<Job> all = list();
        if (all.size() >= MAX_JOBS) return null;
        long max = 0;
        for (Job x : all) max = Math.max(max, x.id);
        j.id = max + 1;
        all.add(j);
        write(all);
        arm(j);
        return j;
    }

    public synchronized boolean cancel(long id) {
        List<Job> all = list();
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id == id) {
                all.remove(i);
                removed = true;
            }
        }
        write(all);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(id));
        return removed;
    }

    private synchronized void replace(Job j) {
        List<Job> all = list();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id == j.id) all.set(i, j);
        write(all);
    }

    public boolean exactAllowed() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    private PendingIntent pending(long id) {
        Intent i = new Intent(ctx, ScheduleReceiver.class).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(ctx, (int) (1000 + id), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void arm(Job j) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        long when = Math.max(j.at, System.currentTimeMillis() + 5_000);
        PendingIntent pi = pending(j.id);
        try {
            if (exactAllowed()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    public void armAll() {
        for (Job j : list()) arm(j);
    }

    /** Next run after {@code from}, or 0 when the job does not repeat. */
    static long next(Job j, long from) {
        long step;
        switch (j.repeat) {
            case "hourly":
                step = 3_600_000L;
                break;
            case "daily":
                step = 86_400_000L;
                break;
            case "weekly":
                step = 7 * 86_400_000L;
                break;
            case "every":
                step = Math.max(15, j.everyMinutes) * 60_000L;
                break;
            default:
                return 0;
        }
        long t = j.at;
        while (t <= from) t += step;
        return t;
    }

    private static boolean quietHours(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 8;
    }

    /** Called from the alarm. Starts the agent, or retries shortly if it is busy. */
    public void fire(long id) {
        Job j = get(id);
        if (j == null) return;
        com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
        long now = System.currentTimeMillis();
        // Self check-ins stay quiet at night; real reminders always run.
        boolean skip = "checkin".equals(j.kind) && quietHours(now);
        if (!skip && g.agent.isRunning()) {
            Job retry = Job.fromJson(j.describe());
            retry.at = now + 3 * 60_000L;
            AlarmManager am = ctx.getSystemService(AlarmManager.class);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retry.at, pending(j.id));
            return;
        }
        String why = null;
        if (!skip) {
            String text = "checkin".equals(j.kind) ? CHECKIN_PROMPT
                    : "[Scheduled task you set earlier, started by the app now — the user did not just write this.] " + j.prompt;
            why = g.agent.submitScheduled(text, "checkin".equals(j.kind));
        }
        j.lastRun = now;
        j.lastStatus = skip ? "skipped (quiet hours)" : why == null ? "started" : why;
        long nxt = next(j, now);
        if (nxt == 0) {
            cancel(j.id);
        } else {
            j.at = nxt;
            replace(j);
            arm(j);
        }
    }

    // ---------------------------------------------------------------- self check-in from Settings

    public Job checkin() {
        for (Job j : list()) if ("checkin".equals(j.kind)) return j;
        return null;
    }

    /** hours = 0 turns the self check-in off. */
    public void setCheckin(int hours) {
        Job existing = checkin();
        if (existing != null) cancel(existing.id);
        if (hours <= 0) return;
        Job j = new Job();
        j.kind = "checkin";
        j.createdBy = "user";
        j.prompt = "Self check-in";
        j.repeat = "every";
        j.everyMinutes = hours * 60;
        j.at = System.currentTimeMillis() + hours * 3_600_000L;
        add(j);
    }
}
