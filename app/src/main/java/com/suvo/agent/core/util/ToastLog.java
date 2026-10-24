package com.suvo.agent.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Short-lived, in-memory record of the toasts, snackbars and spoken status messages that apps
 * show while the agent works on the screen. A toast never appears in the window tree the agent
 * reads, so without this the agent would not know that, say, "Message sent" or "No internet"
 * popped up after its tap. Screen tools attach entries that appeared during (or since) their action
 * to their result.
 *
 * Nothing is written to disk or to logs; entries expire after two minutes and the text is capped.
 * Pure Java so it can be unit-tested.
 */
public final class ToastLog {

    public static final String TOAST = "toast";
    public static final String SNACKBAR = "snackbar";
    public static final String ANNOUNCEMENT = "announcement";

    public static final class Entry {
        public final long time;
        public final String kind;
        public final String pkg;
        public final String text;
        boolean reported;

        Entry(long time, String kind, String pkg, String text) {
            this.time = time;
            this.kind = kind;
            this.pkg = pkg == null ? "" : pkg;
            this.text = text;
        }
    }

    static final int MAX = 24;
    static final long KEEP_MS = 120_000L;
    static final int MAX_TEXT = 300;

    private static final List<Entry> entries = new ArrayList<>();

    private ToastLog() {
    }

    /** Records a message; repeats of the same text within 4 s (the system often sends several events) are dropped. */
    public static synchronized void record(String kind, String pkg, CharSequence text, long now) {
        if (text == null) return;
        String t = text.toString().replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return;
        if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT) + "…";
        prune(now);
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (now - e.time > 4000) break;
            if (e.text.equals(t)) return;
        }
        entries.add(new Entry(now, kind, pkg, t));
        while (entries.size() > MAX) entries.remove(0);
    }

    /** Entries from {@code from} on that no tool result has mentioned yet; marks them as reported. */
    public static synchronized List<Entry> takeUnreported(long from, long now) {
        prune(now);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.reported && e.time >= from) {
                e.reported = true;
                out.add(e);
            }
        }
        return out;
    }

    /** Recent entries (reported or not), newest last. */
    public static synchronized List<Entry> recent(long sinceMs, long now) {
        prune(now);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (now - e.time <= sinceMs) out.add(e);
        return out;
    }

    public static synchronized void clear() {
        entries.clear();
    }

    /**
     * Text added to a tool result, e.g.
     * {@code After this action the screen showed: toast "Message sent" (com.whatsapp, 0.4 s after the action started)}.
     *
     * @param actionStart when the tool started; entries before it are described as "before this action".
     */
    public static String describe(List<Entry> list, long actionStart) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("After this action the screen showed ");
        sb.append(list.size() == 1 ? "this message" : list.size() + " messages").append(" (toasts are not in the screen tree):");
        for (Entry e : list) {
            sb.append("\n- ").append(e.kind).append(" \"").append(e.text.replace("\"", "'")).append('"');
            if (!e.pkg.isEmpty()) sb.append(" from ").append(e.pkg);
            long d = e.time - actionStart;
            if (d >= 0) sb.append(String.format(java.util.Locale.ROOT, ", %.1f s after the action started", d / 1000f));
            else sb.append(String.format(java.util.Locale.ROOT, ", %.1f s before this action (after an earlier one)", -d / 1000f));
        }
        return sb.toString();
    }

    private static void prune(long now) {
        while (!entries.isEmpty() && now - entries.get(0).time > KEEP_MS) entries.remove(0);
    }
}
