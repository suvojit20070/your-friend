package com.suvo.agent.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.suvo.agent.App;
import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.TextUtil;
import com.suvo.agent.data.Settings;
import com.suvo.agent.device.CameraCapture;
import com.suvo.agent.device.ImageUtil;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.device.accessibility.ScreenActions;
import com.suvo.agent.device.accessibility.ScreenReader;
import com.suvo.agent.device.capture.ProjectionRequestActivity;
import com.suvo.agent.device.capture.ScreenCaptureService;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.terminal.ExecResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/** Screen reading/control, screenshots, image viewing and camera. */
public final class ScreenTools {

    private ScreenTools() {
    }

    static DisplayMetrics realMetrics(Context ctx) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    // ------------------------------------------------------------------------------------------

    public static final class ScreenAccessibility extends BaseTool {
        public ScreenAccessibility(AppGraph g) {
            super(g, ToolSpec.builder("screen_operation_accessibility")
                    .description("Read and control the current screen through Android Accessibility. "
                            + "action=read returns visible elements with ids (e1, e2...) — use these ids for tap/long_press/type/scroll. "
                            + "find locates an element by text. tap/long_press accept element, text or x/y. type sets text in a field "
                            + "(element or the focused field; password fields are refused). scroll takes direction. swipe takes x,y,x2,y2. "
                            + "back/home/recents/notifications/quick_settings are navigation. wait_for waits until text appears. "
                            + "After an action the screen is re-read unless read_after=false. "
                            + "Long texts are cut to save tokens (input fields 60 chars, other text 120) and marked cut=true with their "
                            + "full len; use get_text with the element (and offset for very long text) only when you need the whole text. "
                            + "Each read also lists previous_screens: the last few screens and what you did on them. "
                            + "Toasts, snackbars and status announcements never appear in the element list; when one pops up after "
                            + "your action the result message says so (\"After this action the screen showed: toast ...\") — read it, it "
                            + "often tells you whether the action worked.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"read\",\"get_text\",\"find\",\"tap\",\"long_press\",\"type\",\"scroll\",\"swipe\",\"back\",\"home\",\"recents\",\"notifications\",\"quick_settings\",\"wait_for\"]},"
                            + "\"element\":{\"type\":\"string\",\"description\":\"Element id from the last read, e.g. e12\"},"
                            + "\"text\":{\"type\":\"string\",\"description\":\"Visible text to target (tap/find/wait_for) or text to type\"},"
                            + "\"x\":{\"type\":\"integer\",\"minimum\":0},\"y\":{\"type\":\"integer\",\"minimum\":0},"
                            + "\"x2\":{\"type\":\"integer\",\"minimum\":0},\"y2\":{\"type\":\"integer\",\"minimum\":0},"
                            + "\"duration_ms\":{\"type\":\"integer\",\"minimum\":10,\"maximum\":5000},"
                            + "\"direction\":{\"type\":\"string\",\"enum\":[\"up\",\"down\",\"left\",\"right\"]},"
                            + "\"append\":{\"type\":\"boolean\",\"default\":false},"
                            + "\"timeout_ms\":{\"type\":\"integer\",\"minimum\":500,\"maximum\":30000,\"default\":8000},"
                            + "\"max_elements\":{\"type\":\"integer\",\"minimum\":10,\"maximum\":400,\"default\":150},"
                            + "\"read_after\":{\"type\":\"boolean\",\"default\":true},"
                            + "\"offset\":{\"type\":\"integer\",\"minimum\":0,\"default\":0},"
                            + "\"limit\":{\"type\":\"integer\",\"minimum\":50,\"maximum\":20000,\"default\":4000}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .requires(Capability.ACCESSIBILITY).risk(RiskLevel.MEDIUM).backend("accessibility").timeoutMs(45_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String a = args.optString("action");
            return a.equals("read") || a.equals("find") || a.equals("wait_for") || a.equals("get_text") ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
        }

        @Override
        public String describeCall(JSONObject args) {
            String a = args.optString("action");
            String target = args.optString("element", args.optString("text", args.has("x") ? "(" + args.optInt("x") + "," + args.optInt("y") + ")" : ""));
            return "Screen: " + a + " " + target;
        }

        /** Short label for the screen history, e.g. tap e12 "Send". */
        private static String historyLabel(String action, ToolInvocation inv) {
            StringBuilder sb = new StringBuilder(action);
            String el = inv.str("element");
            if (!el.isEmpty()) {
                sb.append(' ').append(el);
                ScreenReader.Snapshot s = ScreenReader.last();
                AccessibilityNodeInfo n = s == null ? null : s.nodes.get(el);
                if (n != null) {
                    CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
                    if (t != null && t.length() > 0 && !n.isPassword()) {
                        String l = t.toString();
                        sb.append(" \"").append(l.length() > 25 ? l.substring(0, 25) + "…" : l).append('"');
                    }
                }
            } else if (!inv.str("text").isEmpty() && !action.equals("type")) {
                sb.append(" \"").append(TextUtil.truncate(inv.str("text"), 25)).append('"');
            } else if (inv.args.has("x")) {
                sb.append(" (").append(inv.num("x", 0)).append(',').append(inv.num("y", 0)).append(')');
            }
            if (action.equals("type")) sb.append(" typed ").append(inv.str("text").length()).append(" chars");
            if (action.equals("scroll")) sb.append(' ').append(inv.str("direction", "down"));
            return sb.toString();
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            AgentAccessibilityService svc = AgentAccessibilityService.get();
            if (svc == null) return ToolResult.of(ToolResult.Status.UNAVAILABLE, "Accessibility service is not connected");
            DisplayMetrics dm = realMetrics(g.ctx);
            ScreenActions act = new ScreenActions(svc, dm);
            String action = inv.str("action");
            int max = (int) inv.num("max_elements", 150);
            ScreenActions.Outcome out;
            switch (action) {
                case "read": {
                    ScreenReader.Snapshot s = ScreenReader.read(svc, dm, max, false);
                    return ToolResult.ok(s.json.getJSONArray("elements").length() + " elements on screen", s.json);
                }
                case "get_text": {
                    if (inv.str("element").isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "get_text needs an element id from the last read");
                    JSONObject t = ScreenReader.fullText(inv.str("element"), (int) inv.num("offset", 0), (int) inv.num("limit", 4000));
                    if (t == null) return ToolResult.fail("Element " + inv.str("element") + " not found; read the screen again");
                    return ToolResult.ok("Text of " + inv.str("element") + " (" + t.optInt("length") + " chars)", t);
                }
                case "find": {
                    AccessibilityNodeInfo n = act.find(null, inv.str("text"));
                    if (n == null) return ToolResult.fail("No element with text \"" + inv.str("text") + "\"");
                    ScreenReader.Snapshot s = ScreenReader.read(svc, dm, max, false);
                    String id = null;
                    for (java.util.Map.Entry<String, AccessibilityNodeInfo> e : s.nodes.entrySet()) if (e.getValue().equals(n)) id = e.getKey();
                    return ToolResult.ok("Found", J.obj("element", id == null ? JSONObject.NULL : id, "screen", s.json));
                }
                case "tap":
                case "long_press": {
                    boolean lp = action.equals("long_press");
                    if (inv.args.has("x") && inv.args.has("y") && inv.str("element").isEmpty() && inv.str("text").isEmpty()) {
                        out = act.gestureTap((int) inv.num("x", 0), (int) inv.num("y", 0), inv.num("duration_ms", lp ? 700 : 60));
                    } else {
                        out = act.tap(act.find(inv.str("element"), inv.str("text")), lp);
                    }
                    break;
                }
                case "type": {
                    if (!inv.args.has("text")) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "text is required for type");
                    AccessibilityNodeInfo target = inv.str("element").isEmpty() ? null : act.find(inv.str("element"), null);
                    if (!inv.str("element").isEmpty() && target == null) return ToolResult.fail("Element " + inv.str("element") + " not found; read the screen again");
                    out = act.type(target, inv.str("text"), inv.bool("append", false));
                    break;
                }
                case "scroll": {
                    AccessibilityNodeInfo target = inv.str("element").isEmpty() ? null : act.find(inv.str("element"), null);
                    out = act.scroll(target, inv.str("direction", "down"));
                    break;
                }
                case "swipe":
                    if (!inv.args.has("x") || !inv.args.has("y") || !inv.args.has("x2") || !inv.args.has("y2")) {
                        return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "swipe needs x, y, x2, y2");
                    }
                    out = act.swipe((int) inv.num("x", 0), (int) inv.num("y", 0), (int) inv.num("x2", 0), (int) inv.num("y2", 0), inv.num("duration_ms", 300));
                    break;
                case "wait_for":
                    out = act.waitForText(inv.str("text"), inv.num("timeout_ms", 8000));
                    if (out.ok) return ToolResult.ok(out.message);
                    return ToolResult.fail(out.message);
                case "back":
                case "home":
                case "recents":
                case "notifications":
                case "quick_settings":
                    out = act.global(action);
                    break;
                default:
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action " + action);
            }
            if (out.ok) com.suvo.agent.device.accessibility.ScreenHistory.recordAction(historyLabel(action, inv));
            JSONObject data = new JSONObject();
            if (out.ok && inv.bool("read_after", true)) {
                Thread.sleep(700);
                ScreenReader.Snapshot s = ScreenReader.read(svc, dm, Math.min(max, 80), false);
                J.put(data, "screen_after", s.json);
            }
            return out.ok ? ToolResult.ok(out.message, data) : ToolResult.fail(out.message, data);
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class ScreenShell extends BaseTool {
        public ScreenShell(AppGraph g) {
            super(g, ToolSpec.builder("screen_operation_shell")
                    .description("Screen control through the Shizuku shell backend (ADB identity): tap, long_press, swipe at coordinates, "
                            + "text (types ASCII text into the focused field), key (Android key name like BACK, HOME, ENTER, or a keycode), "
                            + "ui_dump (uiautomator XML of the screen), current_app. Use when Accessibility is unavailable or an app ignores it.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"tap\",\"long_press\",\"swipe\",\"text\",\"key\",\"ui_dump\",\"current_app\"]},"
                            + "\"x\":{\"type\":\"integer\",\"minimum\":0},\"y\":{\"type\":\"integer\",\"minimum\":0},"
                            + "\"x2\":{\"type\":\"integer\",\"minimum\":0},\"y2\":{\"type\":\"integer\",\"minimum\":0},"
                            + "\"duration_ms\":{\"type\":\"integer\",\"minimum\":10,\"maximum\":5000},"
                            + "\"text\":{\"type\":\"string\",\"maxLength\":2000},\"key\":{\"type\":\"string\",\"maxLength\":40}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .requires(Capability.SHIZUKU).risk(RiskLevel.MEDIUM).backend("shizuku").timeoutMs(40_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String a = args.optString("action");
            return a.equals("ui_dump") || a.equals("current_app") ? RiskLevel.READ_ONLY : RiskLevel.MEDIUM;
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            DisplayMetrics dm = realMetrics(g.ctx);
            String a = inv.str("action");
            String cmd;
            switch (a) {
                case "tap":
                case "long_press": {
                    int x = (int) inv.num("x", -1), y = (int) inv.num("y", -1);
                    String bad = bounds(x, y, dm);
                    if (bad != null) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, bad);
                    com.suvo.agent.device.TapIndicator.tap(x, y, a.equals("long_press"));
                    cmd = a.equals("tap") ? "input tap " + x + " " + y
                            : "input swipe " + x + " " + y + " " + x + " " + y + " " + inv.num("duration_ms", 700);
                    break;
                }
                case "swipe": {
                    int x = (int) inv.num("x", -1), y = (int) inv.num("y", -1), x2 = (int) inv.num("x2", -1), y2 = (int) inv.num("y2", -1);
                    String bad = bounds(x, y, dm);
                    if (bad == null) bad = bounds(x2, y2, dm);
                    if (bad != null) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, bad);
                    com.suvo.agent.device.TapIndicator.swipe(x, y, x2, y2);
                    cmd = "input swipe " + x + " " + y + " " + x2 + " " + y2 + " " + inv.num("duration_ms", 300);
                    break;
                }
                case "text": {
                    String t = inv.str("text");
                    if (!t.matches("[\\x20-\\x7E]*")) return ToolResult.fail("Shell text input supports printable ASCII only; use the accessibility tool for other scripts");
                    cmd = "input text " + ShizukuManager.shellQuote(t.replace(" ", "%s"));
                    break;
                }
                case "key": {
                    String k = inv.str("key").toUpperCase(Locale.ROOT);
                    if (!k.matches("[A-Z0-9_]{1,40}")) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Invalid key name");
                    cmd = "input keyevent " + (k.matches("\\d+") || k.startsWith("KEYCODE_") ? k : "KEYCODE_" + k);
                    break;
                }
                case "ui_dump":
                    cmd = "uiautomator dump /data/local/tmp/yf_ui.xml >/dev/null 2>&1 && cat /data/local/tmp/yf_ui.xml";
                    break;
                case "current_app":
                    cmd = "dumpsys activity activities | grep -m 1 -E 'mResumedActivity|topResumedActivity'";
                    break;
                default:
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action");
            }
            ExecResult r = g.shizuku.run(cmd, null, 30_000, inv.cancel);
            if (!r.ran()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, r.error);
            if (r.exitCode != 0) return ToolResult.fail("Shell command failed (exit " + r.exitCode + "): " + r.stderr.trim(), r.toJson(4000));
            if (a.equals("ui_dump")) return ToolResult.ok("UI hierarchy", J.obj("xml", com.suvo.agent.core.util.TextUtil.truncate(r.stdout, 60_000)));
            if (a.equals("current_app")) return ToolResult.ok(r.stdout.trim());
            return ToolResult.ok("Executed: " + cmd);
        }

        private static String bounds(int x, int y, DisplayMetrics dm) {
            if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
                return "Point (" + x + "," + y + ") is outside the screen " + dm.widthPixels + "x" + dm.heightPixels;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class Screenshot extends BaseTool {
        public Screenshot(AppGraph g) {
            super(g, ToolSpec.builder("screenshot")
                    .description("Capture the current screen as an image (attached for you to look at, if the model supports images) "
                            + "and save it in the workspace. Prefer screen_operation_accessibility read for text; use this for visual content.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"backend\":{\"type\":\"string\",\"enum\":[\"auto\",\"accessibility\",\"media_projection\",\"shizuku\"],\"default\":\"auto\"},"
                            + "\"max_side\":{\"type\":\"integer\",\"minimum\":320,\"maximum\":2048,\"default\":1280}"
                            + "},\"additionalProperties\":false}")
                    .requires(Capability.SCREEN_CAPTURE).risk(RiskLevel.READ_ONLY).timeoutMs(60_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String backend = inv.str("backend", "auto");
            int maxSide = (int) inv.num("max_side", 1280);
            Bitmap bmp = null;
            String used = null;
            StringBuilder errors = new StringBuilder();
            com.suvo.agent.device.TapIndicator.hideForCapture();
            if ((backend.equals("auto") || backend.equals("accessibility")) && AgentAccessibilityService.isConnected() && AgentAccessibilityService.canCaptureScreen()) {
                try {
                    bmp = AgentAccessibilityService.get().captureScreen(8000);
                    used = "accessibility";
                } catch (Exception e) {
                    errors.append("accessibility: ").append(e.getMessage()).append("; ");
                }
            }
            if (bmp == null && (backend.equals("auto") || backend.equals("media_projection"))) {
                if (!ScreenCaptureService.isActive() && App.isInForeground()) {
                    inv.progress.update("Waiting for screen-capture permission");
                    ProjectionRequestActivity.requestAndWait(g.ctx, 30_000);
                }
                if (ScreenCaptureService.isActive()) {
                    Thread.sleep(250);
                    bmp = ScreenCaptureService.get() == null ? null : ScreenCaptureService.get().latestFrame();
                    if (bmp != null) used = "media_projection";
                    else errors.append("media_projection: no frame yet; ");
                } else {
                    errors.append("media_projection: no active capture session (start it in Screen Control); ");
                }
            }
            if (bmp == null && (backend.equals("auto") || backend.equals("shizuku")) && g.shizuku.unavailableReason() == null
                    && g.settings.bool(Settings.ALLOW_SHIZUKU, true)) {
                bmp = shizukuCapture(inv);
                if (bmp != null) used = "shizuku";
                else errors.append("shizuku: capture failed; ");
            }
            if (bmp == null) return ToolResult.of(ToolResult.Status.UNAVAILABLE, "No screenshot backend worked: " + errors);
            Bitmap scaled = ImageUtil.scaleToMax(bmp, maxSide);
            byte[] jpeg = ImageUtil.jpeg(scaled, 72);
            File f = ImageUtil.save(jpeg, g.workspace.dir("screenshots"), "screen", "jpg");
            JSONObject data = J.obj("path", g.workspace.relative(f), "width", scaled.getWidth(), "height", scaled.getHeight(),
                    "original_width", bmp.getWidth(), "original_height", bmp.getHeight(), "backend", used,
                    "coordinate_scale", (double) bmp.getWidth() / scaled.getWidth());
            return ToolResult.okWithImage("Screenshot captured via " + used, data, ImageUtil.toImage(jpeg, "high"));
        }

        /** Reads a PNG written by screencap in chunks small enough for Binder transactions. */
        private Bitmap shizukuCapture(ToolInvocation inv) {
            String path = "/data/local/tmp/yf_cap.png";
            ExecResult r = g.shizuku.run("screencap -p " + path + " && stat -c %s " + path, null, 20_000, inv.cancel);
            if (!r.ok()) return null;
            long size;
            try {
                size = Long.parseLong(r.stdout.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            int chunk = 196_608;
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) size);
            for (long i = 0; i * chunk < size; i++) {
                ExecResult c = g.shizuku.run("dd if=" + path + " bs=" + chunk + " skip=" + i + " count=1 2>/dev/null | base64", null, 20_000, inv.cancel);
                if (!c.ok()) return null;
                byte[] part = Base64.decode(c.stdout.replaceAll("\\s", ""), Base64.DEFAULT);
                bos.write(part, 0, part.length);
            }
            g.shizuku.run("rm -f " + path, null, 5000, inv.cancel);
            byte[] png = bos.toByteArray();
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class ViewImage extends BaseTool {
        public ViewImage(AppGraph g) {
            super(g, ToolSpec.builder("view_image")
                    .description("Load an image from the workspace or a granted folder (ext:Folder/...) so you can look at it.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1},"
                            + "\"max_side\":{\"type\":\"integer\",\"minimum\":256,\"maximum\":2048,\"default\":1280}},\"required\":[\"path\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.READ_ONLY).timeoutMs(30_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            FileAccess fa = new FileAccess(g.workspace, g.saf);
            String path = inv.str("path");
            FileAccess.Entry e = fa.stat(path);
            if (e.dir) return ToolResult.fail(path + " is a folder");
            Bitmap b;
            try (InputStream in = fa.open(path)) {
                b = ImageUtil.decodeStream(in, (int) inv.num("max_side", 1280));
            }
            byte[] jpeg = ImageUtil.jpeg(b, 80);
            return ToolResult.okWithImage("Loaded " + e.name, J.obj("path", e.path, "width", b.getWidth(), "height", b.getHeight(), "bytes", e.size),
                    ImageUtil.toImage(jpeg, "high"));
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class Camera extends BaseTool {
        public Camera(AppGraph g) {
            super(g, ToolSpec.builder("camera_capture")
                    .description("Take a photo with the front or rear camera while Your Friend is open on screen. "
                            + "The photo is saved in the workspace and attached for you to look at.")
                    .schema("{\"type\":\"object\",\"properties\":{\"facing\":{\"type\":\"string\",\"enum\":[\"front\",\"back\"],\"default\":\"back\"},"
                            + "\"max_side\":{\"type\":\"integer\",\"minimum\":320,\"maximum\":2048,\"default\":1280}},\"additionalProperties\":false}")
                    .requires(Capability.CAMERA).risk(RiskLevel.MEDIUM).backend("camera2").timeoutMs(30_000).build());
        }

        @Override
        public String describeCall(JSONObject args) {
            return "Take a photo with the " + args.optString("facing", "back") + " camera";
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            if (!App.isInForeground()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, "Open Your Friend on screen first: Android blocks camera use from the background.");
            boolean front = "front".equals(inv.str("facing", "back"));
            byte[] jpeg = new CameraCapture(g.ctx).capture(front, 15_000);
            File f = ImageUtil.save(jpeg, g.workspace.dir("camera"), front ? "selfie" : "photo", "jpg");
            Bitmap b = ImageUtil.decodeFile(f, (int) inv.num("max_side", 1280));
            byte[] small = ImageUtil.jpeg(b, 80);
            return ToolResult.okWithImage("Photo taken with the " + (front ? "front" : "rear") + " camera",
                    J.obj("path", g.workspace.relative(f), "width", b.getWidth(), "height", b.getHeight()), ImageUtil.toImage(small, "high"));
        }
    }

    /** Unused helper kept small: JSON array of strings. */
    static JSONArray strings(String... s) {
        JSONArray a = new JSONArray();
        for (String x : s) a.put(x);
        return a;
    }
}
