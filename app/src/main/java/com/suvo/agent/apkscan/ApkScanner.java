package com.suvo.agent.apkscan;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import com.suvo.agent.AppGraph;
import com.suvo.agent.agent.RoleModels;
import com.suvo.agent.appalert.AppInfoReader;
import com.suvo.agent.core.apkscan.ScanLogic;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Your Friend — Original project by Suvo.
 *
 * Scans an app for the person who installed it:
 * <ol>
 *   <li>Well-known apps (Facebook, Instagram, Telegram, ChatGPT, Claude…) that came from the Play
 *       Store get a quick check only: where they came from, their certificate and permissions.</li>
 *   <li>Every other app: the manifest (permissions, the services that get high-level powers —
 *       accessibility, notifications, device admin, SMS, boot), the files inside the APK (code in
 *       assets, native libraries), and the decompiled Java of only the suspicious classes — never
 *       the whole app, which would cost far too many tokens.</li>
 *   <li>The AI reads that and answers with a risk level, a short summary, the reasons, and how
 *       each high-level permission is actually used.</li>
 * </ol>
 * The last result per app is kept.
 */
public final class ApkScanner {

    /** Setting: scan new apps by themselves after they are installed (App install alert). */
    public static final String AUTO = "appalert_ai_scan";
    private static final int MAX_CLASSES = 10;
    private static final int MAX_CODE_PER_CLASS = 6000;
    private static final int MAX_CODE_TOTAL = 42_000;

    public interface Progress {
        void step(String text);

        Progress NONE = t -> {
        };
    }

    private ApkScanner() {
    }

    // ------------------------------------------------------------------------------ the result

    public static final class Report {
        public String pkg = "", label = "", version = "", mode = "full", risk = "unknown", verdict = "", summary = "", error = "";
        public long versionCode, time;
        public final List<String> reasons = new ArrayList<>(), classesRead = new ArrayList<>(), addresses = new ArrayList<>(),
                hints = new ArrayList<>(), facts = new ArrayList<>();
        public final List<String[]> permissions = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", pkg).put("label", label).put("version", version).put("versionCode", versionCode).put("time", time)
                        .put("mode", mode).put("risk", risk).put("verdict", verdict).put("summary", summary).put("error", error)
                        .put("reasons", new JSONArray(reasons)).put("classes", new JSONArray(classesRead))
                        .put("addresses", new JSONArray(addresses)).put("hints", new JSONArray(hints)).put("facts", new JSONArray(facts));
                JSONArray perms = new JSONArray();
                for (String[] p : permissions) perms.put(new JSONObject().put("permission", p[0]).put("how", p[1]));
                o.put("permissions", perms);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Report fromJson(JSONObject o) {
            Report r = new Report();
            r.pkg = o.optString("pkg");
            r.label = o.optString("label");
            r.version = o.optString("version");
            r.versionCode = o.optLong("versionCode");
            r.time = o.optLong("time");
            r.mode = o.optString("mode", "full");
            r.risk = o.optString("risk", "unknown");
            r.verdict = o.optString("verdict");
            r.summary = o.optString("summary");
            r.error = o.optString("error");
            fill(o.optJSONArray("reasons"), r.reasons);
            fill(o.optJSONArray("classes"), r.classesRead);
            fill(o.optJSONArray("addresses"), r.addresses);
            fill(o.optJSONArray("hints"), r.hints);
            fill(o.optJSONArray("facts"), r.facts);
            JSONArray p = o.optJSONArray("permissions");
            for (int i = 0; p != null && i < p.length(); i++) {
                JSONObject x = p.optJSONObject(i);
                if (x != null) r.permissions.add(new String[]{x.optString("permission"), x.optString("how")});
            }
            return r;
        }

        private static void fill(JSONArray a, List<String> into) {
            for (int i = 0; a != null && i < a.length(); i++) into.add(a.optString(i));
        }

