package com.suvo.agent.tools;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.ui.MainActivity;
import com.suvo.agent.ui.Notifications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** App discovery/launching, URIs and notifications. */
public final class AppTools {

    private AppTools() {
    }

    static final class AppEntry {
        final String label;
        final String pkg;

        AppEntry(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    /** Launchable apps visible through the manifest's &lt;queries&gt; launcher intent declaration. */
    static List<AppEntry> launchableApps(AppGraph g) {
        PackageManager pm = g.ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            CharSequence label = ri.loadLabel(pm);
            boolean dup = false;
            for (AppEntry e : out) if (e.pkg.equals(pkg)) dup = true;
            if (!dup) out.add(new AppEntry(label == null ? pkg : label.toString(), pkg));
        }
        return out;
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.]", "");
    }

    static int score(AppEntry e, String q) {
        String nq = norm(q);
        String nl = norm(e.label);
        if (nq.isEmpty()) return 1;
        if (nl.equals(nq) || e.pkg.equalsIgnoreCase(q)) return 100;
        if (nl.startsWith(nq)) return 80;
        if (nl.contains(nq)) return 60;
        if (e.pkg.toLowerCase(Locale.ROOT).contains(nq)) return 40;
        return 0;
    }

    public static final class FindInstalledApps extends BaseTool {
        public FindInstalledApps(AppGraph g) {
            super(g, ToolSpec.builder("find_installed_apps")
                    .description("Search installed launchable apps by name or package fragment (e.g. \"whatsapp\", \"camera\"). Returns labels and package names.")
                    .schema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Name or package fragment; empty lists all\"},"
                            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200,\"default\":20}},\"additionalProperties\":false}")
                    .requires(Capability.APP_LIST).risk(RiskLevel.READ_ONLY).timeoutMs(20_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String q = inv.str("query");
            int limit = (int) inv.num("limit", 20);
            List<AppEntry> apps = launchableApps(g);
            apps.sort((a, b) -> {
                int d = score(b, q) - score(a, q);
                return d != 0 ? d : a.label.compareToIgnoreCase(b.label);
            });
            JSONArray arr = new JSONArray();
            for (AppEntry e : apps) {
                if (arr.length() >= limit) break;
                if (score(e, q) > 0) arr.put(J.obj("label", e.label, "package", e.pkg));
            }
            return ToolResult.ok(arr.length() == 0 ? "No installed app matches \"" + q + "\"" : arr.length() + " app(s) found",
                    J.obj("apps", arr, "total_launchable", apps.size()));
        }
    }

    public static final class LaunchApp extends BaseTool {
        public LaunchApp(AppGraph g) {
            super(g, ToolSpec.builder("launch_app")
                    .description("Open an installed app by package name (preferred) or by its visible name.")
                    .schema("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"}},\"additionalProperties\":false}")
                    .requires(Capability.APP_LIST).risk(RiskLevel.LOW).timeoutMs(20_000).build());
        }

