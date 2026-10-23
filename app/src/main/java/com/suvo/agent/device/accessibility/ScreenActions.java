package com.suvo.agent.device.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Accessibility actions with verified outcomes. Semantic targets (element ids, text) are preferred;
 * coordinates are bounds-checked against the display. Every method reports what Android confirmed.
 */
public final class ScreenActions {

    public static final class Outcome {
        public final boolean ok;
        public final String message;

        Outcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Outcome ok(String m) {
            return new Outcome(true, m);
        }

        static Outcome fail(String m) {
            return new Outcome(false, m);
        }
    }

    private final AgentAccessibilityService svc;
    private final DisplayMetrics dm;

    public ScreenActions(AgentAccessibilityService svc, DisplayMetrics dm) {
        this.svc = svc;
        this.dm = dm;
    }

    /** Finds a node by snapshot id ("e12") or by visible text/description. */
    public AccessibilityNodeInfo find(String elementId, String text) {
        if (elementId != null && !elementId.isEmpty()) {
            ScreenReader.Snapshot s = ScreenReader.last();
            if (s != null) {
                AccessibilityNodeInfo n = s.nodes.get(elementId);
                if (n != null && n.refresh()) return n;
            }
            return null;
        }
        if (text == null || text.isEmpty()) return null;
        AccessibilityNodeInfo root = svc.root();
        if (root == null) return null;
        String want = text.toLowerCase(Locale.ROOT).trim();
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        Deque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            AccessibilityNodeInfo n = st.pop();
            if (n == null || !n.isVisibleToUser()) continue;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
            int score = match(n.getText(), want) * 2;
            score = Math.max(score, match(n.getContentDescription(), want) * 2 - 1);
            if (score > 0 && (n.isClickable() || clickableAncestor(n) != null)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }
        return best;
    }

