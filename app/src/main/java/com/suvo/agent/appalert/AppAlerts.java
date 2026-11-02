package com.suvo.agent.appalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.AppAlertActivity;
import com.suvo.agent.ui.Notifications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * New app install alert. Apps can appear without the user doing anything (Play services, the
 * system or an OEM store installing silently). Every new install:
 * <ul>
 *   <li>shows a notification with the basics and Uninstall / Details buttons;</li>
 *   <li>is always written to a log (Settings → New app install alert), with whether the
 *       notification could be shown — so an install is never missed even when notifications are
 *       blocked or the app was not running.</li>
 * </ul>
 * Detection: a live receiver while the app's process runs, plus a catch-up scan of the packages
 * changed since the last check (at app start, every ~15 minutes in the background, after reboot).
 */
public final class AppAlerts {

    public static final String ENABLED = "appalert_enabled";
    public static final String EXTRA_PKG = "appalert_pkg";

    private static final int JOB_ID = 7701;
    private static final int MAX_LOG = 200;

    public interface Listener {
        void onAppAlertsChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static BroadcastReceiver liveReceiver;

    private AppAlerts() {
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void changed() {
        for (Listener l : LISTENERS) {
            try {
                l.onAppAlertsChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static boolean enabled(Context ctx) {
        return Settings.get(ctx).bool(ENABLED, true);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("app_alerts", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ start / stop

    /** At app start: live receiver, background job, catch-up scan (off the main thread). */
    public static void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!enabled(app)) {
            stop(app);
            return;
        }
        if (liveReceiver == null) {
            liveReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null || i.getData() == null) return;
                    String pkg = i.getData().getSchemeSpecificPart();
                    String action = i.getAction();
                    PendingResult pr = goAsync();
                    new Thread(() -> {
                        try {
                            if (Intent.ACTION_PACKAGE_ADDED.equals(action) && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                onInstalled(c, pkg, false);
                            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
                                markRemoved(c, pkg);
                            }
                        } finally {
                            pr.finish();
                        }
                    }, "yf-appalert").start();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            f.addDataScheme("package");
            try {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(liveReceiver, f, Context.RECEIVER_EXPORTED);
                else app.registerReceiver(liveReceiver, f);
            } catch (RuntimeException e) {
                liveReceiver = null;
            }
        }
        schedule(app);
        new Thread(() -> scan(app), "yf-appalert-scan").start();
    }

    public static void stop(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (liveReceiver != null) {
            try {
                app.unregisterReceiver(liveReceiver);
            } catch (RuntimeException ignored) {
            }
            liveReceiver = null;
        }
        JobScheduler js = app.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    private static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AppAlertJob.class))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ detection

    /**
     * Catch-up: every package changed since the last check whose first install is newer than the
     * last check is a new install (handles installs while the app was not running).
     */
    public static synchronized void scan(Context ctx) {
        if (!enabled(ctx)) return;
        SharedPreferences p = prefs(ctx);
        PackageManager pm = ctx.getPackageManager();
        long now = System.currentTimeMillis();
        long lastScan = p.getLong("last_scan", 0);
        int boot = bootCount(ctx);
        int seq = p.getInt("seq", -1);
        if (p.getInt("boot", -1) != boot) seq = 0; // the sequence restarts after a reboot
        if (lastScan == 0) {
            // First run: remember where we are, alert from now on.
            ChangedPackages cp = pm.getChangedPackages(0);
            p.edit().putLong("last_scan", now).putInt("seq", cp == null ? 0 : cp.getSequenceNumber()).putInt("boot", boot).apply();
            return;
        }
        List<String> names = new ArrayList<>();
        ChangedPackages cp = pm.getChangedPackages(Math.max(0, seq));
        int newSeq = seq;
        if (cp != null) {
            names.addAll(cp.getPackageNames());
            newSeq = cp.getSequenceNumber();
        }
        for (String name : names) {
            try {
                PackageInfo pi = pm.getPackageInfo(name, 0);
                // A little slack for clock rounding; updates keep their old first-install time.
                if (pi.firstInstallTime >= lastScan - 5_000) onInstalled(ctx, name, true);
            } catch (PackageManager.NameNotFoundException ignored) {
                markRemoved(ctx, name);
            }
        }
        p.edit().putLong("last_scan", now).putInt("seq", newSeq).putInt("boot", boot).apply();
    }

    private static int bootCount(Context ctx) {
        try {
            return android.provider.Settings.Global.getInt(ctx.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT);
        } catch (Exception e) {
            return 0;
        }
    }

    /** A new app: log it (always) and notify. {@code late} = found by the catch-up scan. */
    static void onInstalled(Context ctx, String pkg, boolean late) {
        if (!enabled(ctx) || pkg == null || pkg.equals(ctx.getPackageName())) return;
        AppInfoReader.Info info = AppInfoReader.read(ctx, pkg);
        if (!info.installed) return;
        String key = pkg + "@" + info.firstInstall;
        synchronized (AppAlerts.class) {
            for (Entry e : log(ctx)) if (e.key.equals(key)) return; // already handled
        }
        Entry e = new Entry();
        e.key = key;
        e.pkg = pkg;
        e.label = info.label;
        e.version = info.version;
        e.installer = info.installer;
        e.installerLabel = info.installerLabel;
        e.system = info.system;
        e.time = info.firstInstall > 0 ? info.firstInstall : System.currentTimeMillis();
        e.found = System.currentTimeMillis();
        e.late = late;
        e.risky = info.risky.size() + info.dangerousGranted.size();
        e.notified = notify(ctx, info, late);
        add(ctx, e);
    }

    private static boolean notify(Context ctx, AppInfoReader.Info info, boolean late) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        String channel = Notifications.channel(ctx, Notifications.CHANNEL_APP_ALERT);
        NotificationChannel ch = nm.getNotificationChannel(channel);
        if (ch == null) {
            Notifications.createChannels(ctx);
            ch = nm.getNotificationChannel(channel);
        }
        if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        int id = 7000 + (info.pkg.hashCode() & 0x3ff);
        Intent details = new Intent(ctx, AppAlertActivity.class).putExtra(EXTRA_PKG, info.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pDetails = PendingIntent.getActivity(ctx, id, details, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pUninstall = PendingIntent.getActivity(ctx, id + 1000, AppInfoReader.uninstallIntent(info.pkg),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        StringBuilder big = new StringBuilder();
        big.append(info.pkg);
        if (!info.version.isEmpty()) big.append("\nVersion ").append(info.version);
        big.append("\n").append(ctx.getString(R.string.appalert_by, info.installerLabel.isEmpty()
                ? ctx.getString(R.string.appalert_unknown_installer) : info.installerLabel));
        if (info.system) big.append("\n").append(ctx.getString(R.string.appalert_system));
        if (!info.hasLauncher) big.append("\n").append(ctx.getString(R.string.appalert_hidden));
        if (!info.risky.isEmpty()) big.append("\n⚠ ").append(info.risky.get(0));
        if (!info.dangerousGranted.isEmpty()) big.append("\n").append(ctx.getString(R.string.appalert_perms, String.join(", ", info.dangerousGranted)));
        if (late) big.append("\n").append(ctx.getString(R.string.appalert_found_late));
        Notification n = Notifications.builder(ctx, Notifications.CHANNEL_APP_ALERT)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(ctx.getString(R.string.appalert_notif_title, info.label))
                .setContentText(info.summary())
                .setStyle(new Notification.BigTextStyle().bigText(big))
                .setContentIntent(pDetails)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.appalert_uninstall), pUninstall).build())
                .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.appalert_details), pDetails).build())
                .build();
        try {
            nm.notify(id, n);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ log

    public static final class Entry {
        public String key = "", pkg = "", label = "", version = "", installer = "", installerLabel = "";
        public long time, found;
        public boolean system, late, notified, removed, seen;
        public int risky;

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("label", label).put("version", version)
                    .put("installer", installer).put("installerLabel", installerLabel).put("time", time).put("found", found)
                    .put("system", system).put("late", late).put("notified", notified).put("removed", removed)
                    .put("seen", seen).put("risky", risky);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key");
            e.pkg = o.optString("pkg");
            e.label = o.optString("label");
            e.version = o.optString("version");
            e.installer = o.optString("installer");
            e.installerLabel = o.optString("installerLabel");
            e.time = o.optLong("time");
            e.found = o.optLong("found");
            e.system = o.optBoolean("system");
            e.late = o.optBoolean("late");
            e.notified = o.optBoolean("notified");
            e.removed = o.optBoolean("removed");
            e.seen = o.optBoolean("seen");
            e.risky = o.optInt("risky");
            return e;
        }
    }