        /** For the agent and for copying: everything in plain text. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(label).append(" (").append(pkg).append(") ").append(version).append('\n');
            sb.append("Risk: ").append(risk.toUpperCase(Locale.ROOT)).append(verdict.isEmpty() ? "" : " — " + verdict)
                    .append("quick".equals(mode) ? " (quick check)" : "").append('\n');
            if (!summary.isEmpty()) sb.append(summary).append('\n');
            if (!error.isEmpty()) sb.append("Problem: ").append(error).append('\n');
            if (!reasons.isEmpty()) {
                sb.append("\nReasons:\n");
                for (String s : reasons) sb.append("• ").append(s).append('\n');
            }
            if (!permissions.isEmpty()) {
                sb.append("\nHow the high-level permissions are used:\n");
                for (String[] p : permissions) sb.append("• ").append(p[0]).append(": ").append(p[1]).append('\n');
            }
            if (!facts.isEmpty()) {
                sb.append("\nFound:\n");
                for (String s : facts) sb.append("• ").append(s).append('\n');
            }
            if (!addresses.isEmpty()) sb.append("\nServers in the code read: ").append(String.join(", ", addresses)).append('\n');
            if (!classesRead.isEmpty()) sb.append("\nClasses read: ").append(String.join(", ", classesRead)).append('\n');
            return sb.toString().trim();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("apk_scans", Context.MODE_PRIVATE);
    }

    /** The last scan of {@code pkg}, or null. */
    public static Report last(Context ctx, String pkg) {
        String s = prefs(ctx).getString(pkg, null);
        if (s == null) return null;
        try {
            return Report.fromJson(new JSONObject(s));
        } catch (JSONException e) {
            return null;
        }
    }

    private static void save(Context ctx, Report r) {
        if (r.pkg.isEmpty()) return;
        prefs(ctx).edit().putString(r.pkg, r.toJson().toString()).apply();
    }

    // ------------------------------------------------------------------------------ the scan

    /** One APK (installed or a file) with what Android itself says about it. */
    static final class Target {
        String pkg = "", label = "", version = "", installer = "";
        long versionCode;
        boolean installed, system;
        File apk;
        final List<File> splits = new ArrayList<>();
        PackageInfo pi;
    }

    private static final int FLAGS = PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
            | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA;

