package com.suvo.agent.terminal;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.suvo.agent.core.util.CancellationToken;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs commands inside Termux (a separate app providing a Linux userland with pkg/apt, Python,
 * clang, Node.js, OpenJDK, git...) through Termux's documented RUN_COMMAND intent API.
 * Requirements: Termux installed from F-Droid/GitHub, "allow-external-apps = true" in
 * ~/.termux/termux.properties, and the com.termux.permission.RUN_COMMAND permission granted.
 */
public final class TermuxBackend implements ShellBackend {

    public static final String PACKAGE = "com.termux";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String SERVICE = "com.termux.app.RunCommandService";
    static final String ACTION = "com.termux.RUN_COMMAND";
    static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    static final String RESULT_BUNDLE = "result";

    public static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String HOME = "/data/data/com.termux/files/home";

    static final String EXTRA_EXEC_ID = "com.suvo.agent.EXEC_ID";
    private static final AtomicInteger NEXT_ID = new AtomicInteger((int) (System.currentTimeMillis() & 0xFFFF));
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    /** Folder in Termux where every command keeps its live log, pid and exit code. */
    public static final String JOBS_DIR = HOME + "/.yourfriend/jobs";
    /** Finished background jobs are remembered this long. */
    private static final long KEEP_FINISHED_MS = 6 * 60 * 60_000L;

