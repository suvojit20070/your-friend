package com.suvo.agent.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.autoreply.ReplyListener;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.device.shizuku.ShizukuManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The permission chain, so the user is asked as little as possible:
 * <ol>
 *   <li>Shizuku installed and running → ask for Shizuku's permission; once given, offer to turn on
 *       everything the app uses in one go ("OK?"), and do it through Shizuku.</li>
 *   <li>No Shizuku → Accessibility alone: it controls the screen, reads it, and draws the floating
 *       windows. Nothing else is asked up front.</li>
 *   <li>"Display over other apps" only when a floating part is needed and Accessibility is off.</li>
 * </ol>
 * Everything else (microphone, notifications…) is asked when it is first used.
 */
public final class PermissionSetup {

    private static final String TAG = "YF.PermSetup";
    /** The one-time offer on the home screen was made (or answered). */
    private static final String OFFERED = "perm_setup_offered_v1";
    /** Shizuku's own permission dialog was shown once by us. */
    private static final String SHIZUKU_ASKED = "perm_setup_shizuku_asked_v1";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ShizukuManager.Listener pending;

    private PermissionSetup() {
    }

    /** Called from the home screen: makes the one-time offer when it is due. */
    public static void maybeOffer(Activity a) {
        AppGraph g = AppGraph.get();
        if (g.settings.bool(OFFERED, false)) return;
        ShizukuManager.State st = g.shizuku.state();
        switch (st) {
            case READY:
                if (allDone(a)) {
                    g.settings.put(OFFERED, true);
                    return;
                }
                offerAutoGrant(a);
                break;
            case PERMISSION_REQUIRED:
                if (g.settings.bool(SHIZUKU_ASKED, false)) {
                    suggestAccessibility(a);
                    return;
                }
                g.settings.put(SHIZUKU_ASKED, true);
                waitForShizuku(a);
                if (!g.shizuku.requestPermission()) {
                    stopWaiting();
                    suggestAccessibility(a);
                }
                break;
            default:
                // Not installed, not running, too old or refused: Accessibility alone is enough.
                suggestAccessibility(a);
        }
    }

    /** Waits for the answer to Shizuku's dialog; a yes leads to the "turn everything on?" offer. */
    private static void waitForShizuku(Activity a) {
        stopWaiting();
        AppGraph g = AppGraph.get();
        java.lang.ref.WeakReference<Activity> ref = new java.lang.ref.WeakReference<>(a);
        pending = state -> MAIN.post(() -> {
            if (state == ShizukuManager.State.PERMISSION_REQUIRED) return;
            stopWaiting();
            Activity act = ref.get();
            if (act == null || act.isFinishing()) return;
            if (state == ShizukuManager.State.READY) offerAutoGrant(act);
            else suggestAccessibility(act);
        });
        g.shizuku.addListener(pending);
    }

    private static void stopWaiting() {
        if (pending != null) AppGraph.get().shizuku.removeListener(pending);
        pending = null;
    }

    /** "Shizuku is on: turn on everything the app uses automatically?" */
    public static void offerAutoGrant(Activity a) {
        AppGraph g = AppGraph.get();
        Ui.show(Ui.dialog(a).setTitle(R.string.perm_auto_title).setMessage(R.string.perm_auto_body)
                .setPositiveButton(R.string.perm_auto_yes, (d, w) -> {
                    g.settings.put(OFFERED, true);
                    grantAll(a);
                })
                .setNegativeButton(R.string.perm_auto_no, (d, w) -> g.settings.put(OFFERED, true)));
    }

    /** No Shizuku: one gentle suggestion to turn on Accessibility, which covers screen control and floating windows. */
    private static void suggestAccessibility(Activity a) {
        AppGraph g = AppGraph.get();
        g.settings.put(OFFERED, true);
        if (AgentAccessibilityService.isConnected()) return;
        Ui.show(Ui.dialog(a).setTitle(R.string.perm_one_title).setMessage(R.string.perm_one_body)
                .setPositiveButton(R.string.open_settings, (d, w) -> PermissionFlows.fix(a, com.suvo.agent.core.tools.Capability.ACCESSIBILITY))
                .setNegativeButton(R.string.perm_auto_no, null));
    }

    /** True when Shizuku can do the granting right now. */
    public static boolean shizukuReady() {
        return AppGraph.get().shizuku.state() == ShizukuManager.State.READY;
    }