    private static int match(CharSequence cs, String want) {
        if (cs == null) return 0;
        String s = cs.toString().toLowerCase(Locale.ROOT).trim();
        if (s.equals(want)) return 3;
        if (s.startsWith(want)) return 2;
        if (s.contains(want)) return 1;
        return 0;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static String describe(AccessibilityNodeInfo n) {
        CharSequence t = n.getText() != null && n.getText().length() > 0 ? n.getText() : n.getContentDescription();
        String label = t == null ? "" : t.toString();
        if (label.length() > 60) label = label.substring(0, 60) + "…";
        CharSequence cls = n.getClassName();
        String c = cls == null ? "element" : cls.toString().substring(cls.toString().lastIndexOf('.') + 1);
        return label.isEmpty() ? c : c + " \"" + label + "\"";
    }

    public Outcome tap(AccessibilityNodeInfo node, boolean longPress) {
        if (node == null) return Outcome.fail("Element not found on the current screen. Read the screen again to get fresh ids.");
        if (!node.isEnabled()) return Outcome.fail("The element is disabled: " + describe(node));
        AccessibilityNodeInfo target = longPress ? (node.isLongClickable() ? node : null) : clickableAncestor(node);
        if (target != null) {
            boolean done = target.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            if (done) return Outcome.ok((longPress ? "Long-pressed " : "Tapped ") + describe(node));
        }
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return Outcome.fail("The element has no on-screen bounds: " + describe(node));
        Outcome g = gestureTap(r.centerX(), r.centerY(), longPress ? 700 : 60);
        return g.ok ? Outcome.ok((longPress ? "Long-pressed " : "Tapped ") + describe(node) + " via gesture") : g;
    }

    public Outcome gestureTap(int x, int y, long durationMs) {
        String bad = checkPoint(x, y);
        if (bad != null) return Outcome.fail(bad);
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, durationMs))).build();
        boolean ok = svc.dispatchAndWait(g, durationMs + 3000);
        return ok ? Outcome.ok("Gesture at (" + x + "," + y + ") completed") : Outcome.fail("Android did not complete the gesture at (" + x + "," + y + ")");
    }

    public Outcome swipe(int x1, int y1, int x2, int y2, long durationMs) {
        String bad = checkPoint(x1, y1);
        if (bad == null) bad = checkPoint(x2, y2);
        if (bad != null) return Outcome.fail(bad);
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        long d = Math.max(50, Math.min(5000, durationMs));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, d)).build();
        boolean ok = svc.dispatchAndWait(g, d + 3000);
        return ok ? Outcome.ok("Swiped from (" + x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")") : Outcome.fail("Android did not complete the swipe");
    }

    private String checkPoint(int x, int y) {
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
            return "Point (" + x + "," + y + ") is outside the screen (" + dm.widthPixels + "x" + dm.heightPixels + ")";
        }
        return null;
    }

    /** Sets text on the given editable node, or on the currently focused input field. */
    public Outcome type(AccessibilityNodeInfo node, String text, boolean append) {
        AccessibilityNodeInfo target = node;
        if (target == null) {
            AccessibilityNodeInfo root = svc.root();
            if (root != null) target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (target == null) return Outcome.fail("No input field is focused. Tap a text field first or pass its element id.");
        if (!target.isEditable()) return Outcome.fail("The target is not an editable field: " + describe(target));
        if (target.isPassword()) {
            return Outcome.fail("Typing into password fields is not allowed. Ask the user to enter the password themselves.");
        }
        String value = text;
        if (append && target.getText() != null) value = target.getText().toString() + text;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) return Outcome.fail("The app rejected the text input for " + describe(target));
        target.refresh();
        CharSequence now = target.getText();
        boolean verified = now != null && now.toString().contains(text.length() > 30 ? text.substring(0, 30) : text);
        return verified ? Outcome.ok("Typed text into " + describe(target))
                : Outcome.ok("Text was sent to " + describe(target) + " but the field does not show it yet (the app may format or hide input)");
    }

    public Outcome scroll(AccessibilityNodeInfo node, String direction) {
        AccessibilityNodeInfo target = node;
        if (target == null) target = firstScrollable(svc.root());
        String dir = direction == null ? "down" : direction.toLowerCase(Locale.ROOT);
        if (target != null) {
            boolean ok = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AccessibilityNodeInfo.AccessibilityAction a;
                switch (dir) {
                    case "up":
                        a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
                        break;
                    case "left":
                        a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
                        break;
                    case "right":
                        a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
                        break;
                    default:
                        a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
                }
                List<AccessibilityNodeInfo.AccessibilityAction> supported = target.getActionList();
                if (supported.contains(a)) ok = target.performAction(a.getId());
            }
            if (!ok) {
                boolean forward = dir.equals("down") || dir.equals("right");
                ok = target.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
            if (ok) return Outcome.ok("Scrolled " + dir + " in " + describe(target));
        }
        // Fall back to a swipe in the middle of the screen.
        int cx = dm.widthPixels / 2;
        int cy = dm.heightPixels / 2;
        int dy = dm.heightPixels / 3;
        int dx = dm.widthPixels / 3;
        switch (dir) {
            case "up":
                return swipe(cx, cy - dy / 2, cx, cy + dy, 350);
            case "left":
                return swipe(cx - dx / 2, cy, cx + dx, cy, 350);
            case "right":
                return swipe(cx + dx / 2, cy, cx - dx, cy, 350);
            default:
                return swipe(cx, cy + dy / 2, cx, cy - dy, 350);
        }
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isScrollable() && n.isVisibleToUser()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    public Outcome global(String action) {
        int code;
        switch (action) {
            case "back":
                code = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case "home":
                code = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case "recents":
                code = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                code = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quick_settings":
                code = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "lock_screen":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.fail("Lock screen action needs Android 9+");
                code = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default:
                return Outcome.fail("Unknown navigation action: " + action);
        }
        return svc.global(code) ? Outcome.ok("Performed " + action) : Outcome.fail("Android rejected the " + action + " action");
    }

    /** Waits until text appears on screen (or timeout). */
    public Outcome waitForText(String text, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo n = find(null, text);
            if (n != null) return Outcome.ok("\"" + text + "\" is visible: " + describe(n));
            Thread.sleep(400);
        }
        return Outcome.fail("\"" + text + "\" did not appear within " + timeoutMs / 1000 + " s");
    }
}
