package com.suvo.agent.apkscan;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

/**
 * Your Friend — Original project by Suvo.
 *
 * The APK decompiler: jadx (github.com/skylot/jadx, Apache-2.0) turns an app's dex code back into
 * Java. Loading an app reads all of its classes (seconds, and memory for big apps); turning a class
 * into Java happens only for the classes asked for. The last opened app stays loaded for a few
 * minutes, so several questions about the same app (the agent's apk_decompile calls) are quick.
 */
public final class Decompiler implements AutoCloseable {

    private static Decompiler last;
    private static long lastUsed;
    /** Callers using {@link #last} right now: it is never closed under them. */
    private static int users;

    private final String key;
    private final JadxDecompiler jadx;
    private final Map<String, JavaClass> classes = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    private Decompiler(File apk, File work) {
        key = apk.getAbsolutePath() + ":" + apk.lastModified() + ":" + apk.length();
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk);
        args.setOutDir(work);
        args.setSkipResources(true); // the manifest is read through Android itself
        args.setThreadsCount(2);
        args.setShowInconsistentCode(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        for (JavaClass c : jadx.getClasses()) {
            String n = c.getFullName();
            classes.put(n, c);
            names.add(n);
        }
    }

    /**
     * The decompiler for {@code apk} (the one already loaded when it is the same file). Slow the
     * first time: never on the main thread. Every open() needs a {@link #release()} when done —
     * the loaded app is never closed while someone still uses it (a scan and the agent's tool at
     * the same time used to close it under each other).
     */
    public static synchronized Decompiler open(File apk, File work) {
        String k = apk.getAbsolutePath() + ":" + apk.lastModified() + ":" + apk.length();
        if (last != null && last.key.equals(k)) {
            lastUsed = System.currentTimeMillis();
            users++;
            return last;
        }
        // Another app is loaded: wait (up to 5 minutes) until nobody uses it, then replace it.
        long until = System.currentTimeMillis() + 300_000;
        while (users > 0 && System.currentTimeMillis() < until) {
            try {
                Decompiler.class.wait(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
        if (last != null) {
            last.closeNow();
            last = null;
        }
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        // jadx keeps its config / cache / temp files under the user's home and temp folders; on
        // Android those are not set (or not writable): point them into the app's own folder.
        String home = System.getProperty("user.home", "");
        if (home.isEmpty() || "/".equals(home) || !new File(home).canWrite()) System.setProperty("user.home", work.getAbsolutePath());
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (tmp.isEmpty() || !new File(tmp).canWrite()) System.setProperty("java.io.tmpdir", work.getAbsolutePath());
        try {
            last = new Decompiler(apk, work);
        } catch (OutOfMemoryError oom) {
            // A big app: free what can be freed and try once more.
            last = null;
            System.gc();
            last = new Decompiler(apk, work);
        }
        lastUsed = System.currentTimeMillis();
        users = 1;
        return last;
    }

    /** Done with the decompiler from open(). */
    public static synchronized void release() {
        if (users > 0) users--;
        lastUsed = System.currentTimeMillis();
        Decompiler.class.notifyAll();
    }

    /** Frees the loaded app when nobody uses it and it was not used for a while (memory). */
    public static synchronized void trim(long idleMs) {
        if (last != null && users == 0 && System.currentTimeMillis() - lastUsed > idleMs) {
            last.closeNow();
            last = null;
        }
    }

    /** Every top-level class name ("com.example.Foo"). */
    public List<String> classNames() {
        return names;
    }

    public boolean has(String className) {
        return classes.containsKey(className);
    }

    /** The class as Java source, or null when there is no such class. */
    public synchronized String source(String className) {
        JavaClass c = classes.get(className);
        if (c == null) {
            // An inner class ("Outer$Inner"): its outer class holds the code.
            int d = className.indexOf('$');
            if (d > 0) c = classes.get(className.substring(0, d));
        }
        if (c == null) return null;
        try {
            return c.getCode();
        } catch (Throwable t) {
            return "// could not decompile " + className + ": " + t;
        }
    }

    private void closeNow() {
        try {
            jadx.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        // Kept open for the next question; trim() frees it.
    }
}