    private static boolean allDone(Context ctx) {
        return AgentAccessibilityService.isConnected() && android.provider.Settings.canDrawOverlays(ctx) && missingRuntime(ctx).isEmpty();
    }

    /** Turns on everything through Shizuku, then reports what worked. */
    public static void grantAll(Activity a) {
        Context app = a.getApplicationContext();
        Ui.toast(a, R.string.perm_auto_working);
        AppGraph.get().io.execute(() -> {
            int failed = 0;
            for (String cmd : commands(app)) {
                if (!run(cmd)) failed++;
            }
            int f = failed;
            MAIN.post(() -> {
                if (f == 0) Ui.toast(app, R.string.perm_auto_done);
                else Ui.toast(app, app.getResources().getQuantityString(R.plurals.perm_auto_partial, f, f));
            });
        });
    }

    /** Turns on one thing through Shizuku in the background; false when Shizuku isn't ready. */
    public static boolean grantOne(Activity a, com.suvo.agent.core.tools.Capability c) {
        if (!shizukuReady()) return false;
        Context app = a.getApplicationContext();
        List<String> cmds = new ArrayList<>();
        String pkg = app.getPackageName();
        switch (c) {
            case ACCESSIBILITY:
                cmds.addAll(accessibilityCommands(app));
                break;
            case OVERLAY:
                cmds.add("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
                break;
            case INSTALL_PACKAGES:
                cmds.add("appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow");
                break;
            default:
                return false;
        }
        AppGraph.get().io.execute(() -> {
            boolean ok = true;
            for (String cmd : cmds) ok &= run(cmd);
            boolean done = ok;
            MAIN.post(() -> Ui.toast(app, done ? R.string.perm_auto_done : R.string.perm_auto_failed));
        });
        return true;
    }

    private static boolean run(String cmd) {
        try {
            ExecResult r = AppGraph.get().shizuku.run(cmd, null, 15_000, null);
            boolean ok = r != null && r.exitCode == 0 && !r.timedOut;
            if (!ok) Log.w(TAG, cmd + " → " + (r == null ? "null" : r.exitCode + " " + r.stderr));
            return ok;
        } catch (RuntimeException e) {
            Log.w(TAG, cmd + " → " + e);
            return false;
        }
    }

    /** The shell commands that turn on everything the app uses. */
    static List<String> commands(Context ctx) {
        String pkg = ctx.getPackageName();
        List<String> out = new ArrayList<>();
        for (String p : missingRuntime(ctx)) out.add("pm grant " + pkg + " " + p);
        out.add("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        out.add("appops set " + pkg + " PROJECT_MEDIA allow");
        out.add("appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow");
        if (Build.VERSION.SDK_INT >= 31) out.add("appops set " + pkg + " SCHEDULE_EXACT_ALARM allow");
        out.add("dumpsys deviceidle whitelist +" + pkg);
        out.addAll(accessibilityCommands(ctx));
        String listener = new ComponentName(pkg, ReplyListener.class.getName()).flattenToString();
        out.add("cmd notification allow_listener " + listener);
        return out;
    }

    /** Adds our service to the enabled accessibility services, keeping the ones already on. */
    private static List<String> accessibilityCommands(Context ctx) {
        List<String> out = new ArrayList<>();
        if (AgentAccessibilityService.isConnected()) return out;
        String me = new ComponentName(ctx.getPackageName(), AgentAccessibilityService.class.getName()).flattenToString();
        String current = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
        String list = current == null || current.trim().isEmpty() || "null".equals(current.trim()) ? me
                : current.contains(me) ? current : current + ":" + me;
        out.add("settings put secure enabled_accessibility_services " + ShizukuManager.shellQuote(list));
        out.add("settings put secure accessibility_enabled 1");
        return out;
    }

    /** Runtime ("dangerous") permissions the app asks for in its manifest and doesn't have yet. */
    static List<String> missingRuntime(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions == null) return out;
            for (int i = 0; i < pi.requestedPermissions.length; i++) {
                String p = pi.requestedPermissions[i];
                boolean granted = pi.requestedPermissionsFlags != null
                        && (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                if (granted) continue;
                try {
                    PermissionInfo info = pm.getPermissionInfo(p, 0);
                    int base = Build.VERSION.SDK_INT >= 28 ? info.getProtection() : info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                    if (base == PermissionInfo.PROTECTION_DANGEROUS) out.add(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // A permission of an app that isn't installed (e.g. Termux's).
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return out;
    }
}