    /** An installed package name, or a path to an .apk file. */
    @SuppressWarnings("deprecation")
    static Target target(Context ctx, String pkgOrPath) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Target t = new Target();
        File f = new File(pkgOrPath);
        if (pkgOrPath.endsWith(".apk") || f.isFile()) {
            if (!f.isFile()) throw new IllegalArgumentException("No such file: " + pkgOrPath);
            t.apk = f;
            t.pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), FLAGS);
            if (t.pi == null) throw new IllegalArgumentException("Not a readable APK: " + f.getName());
            if (t.pi.applicationInfo != null) {
                t.pi.applicationInfo.sourceDir = f.getAbsolutePath();
                t.pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
            }
        } else {
            try {
                t.pi = pm.getPackageInfo(pkgOrPath, FLAGS);
            } catch (Exception e) {
                t.pi = pm.getPackageInfo(pkgOrPath, PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
            }
            t.installed = true;
            ApplicationInfo ai = t.pi.applicationInfo;
            t.apk = new File(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) t.splits.add(new File(s));
            t.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            AppInfoReader.Info info = AppInfoReader.read(ctx, pkgOrPath);
            t.installer = info.installer;
        }
        t.pkg = t.pi.packageName;
        t.label = t.pi.applicationInfo == null ? t.pkg : String.valueOf(t.pi.applicationInfo.loadLabel(pm));
        t.version = t.pi.versionName == null ? "" : t.pi.versionName;
        t.versionCode = Build.VERSION.SDK_INT >= 28 ? t.pi.getLongVersionCode() : t.pi.versionCode;
        return t;
    }

    /** High-level powers in the manifest: "what" → the class that gets it. */
    static List<String[]> powers(Context ctx, Target t) {
        List<String[]> out = new ArrayList<>();
        PackageInfo pi = t.pi;
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) {
                String perm = s.permission == null ? "" : s.permission;
                String what = null;
                switch (perm) {
                    case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                        what = "Accessibility service (reads and controls the screen)";
                        break;
                    case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                        what = "Notification listener (reads every notification)";
                        break;
                    case "android.permission.BIND_VPN_SERVICE":
                        what = "VPN service (sees network traffic)";
                        break;
                    case "android.permission.BIND_INPUT_METHOD":
                        what = "Keyboard (sees what is typed)";
                        break;
                    case "android.permission.BIND_DEVICE_ADMIN":
                        what = "Device admin";
                        break;
                    default:
                        break;
                }
                if (what != null) out.add(new String[]{what, s.name});
            }
        }
        if (pi.receivers != null) {
            for (ActivityInfo r : pi.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(r.permission)) out.add(new String[]{"Device admin receiver", r.name});
                if ("android.permission.BROADCAST_SMS".equals(r.permission)) out.add(new String[]{"Receives SMS", r.name});
            }
        }
        if (t.installed) {
            // Receivers for SMS and for the phone starting (only an installed app can be asked).
            PackageManager pm = ctx.getPackageManager();
            String[][] actions = {{"android.provider.Telephony.SMS_RECEIVED", "Receives SMS"},
                    {Intent.ACTION_BOOT_COMPLETED, "Starts after every reboot"},
                    {"android.intent.action.NEW_OUTGOING_CALL", "Watches outgoing calls"},
                    {"android.intent.action.PHONE_STATE", "Watches calls"}};
            for (String[] a : actions) {
                try {
                    for (ResolveInfo ri : pm.queryBroadcastReceivers(new Intent(a[0]).setPackage(t.pkg), 0)) {
                        if (ri.activityInfo != null) out.add(new String[]{a[1], ri.activityInfo.name});
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (pi.applicationInfo != null && pi.applicationInfo.className != null) {
            out.add(new String[]{"Application class (runs first, at every start)", pi.applicationInfo.className});
        }
        return out;
    }

    private static final String[] HIGH_PERMS = {"SEND_SMS", "READ_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "PROCESS_OUTGOING_CALLS",
            "CALL_PHONE", "READ_CONTACTS", "RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "READ_PHONE_STATE",
            "READ_PHONE_NUMBERS", "PACKAGE_USAGE_STATS", "WRITE_SETTINGS", "BIND_ACCESSIBILITY_SERVICE", "BIND_DEVICE_ADMIN",
            "REQUEST_DELETE_PACKAGES", "USE_FULL_SCREEN_INTENT", "READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE", "GET_ACCOUNTS",
            "BODY_SENSORS", "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES", "SCHEDULE_EXACT_ALARM", "FOREGROUND_SERVICE_SPECIAL_USE"};

    static List<String> highPermissions(PackageInfo pi) {
        List<String> out = new ArrayList<>();
        if (pi.requestedPermissions == null) return out;
        for (String p : pi.requestedPermissions) {
            String s = p.startsWith("android.permission.") ? p.substring(19) : p;
            for (String h : HIGH_PERMS) {
                if (h.equals(s)) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    /** What the files inside the APK show: code hidden in assets, native libraries, dex count. */
    static List<String> files(Target t) {
        List<String> out = new ArrayList<>();
        int dex = 0;
        LinkedHashSet<String> natives = new LinkedHashSet<>();
        List<String> hidden = new ArrayList<>();
        List<File> all = new ArrayList<>();
        all.add(t.apk);
        all.addAll(t.splits);
        for (File f : all) {
            try (ZipFile z = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    String low = n.toLowerCase(Locale.ROOT);
                    if (low.matches("classes\\d*\\.dex")) dex++;
                    else if (low.startsWith("lib/") && low.endsWith(".so")) natives.add(n.substring(n.lastIndexOf('/') + 1));
                    else if ((low.startsWith("assets/") || low.startsWith("res/raw/"))
                            && (low.endsWith(".dex") || low.endsWith(".jar") || low.endsWith(".apk") || low.endsWith(".odex")
                            || ((low.endsWith(".bin") || low.endsWith(".dat") || !low.substring(low.lastIndexOf('/') + 1).contains("."))
                            && e.getSize() > 200_000))) {
                        hidden.add(n + " (" + (e.getSize() / 1024) + " KB)");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        out.add(dex + " dex file(s)" + (t.splits.isEmpty() ? "" : ", " + t.splits.size() + " split APK(s)"));
        if (!natives.isEmpty()) {
            List<String> n = new ArrayList<>(natives);
            out.add("Native libraries: " + String.join(", ", n.size() > 20 ? n.subList(0, 20) : n) + (n.size() > 20 ? " …" : ""));
        }
        if (!hidden.isEmpty()) {
            out.add("Code or blobs packed inside the app (can be loaded later): "
                    + String.join(", ", hidden.size() > 10 ? hidden.subList(0, 10) : hidden));
        }
        return out;
    }

    /** The APK file of a package name or path (for the decompiler). */
    public static File apkFile(Context ctx, String pkgOrPath) throws Exception {
        return target(ctx, pkgOrPath).apk;
    }

    /** The manifest and file facts only (no decompiling, no AI). */
    public static Report facts(Context ctx, String pkgOrPath) {
        Report r = new Report();
        r.time = System.currentTimeMillis();
        try {
            Target t = target(ctx, pkgOrPath);
            r.pkg = t.pkg;
            r.label = t.label;
            r.version = t.version;
            r.versionCode = t.versionCode;
            r.mode = "info";
            for (String[] pw : powers(ctx, t)) r.facts.add(pw[0] + ": " + pw[1]);
            List<String> high = highPermissions(t.pi);
            r.facts.add("High-level permissions: " + (high.isEmpty() ? "none" : String.join(", ", high)));
            if (t.installed) r.facts.add("Installed by: " + (t.installer.isEmpty() ? "unknown" : AppInfoReader.installerLabel(ctx, t.installer)));
            r.facts.addAll(files(t));
            String famous = ScanLogic.famous(t.pkg);
            if (famous != null) r.facts.add("Well-known app: " + famous);
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    private static final Object AUTO_LOCK = new Object();

    /** New app installed (App install alert): scan it in the background and tell the result. */
    public static void autoScan(Context ctx, String pkg) {
        AppGraph g = AppGraph.get();
        if (g == null || !g.settings.bool(AUTO, true)) return;
        Thread th = new Thread(() -> {
            Report r;
            // Several installs at once: scanned one after the other (each needs a lot of memory).
            synchronized (AUTO_LOCK) {
                try {
                    // A just-installed APK can still be settling for a moment.
                    Thread.sleep(3000);
                    r = scan(g, pkg, Progress.NONE, new CancellationToken());
                } catch (Throwable t) {
                    return;
                }
            }
            notifyResult(ctx.getApplicationContext(), r);
        }, "yf-apkscan");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /** The scan's result as a notification (tap: the app's details with the full report). */
    public static void notifyResult(Context ctx, Report r) {
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled() || r.pkg.isEmpty()) return;
        int id = 7600 + (r.pkg.hashCode() & 0x3ff);
        Intent details = new Intent(ctx, com.suvo.agent.ui.AppAlertActivity.class).putExtra(com.suvo.agent.appalert.AppAlerts.EXTRA_PKG, r.pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, id, details,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        String risk = r.risk.toUpperCase(Locale.ROOT);
        String title = ctx.getString(com.suvo.agent.R.string.apkscan_notif_title, r.label, risk);
        String body = !r.summary.isEmpty() ? r.summary : !r.error.isEmpty() ? r.error : String.join("\n", r.facts);
        android.app.Notification.Builder b = com.suvo.agent.ui.Notifications.builder(ctx, com.suvo.agent.ui.Notifications.CHANNEL_APP_ALERT)
                .setSmallIcon(com.suvo.agent.R.drawable.ic_stat_companion)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body + (r.reasons.isEmpty() ? "" : "\n• "
                        + String.join("\n• ", r.reasons.size() > 4 ? r.reasons.subList(0, 4) : r.reasons))))
                .setContentIntent(pi)
                .setAutoCancel(true);
        if ("high".equals(r.risk) || "critical".equals(r.risk)) {
            android.app.PendingIntent un = android.app.PendingIntent.getActivity(ctx, id + 1000, AppInfoReader.uninstallIntent(r.pkg),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new android.app.Notification.Action.Builder(null, ctx.getString(com.suvo.agent.R.string.appalert_uninstall), un).build());
        }
        try {
            nm.notify(id, b.build());
        } catch (RuntimeException ignored) {
        }
    }

    /** Scans {@code pkgOrPath}. Slow (decompiling, an AI call): never on the main thread. */
    public static Report scan(AppGraph g, String pkgOrPath, Progress p, CancellationToken cancel) {
        Context ctx = g.ctx;
        Report r = new Report();
        r.time = System.currentTimeMillis();
        Target t;
        try {
            p.step("Reading the app");
            t = target(ctx, pkgOrPath);
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            r.pkg = pkgOrPath;
            return r;
        }
        r.pkg = t.pkg;
        r.label = t.label;
        r.version = t.version;
        r.versionCode = t.versionCode;
        List<String> high = highPermissions(t.pi);
        List<String[]> powers = powers(ctx, t);
        for (String[] pw : powers) if (!pw[0].startsWith("Application class")) r.facts.add(pw[0] + ": " + pw[1]);
        if (t.installed) {
            r.facts.add("Installed by: " + (t.installer.isEmpty() ? "unknown" : AppInfoReader.installerLabel(ctx, t.installer)));
        }
        if (t.installed && ctx.getPackageManager().getLaunchIntentForPackage(t.pkg) == null && !t.system) {
            r.facts.add("No launcher icon (hidden from the app drawer)");
        }

        // 1. Well-known apps from a trusted source: a quick check is enough.
        String famous = ScanLogic.famous(t.pkg);
        if (famous != null && t.installed && (t.system || ScanLogic.trustedInstaller(t.installer))) {
            r.mode = "quick";
            r.risk = "safe";
            r.verdict = "keep";
            r.summary = famous + " is a widely used app and this copy came from " + (t.system ? "the phone's system"
                    : AppInfoReader.installerLabel(ctx, t.installer)) + ", which checks apps. Only a quick check was done.";
            for (String h : high) r.permissions.add(new String[]{h, "Normal for " + famous + " (not checked in the code)"});
            save(ctx, r);
            return r;
        }
        if (famous != null) {
            r.reasons.add("Uses the package name of " + famous + " but was not installed from the Play Store — it can be a fake or "
                    + "modified copy");
        }
        r.facts.addAll(files(t));

        // 2. Decompile only what matters.
        StringBuilder code = new StringBuilder();
        Decompiler d = null;
        try {
            p.step("Decompiling the important parts");
            d = Decompiler.open(t.apk, new File(ctx.getCacheDir(), "jadx"));
            List<String> fromManifest = new ArrayList<>();
            for (String[] pw : powers) fromManifest.add(pw[1]);
            List<String> picks = ScanLogic.pickClasses(fromManifest, d.classNames(), MAX_CLASSES);
            int failed = 0;
            for (String c : picks) {
                if (cancel.isCancelled() || code.length() > MAX_CODE_TOTAL) break;
                p.step("Reading " + c.substring(c.lastIndexOf('.') + 1));
                String src;
                try {
                    src = d.source(c);
                } catch (Throwable one) {
                    failed++; // one class jadx cannot handle: the others still count
                    continue;
                }
                if (src == null) continue;
                if (src.length() > MAX_CODE_PER_CLASS) src = src.substring(0, MAX_CODE_PER_CLASS) + "\n// … cut";
                code.append("\n// ===== ").append(c).append(" =====\n").append(src).append('\n');
                r.classesRead.add(c);
            }
            r.facts.add(d.classNames().size() + " classes in the app, " + r.classesRead.size() + " read"
                    + (failed > 0 ? ", " + failed + " could not be decompiled" : ""));
        } catch (Throwable e) {
            // Out of memory on a huge app, or an unusual APK: the manifest still says a lot. The
            // exact reason is kept, so a failure can be understood.
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (why.length() > 300) why = why.substring(0, 300) + "…";
            r.facts.add("The code could not be decompiled (" + why + "): only the manifest was checked");
            android.util.Log.w("YF.ApkScan", "decompile failed for " + t.pkg, e);
        } finally {
            if (d != null) Decompiler.release();
            Decompiler.trim(0);
        }
        String all = code.toString();
        r.hints.addAll(ScanLogic.apiHints(all));
        r.addresses.addAll(ScanLogic.addresses(all));

        // 3. The AI's verdict.
        LlmClient llm = RoleModels.client(g, RoleModels.BRAIN);
        if (llm == null) {
            r.error = "No AI provider is set up (Settings → Providers): only the facts are shown";
            save(ctx, r);
            return r;
        }
        p.step("Asking the AI");
        StringBuilder user = new StringBuilder();
        user.append("App: ").append(t.label).append(" (").append(t.pkg).append(") version ").append(t.version).append('\n');
        if (t.installed) user.append("Installed by: ").append(t.installer.isEmpty() ? "unknown" : t.installer).append('\n');
        user.append("Target SDK: ").append(t.pi.applicationInfo == null ? "?" : t.pi.applicationInfo.targetSdkVersion).append('\n');
        user.append("High-level permissions: ").append(high.isEmpty() ? "none" : String.join(", ", high)).append('\n');
        int otherCount = t.pi.requestedPermissions == null ? 0 : t.pi.requestedPermissions.length - high.size();
        user.append("Other permissions: ").append(otherCount).append('\n');
        if (!r.reasons.isEmpty()) user.append("Already noticed: ").append(String.join("; ", r.reasons)).append('\n');
        user.append("\nFacts:\n");
        for (String f : r.facts) user.append("- ").append(f).append('\n');
        if (!r.hints.isEmpty()) user.append("\nAPIs used in the code that was read:\n- ").append(String.join("\n- ", r.hints)).append('\n');
        if (!r.addresses.isEmpty()) user.append("\nServer addresses in that code: ").append(String.join(", ", r.addresses)).append('\n');
        user.append(all.isEmpty() ? "\n(No code could be read.)\n" : "\nDecompiled code of the suspicious classes only:\n" + all);
        ChatRequest req = new ChatRequest();
        req.forceNoStream = true;
        req.tools = new JSONArray();
        req.toolChoice = null;
        req.temperatureOverride = 0.1;
        req.maxTokensOverride = 1500;
        req.messages.add(ChatMessage.system(ScanLogic.systemPrompt()));
        req.messages.add(ChatMessage.user(user.toString()));
        try {
            ChatResponse resp = llm.chat(req, StreamListener.NONE, cancel);
            ScanLogic.Verdict v = ScanLogic.parse(resp == null ? null : resp.content, (json, into) -> {
                JSONObject o = new JSONObject(json);
                into.risk = o.optString("risk", "unknown");
                into.verdict = o.optString("verdict");
                into.summary = o.optString("summary");
                JSONArray rs = o.optJSONArray("reasons");
                for (int i = 0; rs != null && i < rs.length(); i++) into.reasons.add(rs.optString(i));
                JSONArray ps = o.optJSONArray("permissions");
                for (int i = 0; ps != null && i < ps.length(); i++) {
                    JSONObject x = ps.optJSONObject(i);
                    if (x != null) into.permissions.add(new String[]{x.optString("permission"), x.optString("how")});
                }
            });
            r.risk = v.risk;
            r.verdict = v.verdict;
            r.summary = v.summary;
            r.reasons.addAll(v.reasons);
            r.permissions.addAll(v.permissions);
            if (!v.parsed) r.error = "The AI did not answer in the expected format";
        } catch (Exception e) {
            r.error = "AI request failed: " + e.getMessage();
        }
        save(ctx, r);
        return r;
    }
}
