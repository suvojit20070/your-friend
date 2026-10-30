package com.suvo.agent.character;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.suvo.agent.device.accessibility.AgentAccessibilityService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The visible elements of whatever is on screen (other apps only), read through the accessibility
 * service: where the floating character can stand or sit, what it can look at, and a short screen
 * tree for its AI "brain". Empty when the accessibility service is off.
 *
 * Safe on any thread (accessibility calls are blocking, so the periodic checks run off the main
 * thread).
 */
final class ScreenSurfaces {

    private ScreenSurfaces() {
    }

    static final class Item {
        final Rect r;
        final String label;
        final String type;
        final boolean clickable, editable, scrollable, keyboard;

        Item(Rect r, String label, String type, boolean clickable, boolean editable, boolean scrollable, boolean keyboard) {
            this.r = r;
            this.label = label;
            this.type = type;
            this.clickable = clickable;
            this.editable = editable;
            this.scrollable = scrollable;
            this.keyboard = keyboard;
        }

        /** Something the character can stand or sit on (wide enough for its feet). */
        boolean canHold(int bodyW, int minH) {
            return r.width() >= bodyW * 0.6f && r.height() >= minH;
        }
    }

    static final class Snapshot {
        final String app;
        final List<Item> items;
        /** Changes when the screen's layout changes (another app, screen, scroll…). */
        final int signature;

        Snapshot(String app, List<Item> items, int signature) {
            this.app = app;
            this.items = items;
            this.signature = signature;
        }
    }

    /**
     * Reads the screen. {@code ownPackage}'s windows (the app itself, the floating character) are
     * left out. At most {@code maxVisit} nodes are visited.
     */
    static Snapshot scan(String ownPackage, int minSize, int maxVisit) {
        AgentAccessibilityService svc = AgentAccessibilityService.get();
        List<Item> out = new ArrayList<>();
        if (svc == null) return new Snapshot("", out, 0);
        String app = "";
        int sig = 17;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        Rect b = new Rect();
        try {
            app = svc.foregroundPackage();
            for (AccessibilityWindowInfo win : svc.getWindows()) {
                int type = win.getType();
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo r = win.getRoot();
                if (r == null) continue;
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    // The keyboard: only its top edge matters (and that it is there).
                    win.getBoundsInScreen(b);
                    if (!b.isEmpty()) {
                        out.add(new Item(new Rect(b), "keyboard", "Keyboard", false, false, false, true));
                        sig = sig * 31 + b.top;
                    }
                    continue;
                }
                q.add(r);
            }
        } catch (RuntimeException ignored) {
        }
        int visited = 0;
        while (!q.isEmpty() && visited < maxVisit) {
            AccessibilityNodeInfo nd = q.poll();
            visited++;
            if (nd == null) continue;
            try {
                CharSequence pkg = nd.getPackageName();
                if (pkg != null && ownPackage.contentEquals(pkg)) continue;
                if (!nd.isVisibleToUser()) continue;
                nd.getBoundsInScreen(b);
                if (b.width() >= minSize && b.height() >= minSize / 2) {
                    String label = label(nd);
                    out.add(new Item(new Rect(b), label, type(nd), nd.isClickable(), nd.isEditable(), nd.isScrollable(), false));
                    if (b.width() * b.height() > minSize * minSize * 4) {
                        sig = sig * 31 + ((b.left >> 3) * 7 + (b.top >> 3) * 13 + (b.right >> 3) * 17 + (b.bottom >> 3) * 19);
                    }
                }
                for (int i = 0; i < nd.getChildCount(); i++) {
                    AccessibilityNodeInfo c = nd.getChild(i);
                    if (c != null) q.add(c);
                }
            } catch (RuntimeException ignored) {
                // The node went away while reading it.
            }
        }
        return new Snapshot(app == null ? "" : app, out, sig * 31 + (app == null ? 0 : app.hashCode()));
    }

    private static String label(AccessibilityNodeInfo n) {
        if (n.isPassword()) return "(password field)";
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if ((t == null || t.length() == 0) && n.getHintText() != null) t = n.getHintText();
        String s = t == null ? "" : t.toString().replace('\n', ' ').trim();
        if (s.isEmpty()) {
            String id = n.getViewIdResourceName();
            if (id != null) {
                int slash = id.lastIndexOf('/');
                s = "#" + (slash >= 0 ? id.substring(slash + 1) : id);
            }
        }
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    private static String type(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) return "View";
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