        @Override
        public String describeCall(JSONObject args) {
            return "Open app " + args.optString("package", args.optString("name", ""));
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String pkg = inv.str("package");
            String name = inv.str("name");
            if (pkg.isEmpty() && name.isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Give package or name");
            String label = pkg;
            if (pkg.isEmpty()) {
                AppEntry best = null;
                int bestScore = 0;
                for (AppEntry e : launchableApps(g)) {
                    int s = score(e, name);
                    if (s > bestScore) {
                        bestScore = s;
                        best = e;
                    }
                }
                if (best == null || bestScore < 60) return ToolResult.fail("No installed app matches \"" + name + "\". Use find_installed_apps.");
                pkg = best.pkg;
                label = best.label;
            }
            Intent i = g.ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return ToolResult.fail("Package " + pkg + " is not installed or has no launcher activity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                g.ctx.startActivity(i);
            } catch (ActivityNotFoundException | SecurityException e) {
                return ToolResult.fail("Android refused to open " + pkg + ": " + e.getMessage());
            }
            AgentAccessibilityService a = AgentAccessibilityService.get();
            if (a != null) {
                long end = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < end && !inv.cancel.isCancelled()) {
                    if (pkg.equals(a.foregroundPackage())) {
                        return ToolResult.ok("Opened " + label + " (verified in foreground)", J.obj("package", pkg, "verified", true));
                    }
                    Thread.sleep(150);
                }
                return ToolResult.fail("Launch was requested but " + label + " did not come to the foreground (current: "
                        + a.foregroundPackage() + "). Android may block launches while Your Friend is in the background.",
                        J.obj("package", pkg, "verified", false));
            }
            return ToolResult.ok("Launch requested for " + label + " (could not verify: Accessibility is off)", J.obj("package", pkg, "verified", false));
        }
    }

    public static final class OpenUri extends BaseTool {
        public OpenUri(AppGraph g) {
            super(g, ToolSpec.builder("open_uri")
                    .description("Open a link in the right app: https/http, geo:, market:, mailto:, sms:, tel:. "
                            + "Phone numbers open the dialer and messages open a draft — nothing is sent or called automatically.")
                    .schema("{\"type\":\"object\",\"properties\":{\"uri\":{\"type\":\"string\",\"minLength\":3,\"maxLength\":4000}},\"required\":[\"uri\"],\"additionalProperties\":false}")
                    .risk(RiskLevel.LOW).timeoutMs(15_000).build());
        }

        private static String scheme(String uri) {
            String s = Uri.parse(uri.trim()).getScheme();
            return s == null ? "" : s.toLowerCase(Locale.ROOT);
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String s = scheme(args.optString("uri"));
            return s.equals("mailto") || s.equals("sms") || s.equals("smsto") || s.equals("tel") ? RiskLevel.MEDIUM : RiskLevel.LOW;
        }

        @Override
        public String describeCall(JSONObject args) {
            return "Open " + args.optString("uri");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String raw = inv.str("uri").trim();
            String s = scheme(raw);
            Intent i;
            switch (s) {
                case "http":
                case "https":
                case "geo":
                case "market":
                case "mailto":
                case "sms":
                case "smsto":
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
                    break;
                case "tel":
                    i = new Intent(Intent.ACTION_DIAL, Uri.parse(raw)); // dialer only, never auto-call
                    break;
                default:
                    return ToolResult.of(ToolResult.Status.DENIED, "URI scheme \"" + s + "\" is not allowed");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(g.ctx.getPackageManager()) == null) return ToolResult.fail("No installed app can open " + s + ": links");
            try {
                g.ctx.startActivity(i);
            } catch (ActivityNotFoundException | SecurityException e) {
                return ToolResult.fail("Could not open: " + e.getMessage());
            }
            return ToolResult.ok("Opened " + raw);
        }
    }

    public static final class Notify extends BaseTool {
        private static final AtomicInteger IDS = new AtomicInteger(5000);

        public Notify(AppGraph g) {
            super(g, ToolSpec.builder("notify")
                    .description("Show an Android notification to the user (for reminders, results of long tasks, alerts).")
                    .schema("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":120},"
                            + "\"text\":{\"type\":\"string\",\"maxLength\":2000}},\"required\":[\"title\"],\"additionalProperties\":false}")
                    .requires(Capability.NOTIFICATIONS).risk(RiskLevel.LOW).timeoutMs(10_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            NotificationManager nm = g.ctx.getSystemService(NotificationManager.class);
            if (nm == null) return ToolResult.fail("Notification service unavailable");
            Intent open = new Intent(g.ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(g.ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            int id = IDS.incrementAndGet();
            Notification n = Notifications.builder(g.ctx, Notifications.CHANNEL_GENERAL)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(inv.str("title"))
                    .setContentText(inv.str("text"))
                    .setStyle(new Notification.BigTextStyle().bigText(inv.str("text")))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(id, n);
            boolean posted = false;
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) if (sbn.getId() == id) posted = true;
            return posted ? ToolResult.ok("Notification shown", J.obj("id", id))
                    : ToolResult.fail("Android did not show the notification (notifications may be blocked for this app or channel)");
        }
    }
}