    /** Newest first. */
    public static synchronized List<Entry> log(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("log", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Entry.from(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static synchronized void write(Context ctx, List<Entry> all) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : all) a.put(e.json());
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString("log", a.toString()).apply();
        changed();
    }

    private static synchronized void add(Context ctx, Entry e) {
        List<Entry> all = log(ctx);
        all.add(0, e);
        while (all.size() > MAX_LOG) all.remove(all.size() - 1);
        write(ctx, all);
    }

    static synchronized void markRemoved(Context ctx, String pkg) {
        List<Entry> all = log(ctx);
        boolean any = false;
        for (Entry e : all) {
            if (e.pkg.equals(pkg) && !e.removed) {
                e.removed = true;
                any = true;
            }
        }
        if (any) write(ctx, all);
    }

    public static synchronized void markSeen(Context ctx) {
        List<Entry> all = log(ctx);
        boolean any = false;
        for (Entry e : all) {
            if (!e.seen) {
                e.seen = true;
                any = true;
            }
        }
        if (any) write(ctx, all);
    }

    public static int unseenMissed(Context ctx) {
        int n = 0;
        for (Entry e : log(ctx)) if (!e.seen && !e.notified) n++;
        return n;
    }

    public static synchronized void clear(Context ctx) {
        write(ctx, new ArrayList<>());
    }
}
