package com.suvo.agent.device.accessibility;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns the accessibility tree of the active window into a compact element list with short ids
 * ("e12"). The ids stay valid until the next snapshot, so the model can say "tap e12".
 */
public final class ScreenReader {

    public static final class Snapshot {
        public final JSONObject json;
        public final Map<String, AccessibilityNodeInfo> nodes;
        public final long time;

        Snapshot(JSONObject json, Map<String, AccessibilityNodeInfo> nodes) {
            this.json = json;
            this.nodes = nodes;
            this.time = System.currentTimeMillis();
        }
    }

    private static volatile Snapshot last;

    public static Snapshot last() {
        return last;
    }

    public static Snapshot read(AgentAccessibilityService svc, DisplayMetrics dm, int maxElements, boolean includeAll) throws JSONException {
        AccessibilityNodeInfo root = svc.root();
        JSONObject o = new JSONObject();
        o.put("foreground_package", svc.foregroundPackage());
        o.put("foreground_activity", svc.foregroundClass());
        o.put("screen", new JSONObject().put("width", dm.widthPixels).put("height", dm.heightPixels));
        Map<String, AccessibilityNodeInfo> map = new HashMap<>();
        JSONArray els = new JSONArray();
        if (root == null) {
            o.put("elements", els);
            o.put("note", "No readable window (lock screen, a secure app, or the screen is changing). Try again or take a screenshot.");
            Snapshot s = new Snapshot(o, map);
            last = s;
            return s;
        }
        if (root.getPackageName() != null) o.put("window_package", root.getPackageName().toString());
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        Deque<Integer> depthStack = new ArrayDeque<>();
        stack.push(root);
        depthStack.push(0);
        int n = 0;
        int skipped = 0;
        Rect r = new Rect();
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            int depth = depthStack.pop();
            if (node == null) continue;
            if (!node.isVisibleToUser()) continue;
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo ch = node.getChild(i);
                if (ch != null) {
                    stack.push(ch);
                    depthStack.push(depth + 1);
                }
            }
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence hint = android.os.Build.VERSION.SDK_INT >= 26 ? node.getHintText() : null;
            boolean interesting = node.isClickable() || node.isLongClickable() || node.isEditable() || node.isScrollable()
                    || node.isCheckable() || (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
            if (!interesting && !includeAll) continue;
            if (n >= maxElements) {
                skipped++;
                continue;
            }
            String id = "e" + (++n);
            map.put(id, node);
            node.getBoundsInScreen(r);
            JSONObject e = new JSONObject();
            e.put("id", id);
            if (text != null && text.length() > 0) {
                e.put("text", node.isPassword() ? "••••" : clip(text.toString(), 200));
            }
            if (desc != null && desc.length() > 0) e.put("desc", clip(desc.toString(), 160));
            if (hint != null && hint.length() > 0) e.put("hint", clip(hint.toString(), 80));
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String c = cls.toString();
                e.put("type", c.substring(c.lastIndexOf('.') + 1));
            }
            String res = node.getViewIdResourceName();
            if (res != null) e.put("res", res.contains("/") ? res.substring(res.indexOf('/') + 1) : res);
            e.put("bounds", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));
            StringBuilder f = new StringBuilder();
            if (node.isClickable()) f.append("click ");
            if (node.isLongClickable()) f.append("longclick ");
            if (node.isEditable()) f.append("edit ");
            if (node.isScrollable()) f.append("scroll ");
            if (node.isCheckable()) f.append(node.isChecked() ? "checked " : "unchecked ");
            if (node.isFocused()) f.append("focused ");
            if (node.isSelected()) f.append("selected ");
            if (!node.isEnabled()) f.append("disabled ");
            if (node.isPassword()) f.append("password ");
            if (f.length() > 0) e.put("flags", f.toString().trim());
            if (depth > 0) e.put("depth", depth);
            els.put(e);
        }
        o.put("elements", els);
        if (skipped > 0) o.put("omitted_elements", skipped);
        Snapshot s = new Snapshot(o, map);
        last = s;
        return s;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