    static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Bundle result;
        volatile boolean done;
    }

    /** Set once Termux has sent back any result: it is set up and answering. */
    private static volatile boolean answered;

    /**
     * A command that was still running when the caller stopped waiting. Termux keeps running it
     * and sends its result to our receiver when it ends — it is kept here instead of being lost
     * (before, a long build or "pkg install" simply came back as "timed out").
     */
    public static final class Job {
        public final String id;
        public final int execId;
        public final String command;
        public final long startedAt;
        final Pending pending;
        public volatile long finishedAt;
        volatile TerminalFeed.Run feed;

        Job(int execId, String command, long startedAt, Pending pending) {
            this.id = "t" + execId;
            this.execId = execId;
            this.command = command;
            this.startedAt = startedAt;
            this.pending = pending;
        }

        public boolean finished() {
            return pending.done;
        }

        /** Live output so far (stdout + stderr, merged) is in this file inside Termux. */
        public String logPath() {
            return JOBS_DIR + "/" + execId + ".log";
        }
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private final Context ctx;

    public TermuxBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public String id() {
        return "termux";
    }

    public boolean isInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Termux's versionCode; the Play Store build (0.101) is too old for RUN_COMMAND results. */
    public long versionCode() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(PACKAGE, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** True when the installed Termux declares the RUN_COMMAND permission at all. */
    public boolean permissionDefined() {
        try {
            ctx.getPackageManager().getPermissionInfo(PERMISSION, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public String unavailableReason() {
        if (!isInstalled()) return "Termux is not installed (install it from F-Droid or GitHub, not the Play Store)";
        long vc = versionCode();
        if (vc >= 0 && vc < 108) {
            return "This Termux is too old (Play Store build). Uninstall it and install Termux from F-Droid or GitHub";
        }
        if (!permissionDefined()) {
            return "Termux does not offer the RUN_COMMAND permission. Install Termux from F-Droid or GitHub, then reinstall Your Friend";
        }
        if (!hasPermission()) {
            return "Your Friend is not allowed to run commands in Termux yet. Grant it in Your Friend > Permissions > Termux "
                    + "(or App info > Permissions > Additional permissions). If no option appears, reinstall Your Friend after Termux";
        }
        return null;
    }

    /** Explains the usual reasons Termux never answered. */
    static String noAnswerHelp() {
        return "Termux did not answer. Check: 1) open Termux once so it finishes setting up; "
                + "2) in Termux run: mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings ; "
                + "3) allow Termux to run in the background (Battery > Unrestricted); "
                + "4) Termux shows a notification with the exact error if it refused the command.";
    }

    /**
     * Runs the command and waits up to {@code timeoutMs}. If it is still running then, it is not
     * cut off: it continues in Termux as a background job ({@link ExecResult#jobId}), its output
     * keeps going to {@link Job#logPath()}, and its final result is kept when it arrives.
     * Cancelling (Stop) kills it in Termux.
     */
    @Override
    public ExecResult run(String command, String stdin, long timeoutMs, CancellationToken cancel) {
        String why = unavailableReason();
        if (why != null) return ExecResult.backendError(id(), why);
        long t0 = System.currentTimeMillis();
        int execId = NEXT_ID.incrementAndGet();
        Pending pending = new Pending();
        PENDING.put(execId, pending);
        boolean keep = false;
        try {
            String err = send(execId, wrap(execId, command), stdin);
            if (err != null) return ExecResult.backendError(id(), err);
            boolean got = await(pending, t0 + timeoutMs, cancel);
            if (!got) {
                if (cancel.isCancelled()) {
                    killAsync(execId);
                    return new ExecResult(id(), -1, "", "", false, true, System.currentTimeMillis() - t0, "");
                }
                // Still running: keep it as a background job instead of losing it.
                keep = true;
                Job job = new Job(execId, command, t0, pending);
                JOBS.put(job.id, job);
                pruneJobs();
                String msg = "Still running in Termux after " + Math.max(1, timeoutMs / 1000)
                        + " s — it keeps running in the background as job " + job.id + ". Its live output is in "
                        + job.logPath() + ". Check it with termux_job (action status / wait / output / kill).";
                // Termux never answered anything yet: it may not be set up (then nothing runs at all).
                if (!answered) msg += " If nothing ever comes back: " + noAnswerHelp();
                ExecResult r = new ExecResult(id(), -1, "", msg, true, false, System.currentTimeMillis() - t0, "");
                r.jobId = job.id;
                return r;
            }
            return toResult(pending.result, System.currentTimeMillis() - t0);
        } finally {
            if (!keep) PENDING.remove(execId);
        }
    }

    /** Waits for the result until {@code deadline} or cancel; true when it arrived. */
    private static boolean await(Pending pending, long deadline, CancellationToken cancel) {
        while (cancel == null || !cancel.isCancelled()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            try {
                if (pending.latch.await(Math.min(left, 250), TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * The command inside a small wrapper: output also goes to the job's log file (so a long run
     * can be watched while it works), and the pid and exit code are written next to it. Logs
     * older than two days are cleaned up.
     */
    static String wrap(int execId, String command) {
        String base = JOBS_DIR + "/" + execId;
        return "mkdir -p " + JOBS_DIR + " 2>/dev/null; find " + JOBS_DIR + " -type f -mtime +2 -delete 2>/dev/null; "
                + "echo $$ > " + base + ".pid; : > " + base + ".log; "
                + "trap 'echo $? > " + base + ".exit' EXIT; "
                + "exec > >(tee -a " + base + ".log) 2> >(tee -a " + base + ".log >&2)\n"
                + command;
    }

    /** Starts the command in Termux; returns an error message or null. */
    private String send(int execId, String script, String stdin) {
        Intent result = new Intent(ctx, TermuxResultReceiver.class);
        result.putExtra(EXTRA_EXEC_ID, execId);
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
        // Termux fills the result into this intent, so it must be mutable; it is explicit to our receiver.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, execId, result, flags);

        Intent i = new Intent(ACTION);
        i.setClassName(PACKAGE, SERVICE);
        i.putExtra(EXTRA_PATH, BASH);
        i.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", script});
        i.putExtra(EXTRA_WORKDIR, HOME);
        i.putExtra(EXTRA_BACKGROUND, true);
        i.putExtra(EXTRA_COMMAND_LABEL, "Your Friend");
        if (stdin != null && !stdin.isEmpty()) i.putExtra(EXTRA_STDIN, stdin);
        i.putExtra(EXTRA_PENDING_INTENT, pi);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
            return null;
        } catch (SecurityException e) {
            return "Termux refused the command: " + e.getMessage()
                    + ". Check that allow-external-apps=true is set in ~/.termux/termux.properties.";
        } catch (IllegalStateException e) {
            return "Android blocked starting Termux in the background: " + e.getMessage();
        }
    }

    private ExecResult toResult(Bundle b, long durationMs) {
        if (b == null) return ExecResult.backendError(id(), "Termux returned no result bundle");
        String errmsg = b.getString("errmsg", "");
        int err = b.getInt("err", -1);
        String stdout = b.getString("stdout", "");
        String stderr = b.getString("stderr", "");
        int exit = b.containsKey("exitCode") ? b.getInt("exitCode", -1) : -1;
        String backendErr = "";
        if (err != -1 && err != 0 && !errmsg.isEmpty() && exit == -1) backendErr = "Termux error " + err + ": " + errmsg;
        return new ExecResult(id(), exit, stdout, stderr.isEmpty() && !errmsg.isEmpty() && exit != 0 ? errmsg : stderr,
                false, false, durationMs, backendErr);
    }

    // ---- Background jobs ------------------------------------------------------------------------

    /** Known background jobs, newest first. */
    public static java.util.List<Job> jobs() {
        java.util.List<Job> out = new java.util.ArrayList<>(JOBS.values());
        out.sort((x, y) -> Long.compare(y.startedAt, x.startedAt));
        return out;
    }

    public static Job job(String id) {
        if (id == null) return null;
        String k = id.trim();
        if (!k.startsWith("t")) k = "t" + k;
        return JOBS.get(k);
    }

    /** The finished job's result, or null while it runs. */
    public ExecResult result(Job job) {
        if (!job.finished()) return null;
        Bundle b = job.pending.result;
        long end = job.finishedAt > 0 ? job.finishedAt : System.currentTimeMillis();
        return toResult(b, end - job.startedAt);
    }

    /** Waits up to {@code ms} for a background job to finish. True when it has. */
    public boolean waitFor(Job job, long ms, CancellationToken cancel) {
        if (job.finished()) return true;
        return await(job.pending, System.currentTimeMillis() + ms, cancel);
    }

    /** Shows the job's final output in the Terminal screen when it ends. */
    public static void attachFeed(String jobId, TerminalFeed.Run feed) {
        Job j = job(jobId);
        if (j == null || feed == null) return;
        j.feed = feed;
        if (j.finished()) finishFeed(j);
    }

    private static void finishFeed(Job j) {
        TerminalFeed.Run f = j.feed;
        if (f == null) return;
        j.feed = null;
        Bundle b = j.pending.result;
        String out = b == null ? "" : b.getString("stdout", "") + (b.getString("stderr", "").isEmpty() ? "" : "\n" + b.getString("stderr", ""));
        int exit = b != null && b.containsKey("exitCode") ? b.getInt("exitCode", -1) : -1;
        TerminalFeed.finish(f, out, exit, "");
    }

    /** Stops a job (and what it started) in Termux; does not wait. */
    public void killAsync(int execId) {
        String base = JOBS_DIR + "/" + execId;
        String script = "p=$(cat " + base + ".pid 2>/dev/null); [ -n \"$p\" ] || exit 0; "
                + "for c in $(pgrep -P $p 2>/dev/null); do pkill -TERM -P $c 2>/dev/null; kill -TERM $c 2>/dev/null; done; "
                + "kill -TERM $p 2>/dev/null; sleep 2; kill -KILL $p 2>/dev/null; echo 143 > " + base + ".exit; true";
        int id = NEXT_ID.incrementAndGet();
        send(id, script, null);
    }

    private static void pruneJobs() {
        long now = System.currentTimeMillis();
        for (Job j : JOBS.values()) {
            if (j.finished() && j.finishedAt > 0 && now - j.finishedAt > KEEP_FINISHED_MS) {
                JOBS.remove(j.id);
                PENDING.remove(j.execId);
            }
        }
    }

    /** Called by {@link TermuxResultReceiver}. */
    static void deliver(int execId, Bundle result) {
        answered = true;
        Pending p = PENDING.get(execId);
        if (p != null) {
            p.result = result;
            p.done = true;
            p.latch.countDown();
        }
        Job j = JOBS.get("t" + execId);
        if (j != null) {
            j.finishedAt = System.currentTimeMillis();
            PENDING.remove(execId);
            finishFeed(j);
        }
    }
}
