package com.suvo.agent.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Your Friend — Original project by Suvo.
 *
 * Live feed of commands the agent runs (shell, code runners, device admin). The Terminal screen
 * subscribes to it, so you can watch the AI working in a terminal in real time, and it keeps the
 * most recent runs so opening the screen later still shows what happened.
 */
public final class TerminalFeed {

    public static final class Run {
        public final long id;
        public final long startedAt;
        public final String source;   // tool name, e.g. "shell", "execute_python"
        public final String backend;  // local | termux | shizuku | ssh:host
        public final String command;
        public volatile String output = "";
        public volatile int exitCode = Integer.MIN_VALUE;
        public volatile boolean finished;
        public volatile String error = "";

        Run(long id, String source, String backend, String command) {
            this.id = id;
            this.startedAt = System.currentTimeMillis();
            this.source = source;
            this.backend = backend;
            this.command = command;
        }
    }

    public interface Listener {
        void onStarted(Run run);

        void onFinished(Run run);
    }

    private static final int KEEP = 40;
    private static final int MAX_OUTPUT = 20_000;
    private static final AtomicLong IDS = new AtomicLong();
    private static final List<Run> RECENT = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private TerminalFeed() {
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static synchronized List<Run> recent() {
        return new ArrayList<>(RECENT);
    }

    public static Run start(String source, String backend, String command) {
        Run r = new Run(IDS.incrementAndGet(), source, backend, command == null ? "" : command);
        synchronized (TerminalFeed.class) {
            RECENT.add(r);
            while (RECENT.size() > KEEP) RECENT.remove(0);
        }
        for (Listener l : LISTENERS) {
            try {
                l.onStarted(r);
            } catch (RuntimeException ignored) {
            }
        }
        return r;
    }

    public static void finish(Run r, ExecResult res) {
        if (r == null) return;
        StringBuilder sb = new StringBuilder();
        if (res != null) {
            if (res.stdout != null) sb.append(res.stdout);
            if (res.stderr != null && !res.stderr.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append(res.stderr);
            }
            r.exitCode = res.exitCode;
            if (res.error != null && !res.error.isEmpty()) r.error = res.error;
            if (res.timedOut) r.error = "timed out";
            if (res.cancelled) r.error = "stopped";
        }
        finish(r, sb.toString(), r.exitCode, r.error);
    }

    public static void finish(Run r, String output, int exitCode, String error) {
        if (r == null) return;
        String out = output == null ? "" : output;
        if (out.length() > MAX_OUTPUT) out = "…" + out.substring(out.length() - MAX_OUTPUT);
        r.output = out;
        r.exitCode = exitCode;
        r.error = error == null ? "" : error;
        r.finished = true;
        for (Listener l : LISTENERS) {
            try {
                l.onFinished(r);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
