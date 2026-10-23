package com.suvo.agent.device.accessibility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Your Friend — Original project by Suvo.
 *
 * A short memory of what the agent saw and did on screen: the last few screens (app, activity and
 * their main texts) and the action that led away from each. It is attached to every screen read, so
 * the model knows where it came from and what it already tapped, without keeping old element lists
 * in its context. Only the newest {@link #MAX} entries are kept.
 */
public final class ScreenHistory {

    public static final int MAX = 5;

    private static final class Entry {
        final long time;
        final String pkg;
        final String activity;
        final String summary;
        String action = "";

        Entry(long time, String pkg, String activity, String summary) {
            this.time = time;
            this.pkg = pkg;
            this.activity = activity;
            this.summary = summary;
        }
    }

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private ScreenHistory() {
    }

    /** Records a screen read. Consecutive reads of the same screen update one entry. */
    public static synchronized void recordScreen(JSONObject screen) {
        String pkg = screen.optString("foreground_package", "");
        String act = screen.optString("foreground_activity", "");
        String summary = summarize(screen.optJSONArray("elements"));
        Entry last = ENTRIES.peekLast();
        if (last != null && last.pkg.equals(pkg) && last.activity.equals(act) && last.action.isEmpty()) {
            ENTRIES.pollLast();
        }
        ENTRIES.addLast(new Entry(System.currentTimeMillis(), pkg, act, summary));
        while (ENTRIES.size() > MAX) ENTRIES.pollFirst();
    }

    /** Records what the agent did on the newest screen (tap e12 "Send", typed into e4, back...). */
    public static synchronized void recordAction(String action) {
        Entry last = ENTRIES.peekLast();
        if (last == null) {
            last = new Entry(System.currentTimeMillis(), "", "", "");
            ENTRIES.addLast(last);
        }
        last.action = last.action.isEmpty() ? action : last.action + "; " + action;
        if (last.action.length() > 300) last.action = last.action.substring(last.action.length() - 300);
    }

    /** The previous screens (oldest first), excluding the current one. */
    public static synchronized JSONArray previous() {
        JSONArray a = new JSONArray();
        int i = 0;
        int n = ENTRIES.size();
        long now = System.currentTimeMillis();
        for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext(); i++) {
            Entry e = it.next();
            if (i == n - 1 && e.action.isEmpty()) break; // that is the screen being shown now
            try {
                JSONObject o = new JSONObject();
                o.put("seconds_ago", (now - e.time) / 1000);
                if (!e.pkg.isEmpty()) o.put("app", e.pkg);
                if (!e.activity.isEmpty()) o.put("activity", e.activity.substring(e.activity.lastIndexOf('.') + 1));
                if (!e.summary.isEmpty()) o.put("showed", e.summary);
                if (!e.action.isEmpty()) o.put("did", e.action);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        return a;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** The first few short texts of a screen, e.g. "Chats | Search | Riya: see you | Settings". */
    private static String summarize(JSONArray els) {
        if (els == null) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < els.length() && count < 6; i++) {
            JSONObject e = els.optJSONObject(i);
            if (e == null) continue;
            String t = e.optString("text", e.optString("desc", ""));
            if (t.isEmpty() || t.equals("••••")) continue;
            if (t.length() > 30) t = t.substring(0, 30) + "…";
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
            count++;
        }
        return sb.toString();
    }
}
