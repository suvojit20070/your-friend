package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.TextUtil;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.terminal.ExecResult;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * What Shizuku is really for: device administration with ADB-shell rights, without the usual
 * confirmation screens. Every action that changes the system is rated high risk, so it is always
 * shown to the user for approval first (in every approval mode).
 */
public final class ShizukuTools {

    private ShizukuTools() {
    }

    private static final Pattern PKG = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");
    private static final Pattern PERM = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.]{2,150}$");
    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9_.:-]{1,120}$");
    private static final Pattern SERVICE = Pattern.compile("^[a-zA-Z0-9_.-]{1,60}$");
    private static final Pattern OP = Pattern.compile("^[A-Z0-9_]{2,60}$");

    public static final class DeviceAdmin extends BaseTool {
        public DeviceAdmin(AppGraph g) {
            super(g, ToolSpec.builder("device_admin")
                    .description("Phone administration with ADB-shell rights through Shizuku (no confirmation screens). "
                            + "Apps: list_packages (filter), app_info, install_apk (workspace path, silent), uninstall, force_stop, clear_data, "
                            + "enable_app, disable_app, start_activity (package or component). "
                            + "Permissions: grant_permission, revoke_permission, set_appop (op like SYSTEM_ALERT_WINDOW, mode allow|ignore|deny|default). "
                            + "System: settings_get, settings_put (namespace system|secure|global), wifi (on|off), bluetooth (on|off), "
                            + "mobile_data (on|off), brightness (0-255), volume (stream music|ring|alarm|notification, level), "
                            + "dumpsys (service, output trimmed), notifications (current notifications), battery, ip. "
                            + "Prefer this over screen tapping for these jobs: it is faster and exact.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"list_packages\",\"app_info\",\"install_apk\",\"uninstall\",\"force_stop\","
                            + "\"clear_data\",\"enable_app\",\"disable_app\",\"start_activity\",\"grant_permission\",\"revoke_permission\",\"set_appop\","
                            + "\"settings_get\",\"settings_put\",\"wifi\",\"bluetooth\",\"mobile_data\",\"brightness\",\"volume\",\"dumpsys\","
                            + "\"notifications\",\"battery\",\"ip\"]},"
                            + "\"package\":{\"type\":\"string\",\"maxLength\":200},"
                            + "\"filter\":{\"type\":\"string\",\"maxLength\":100},"
                            + "\"path\":{\"type\":\"string\",\"maxLength\":500,\"description\":\"Workspace path of an APK for install_apk\"},"
                            + "\"component\":{\"type\":\"string\",\"maxLength\":300,\"description\":\"package/.Activity for start_activity\"},"
                            + "\"permission\":{\"type\":\"string\",\"maxLength\":160},"
                            + "\"op\":{\"type\":\"string\",\"maxLength\":60},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"allow\",\"ignore\",\"deny\",\"default\",\"foreground\"]},"
                            + "\"namespace\":{\"type\":\"string\",\"enum\":[\"system\",\"secure\",\"global\"]},"
                            + "\"key\":{\"type\":\"string\",\"maxLength\":120},\"value\":{\"type\":\"string\",\"maxLength\":500},"
                            + "\"state\":{\"type\":\"string\",\"enum\":[\"on\",\"off\"]},"
                            + "\"level\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":255},"
                            + "\"stream\":{\"type\":\"string\",\"enum\":[\"music\",\"ring\",\"alarm\",\"notification\",\"call\"]},"
                            + "\"service\":{\"type\":\"string\",\"maxLength\":60}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .requires(Capability.SHIZUKU).risk(RiskLevel.HIGH).backend("shizuku").timeoutMs(180_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            switch (args.optString("action")) {
                case "list_packages":
                case "app_info":
                case "settings_get":
                case "battery":
                case "ip":
                case "dumpsys":
                    return RiskLevel.READ_ONLY;
                case "notifications":
                    // Reads other apps' messages: always shown to the user first.
                    return RiskLevel.MEDIUM;
                case "start_activity":
                case "brightness":
                case "volume":
                    return RiskLevel.LOW;
                case "force_stop":
                case "wifi":
                case "bluetooth":
                case "mobile_data":
                    return RiskLevel.MEDIUM;
                default:
                    return RiskLevel.HIGH;
            }
        }

        @Override
        public String describeCall(JSONObject args) {
            String a = args.optString("action");
            String target = args.optString("package", args.optString("key", args.optString("path", args.optString("state", ""))));
            return a.replace('_', ' ') + (target.isEmpty() ? "" : " " + target);
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String a = inv.str("action");
            String pkg = inv.str("package");
            String cmd;
            switch (a) {
                case "list_packages": {
                    String f = inv.str("filter").replaceAll("[^a-zA-Z0-9_.]", "");
                    cmd = "pm list packages -3" + (f.isEmpty() ? "" : " | grep -i " + ShizukuManager.shellQuote(f)) + " | head -n 300";
                    break;
                }
                case "app_info":
                    if (bad(PKG, pkg)) return invalid("package");
                    cmd = "dumpsys package " + pkg + " | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|enabled=|targetSdk|granted=true' | head -n 80";
                    break;
                case "install_apk":
                    return installApk(inv);
                case "uninstall":
                    if (bad(PKG, pkg)) return invalid("package");
                    if (pkg.equals(g.ctx.getPackageName())) return ToolResult.fail("Your Friend will not uninstall itself");
                    cmd = "pm uninstall --user 0 " + pkg;
                    break;
                case "force_stop":
                    if (bad(PKG, pkg)) return invalid("package");
                    cmd = "am force-stop " + pkg;
                    break;
                case "clear_data":
                    if (bad(PKG, pkg)) return invalid("package");
                    if (pkg.equals(g.ctx.getPackageName())) return ToolResult.fail("Your Friend will not clear its own data");
                    cmd = "pm clear --user 0 " + pkg;
                    break;
                case "enable_app":
                    if (bad(PKG, pkg)) return invalid("package");
                    cmd = "pm enable --user 0 " + pkg;
                    break;
                case "disable_app":
                    if (bad(PKG, pkg)) return invalid("package");
                    if (pkg.equals(g.ctx.getPackageName()) || pkg.equals(ShizukuManager.SHIZUKU_PACKAGE)) return ToolResult.fail("Refusing to disable " + pkg);
                    cmd = "pm disable-user --user 0 " + pkg;
                    break;
                case "start_activity": {
                    String comp = inv.str("component");
                    if (!comp.isEmpty()) {
                        if (!comp.matches("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")) return invalid("component");
                        cmd = "am start -n " + comp;
                    } else {
                        if (bad(PKG, pkg)) return invalid("package");
                        cmd = "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1";
                    }
                    break;
                }
                case "grant_permission":
                case "revoke_permission": {
                    if (bad(PKG, pkg)) return invalid("package");
                    String perm = inv.str("permission");
                    if (bad(PERM, perm)) return invalid("permission");
                    if (!perm.contains(".")) perm = "android.permission." + perm;
                    cmd = "pm " + (a.equals("grant_permission") ? "grant " : "revoke ") + pkg + " " + perm;
                    break;
                }
                case "set_appop": {
                    if (bad(PKG, pkg)) return invalid("package");
                    String op = inv.str("op").toUpperCase(Locale.ROOT);
                    if (bad(OP, op)) return invalid("op");
                    String mode = inv.str("mode", "allow");
                    cmd = "appops set " + pkg + " " + op + " " + mode;
                    break;
                }
                case "settings_get":
                case "settings_put": {
                    String ns = inv.str("namespace", "system");
                    String key = inv.str("key");
                    if (bad(KEY, key)) return invalid("key");
                    cmd = a.equals("settings_get") ? "settings get " + ns + " " + key
                            : "settings put " + ns + " " + key + " " + ShizukuManager.shellQuote(inv.str("value"));
                    break;
                }
                case "wifi":
                    cmd = "svc wifi " + (on(inv) ? "enable" : "disable");
                    break;
                case "bluetooth":
                    cmd = on(inv) ? "cmd bluetooth_manager enable || svc bluetooth enable" : "cmd bluetooth_manager disable || svc bluetooth disable";
                    break;
                case "mobile_data":
                    cmd = "svc data " + (on(inv) ? "enable" : "disable");
                    break;
                case "brightness": {
                    long level = Math.max(0, Math.min(255, inv.num("level", 128)));
                    cmd = "settings put system screen_brightness_mode 0 && settings put system screen_brightness " + level;
                    break;
                }
                case "volume": {
                    String stream = inv.str("stream", "music");
                    int id;
                    switch (stream) {
                        case "call":
                            id = 0;
                            break;
                        case "ring":
                            id = 2;
                            break;
                        case "alarm":
                            id = 4;
                            break;
                        case "notification":
                            id = 5;
                            break;
                        default:
                            id = 3;
                    }
                    long level = Math.max(0, Math.min(25, inv.num("level", 7)));
                    cmd = "cmd media_session volume --stream " + id + " --set " + level;
                    break;
                }
                case "dumpsys": {
                    String svc = inv.str("service");
                    if (bad(SERVICE, svc)) return invalid("service");
                    cmd = "dumpsys " + svc + " | head -n 400";
                    break;
                }
                case "notifications":
                    cmd = "dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=|android.bigText=' | head -n 200";
                    break;
                case "battery":
                    cmd = "dumpsys battery";
                    break;
                case "ip":
                    cmd = "ip -4 addr show | grep -E 'inet |^[0-9]+:'";
                    break;
                default:
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action");
            }
            com.suvo.agent.terminal.TerminalFeed.Run feed = com.suvo.agent.terminal.TerminalFeed.start("device_admin", "shizuku", cmd);
            ExecResult r = g.shizuku.run(cmd, null, 60_000, inv.cancel);
            com.suvo.agent.terminal.TerminalFeed.finish(feed, r);
            if (!r.ran()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, r.error);
            String out = TextUtil.truncate((r.stdout + (r.stderr.isEmpty() ? "" : "\n" + r.stderr)).trim(), 12_000);
            JSONObject data = J.obj("command", cmd, "exit_code", r.exitCode, "output", out);
            if (r.exitCode != 0) return ToolResult.fail(a + " failed (exit " + r.exitCode + "): " + TextUtil.truncate(out, 500), data);
            return ToolResult.ok(a.replace('_', ' ') + " done" + (out.isEmpty() ? "" : ": " + TextUtil.truncate(out, 300)), data);
        }

        private boolean on(ToolInvocation inv) {
            return !"off".equals(inv.str("state", "on"));
        }

        private static boolean bad(Pattern p, String v) {
            return v == null || !p.matcher(v).matches();
        }

        private static ToolResult invalid(String field) {
            return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Missing or invalid " + field);
        }

        /**
         * Copies the APK to the app's external files folder (readable by the shell user) and installs
         * it with pm, so no install dialog appears.
         */
        private ToolResult installApk(ToolInvocation inv) throws Exception {
            String path = inv.str("path");
            if (path.isEmpty()) return invalid("path");
            FileAccess fa = new FileAccess(g.workspace, g.saf);
            FileAccess.Entry e = fa.stat(path);
            if (e.dir || !e.name.toLowerCase(Locale.ROOT).endsWith(".apk")) return ToolResult.fail(path + " is not an .apk file");
            File dir = g.ctx.getExternalFilesDir("apk");
            if (dir == null) return ToolResult.fail("Shared storage is not available");
            if (!dir.exists() && !dir.mkdirs()) return ToolResult.fail("Cannot create " + dir);
            File copy = new File(dir, "install.apk");
            try (InputStream in = fa.open(e.path); OutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (inv.cancel.isCancelled()) return ToolResult.of(ToolResult.Status.CANCELLED, "Cancelled");
                    out.write(buf, 0, n);
                }
            }
            inv.progress.update("Installing " + e.name);
            ExecResult r = g.shizuku.run("pm install -r -t " + ShizukuManager.shellQuote(copy.getAbsolutePath()), null, 170_000, inv.cancel);
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            if (!r.ran()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, r.error);
            String out = (r.stdout + "\n" + r.stderr).trim();
            if (r.exitCode != 0 || !out.contains("Success")) return ToolResult.fail("Install failed: " + TextUtil.truncate(out, 600));
            return ToolResult.ok("Installed " + e.name, J.obj("output", out));
        }
    }
}
