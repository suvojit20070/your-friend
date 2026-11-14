package com.suvo.agent.device.lock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.suvo.agent.AppGraph;
import com.suvo.agent.data.Settings;
import com.suvo.agent.device.accessibility.AgentAccessibilityService;
import com.suvo.agent.device.shizuku.ShizukuManager;
import com.suvo.agent.terminal.ExecResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Locks and unlocks the phone for the agent (phone lying on the table, "hey aiko, call papa").
 * Unlocking wakes the screen and swipes up; then:
 * <ul>
 *   <li>no lock / swipe only — done;</li>
 *   <li>a saved PIN / password ({@link UnlockSecret}) — typed (Shizuku "input text", or the
 *       keypad's buttons through Accessibility);</li>
 *   <li>a saved pattern — drawn over the lock screen's 3×3 grid (found through Accessibility);</li>
 *   <li>nothing saved — the unlock screen is shown and the user unlocks (face, finger, PIN).</li>
 * </ul>
 * Two wrong tries in a row switch the saved unlock off, so the phone never gets locked out.
 * After a restart Android always asks for the PIN itself: no app can unlock then.
 */
public final class ScreenUnlocker {

    private ScreenUnlocker() {
    }

    public static final class Result {
        public final boolean unlocked;
        public final String message;

        Result(boolean unlocked, String message) {
            this.unlocked = unlocked;
            this.message = message;
        }
    }

    public static boolean isLocked(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private static boolean isSecure(Context ctx) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    // ---- Lock ------------------------------------------------------------------------------------

    public static Result lock(Context ctx) {
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        if (svc != null && android.os.Build.VERSION.SDK_INT >= 28 && svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            return new Result(false, "Locked.");
        }
        if (shizukuReady() && shell("input keyevent KEYCODE_SLEEP")) return new Result(false, "Locked.");
        return new Result(false, "Cannot lock: turn on the Accessibility service (or Shizuku).");
    }

    // ---- Unlock ----------------------------------------------------------------------------------

    /** Runs on a worker thread (it waits for the lock screen). */
    public static Result unlock(Context ctx) throws InterruptedException {
        Settings st = Settings.get(ctx);
        wake(ctx);
        Thread.sleep(500);
        if (!isLocked(ctx)) return new Result(true, "The phone is already unlocked.");
        // Up from the lock screen: the keypad / pattern (or, without a secure lock, the home screen).
        swipeUp(ctx);
        Thread.sleep(900);
        if (!isLocked(ctx)) return new Result(true, "Unlocked (no secure lock).");
        if (!isSecure(ctx)) {
            if (shizukuReady()) shell("wm dismiss-keyguard");
            Thread.sleep(600);
            if (!isLocked(ctx)) return new Result(true, "Unlocked.");
        }
        String method = UnlockSecret.method(ctx);
        if ("none".equals(method)) {
            return new Result(false, "The unlock screen is showing: the user has to unlock (face, fingerprint, PIN or pattern). "
                    + "No unlock is saved in the app (Settings → Screen control → Unlock).");
        }
        if (st.bool(UnlockSecret.ONLY_CHARGING, false) && !charging(ctx)) {
            return new Result(false, "Saved unlock only works while charging (the user's setting); the unlock screen is showing.");
        }
        if (st.integer(UnlockSecret.FAILS, 0) >= 2) {
            return new Result(false, "Saved unlock is paused after wrong tries; the user must unlock once and save it again.");
        }
        String secret = UnlockSecret.read(ctx);
        if (secret == null || secret.isEmpty()) return new Result(false, "The saved unlock can't be read any more; save it again.");
        boolean sent = "pattern".equals(method) ? drawPattern(ctx, secret) : typePin(ctx, secret);
        secret = null;
        if (!sent) return new Result(false, "Couldn't reach the lock screen's " + ("pattern".equals(method) ? "pattern grid" : "keypad")
                + " (needs Accessibility or Shizuku). The unlock screen is showing.");
        for (int i = 0; i < 8 && isLocked(ctx); i++) Thread.sleep(250);
        if (!isLocked(ctx)) {
            st.put(UnlockSecret.FAILS, 0);
            return new Result(true, "Unlocked.");
        }
        st.put(UnlockSecret.FAILS, st.integer(UnlockSecret.FAILS, 0) + 1);
        return new Result(false, "The saved " + method + " did not unlock the phone (wrong, or the lock screen changed).");
    }

    private static void wake(Context ctx) {
        try {
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) {
                //noinspection deprecation
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "YourFriend:unlock");
                wl.acquire(5000);
            }
        } catch (RuntimeException ignored) {
        }
        if (shizukuReady()) shell("input keyevent KEYCODE_WAKEUP");
    }

    private static void swipeUp(Context ctx) {
        DisplayMetrics dm = metrics(ctx);
        int x = dm.widthPixels / 2, y1 = (int) (dm.heightPixels * 0.82f), y2 = (int) (dm.heightPixels * 0.25f);
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        if (svc != null) {
            Path p = new Path();
            p.moveTo(x, y1);
            p.lineTo(x, y2);
            if (svc.dispatchAndWait(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build(), 2000)) {
                return;
            }
        }
        if (shizukuReady()) shell("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 220");
    }

    // ---- PIN / password ----------------------------------------------------------------------------

    private static boolean typePin(Context ctx, String pin) throws InterruptedException {
        // Shizuku types like a keyboard (works for PINs and ASCII passwords).
        if (shizukuReady() && pin.matches("[\\x21-\\x7E]+")) {
            if (shell("input text " + ShizukuManager.shellQuote(pin)) && shell("input keyevent KEYCODE_ENTER")) return true;
        }
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        if (svc == null) return false;
        AccessibilityNodeInfo root = keyguardRoot(svc);
        if (root == null) return false;
        // A password field: set the text and press Enter.
        AccessibilityNodeInfo field = findEditable(root);
        if (field != null && !pin.matches("\\d+")) {
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin);
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            clickEnter(root);
            return ok;
        }
        // The PIN pad: press each digit's button, then Enter / OK.
        for (char c : pin.toCharArray()) {
            AccessibilityNodeInfo key = findKey(root, String.valueOf(c));
            if (key == null) return false;
            if (!clickNode(svc, key)) return false;
            Thread.sleep(70);
        }
        Thread.sleep(150);
        clickEnter(keyguardRoot(svc));
        return true;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() || n.isPassword()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** A keypad button: its text (or description) is just the digit, or starts with it ("2 ABC"). */
    private static AccessibilityNodeInfo findKey(AccessibilityNodeInfo n, String digit) {
        if (n == null) return null;
        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
        if (t != null) {
            String s = t.toString().trim();
            if ((s.equals(digit) || s.startsWith(digit + " ") || s.startsWith(digit + ",")) && (n.isClickable() || n.getParent() != null)) {
                return n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findKey(n.getChild(i), digit);
            if (r != null) return r;
        }
        return null;
    }

    private static void clickEnter(AccessibilityNodeInfo root) {
        if (root == null) return;
        String[] names = {"enter", "ok", "done", "confirm", "unlock"};
        AccessibilityNodeInfo hit = findByWords(root, names);
        if (hit != null) {
            AgentAccessibilityService svc = AgentAccessibilityService.get();
            if (svc != null) clickNode(svc, hit);
        }
    }

    private static AccessibilityNodeInfo findByWords(AccessibilityNodeInfo n, String[] words) {
        if (n == null) return null;
        CharSequence t = n.getContentDescription() != null ? n.getContentDescription() : n.getText();
        if (t != null) {
            String s = t.toString().trim().toLowerCase(java.util.Locale.ROOT);
            for (String w : words) if (s.equals(w)) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByWords(n.getChild(i), words);
            if (r != null) return r;
        }
        return null;
    }

    private static boolean clickNode(AgentAccessibilityService svc, AccessibilityNodeInfo node) {
        AccessibilityNodeInfo c = node;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return svc.dispatchAndWait(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), 1500);
    }

    // ---- Pattern ---------------------------------------------------------------------------------

    /** Draws "1-5-9-6" (dots numbered 1..9, row by row) over the lock screen's pattern grid. */
    private static boolean drawPattern(Context ctx, String order) throws InterruptedException {
        List<Integer> dots = new ArrayList<>();
        for (String s : order.split("[^0-9]+")) {
            if (s.isEmpty()) continue;
            int d = Integer.parseInt(s);
            if (d < 1 || d > 9) return false;
            dots.add(d);
        }
        if (dots.size() < 2) return false;
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        Rect grid = svc == null ? null : patternBounds(keyguardRoot(svc));
        if (grid == null || grid.isEmpty()) return false;
        float cw = grid.width() / 3f, ch = grid.height() / 3f;
        float[][] pts = new float[dots.size()][2];
        for (int i = 0; i < dots.size(); i++) {
            int d = dots.get(i) - 1;
            pts[i][0] = grid.left + cw * (d % 3) + cw / 2f;
            pts[i][1] = grid.top + ch * (d / 3) + ch / 2f;
        }
        Path p = new Path();
        p.moveTo(pts[0][0], pts[0][1]);
        for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0], pts[i][1]);
        long dur = Math.min(GestureDescription.getMaxGestureDuration() - 50, 160L * pts.length + 200);
        if (svc.dispatchAndWait(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, dur)).build(), dur + 2000)) {
            return true;
        }
        // Shizuku: the same line as touch events.
        if (!shizukuReady()) return false;
        StringBuilder cmd = new StringBuilder("input motionevent DOWN " + (int) pts[0][0] + " " + (int) pts[0][1]);
        for (int i = 1; i < pts.length; i++) {
            float x0 = pts[i - 1][0], y0 = pts[i - 1][1], x1 = pts[i][0], y1 = pts[i][1];
            for (int k = 1; k <= 6; k++) {
                cmd.append(" && input motionevent MOVE ").append((int) (x0 + (x1 - x0) * k / 6f)).append(' ').append((int) (y0 + (y1 - y0) * k / 6f));
            }
        }
        cmd.append(" && input motionevent UP ").append((int) pts[pts.length - 1][0]).append(' ').append((int) pts[pts.length - 1][1]);
        return shell(cmd.toString());
    }

    /** The lock screen's pattern view (Android's LockPatternView): its 3×3 dots fill its bounds. */
    private static Rect patternBounds(AccessibilityNodeInfo n) {
        if (n == null) return null;
        CharSequence cls = n.getClassName();
        String id = n.getViewIdResourceName();
        if ((cls != null && cls.toString().endsWith("LockPatternView")) || (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("lockpattern"))) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            return r;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            Rect r = patternBounds(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    /** The lock screen's window (System UI keyguard), else the active window. */
    private static AccessibilityNodeInfo keyguardRoot(AgentAccessibilityService svc) {
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindows();
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && r.getPackageName().toString().equals("com.android.systemui")) return r;
            }
        } catch (RuntimeException ignored) {
        }
        return svc.root();
    }

    private static boolean charging(Context ctx) {
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    private static DisplayMetrics metrics(Context ctx) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private static boolean shizukuReady() {
        return AppGraph.get().shizuku.state() == ShizukuManager.State.READY;
    }

    private static boolean shell(String cmd) {
        try {
            ExecResult r = AppGraph.get().shizuku.run(cmd, null, 15_000, null);
            return r != null && r.exitCode == 0 && !r.timedOut;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
