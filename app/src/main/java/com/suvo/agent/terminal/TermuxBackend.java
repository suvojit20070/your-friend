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

    static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Bundle result;
    }

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

    @Override
    public ExecResult run(String command, String stdin, long timeoutMs, CancellationToken cancel) {
        String why = unavailableReason();
        if (why != null) return ExecResult.backendError(id(), why);
        long t0 = System.currentTimeMillis();
        int execId = NEXT_ID.incrementAndGet();
        Pending pending = new Pending();
        PENDING.put(execId, pending);
        try {
            Intent result = new Intent(ctx, TermuxResultReceiver.class);
            result.putExtra(EXTRA_EXEC_ID, execId);
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT;
            // Termux fills the result into this intent, so it must be mutable; it is explicit to our receiver.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, execId, result, flags);

            Intent i = new Intent(ACTION);
            i.setClassName(PACKAGE, SERVICE);
            i.putExtra(EXTRA_PATH, BASH);
            i.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command});
            i.putExtra(EXTRA_WORKDIR, HOME);
            i.putExtra(EXTRA_BACKGROUND, true);
            i.putExtra(EXTRA_COMMAND_LABEL, "Your Friend");
            if (stdin != null && !stdin.isEmpty()) i.putExtra(EXTRA_STDIN, stdin);
            i.putExtra(EXTRA_PENDING_INTENT, pi);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (SecurityException e) {
                return ExecResult.backendError(id(), "Termux refused the command: " + e.getMessage()
                        + ". Check that allow-external-apps=true is set in ~/.termux/termux.properties.");
            } catch (IllegalStateException e) {
                return ExecResult.backendError(id(), "Android blocked starting Termux in the background: " + e.getMessage());
            }
            boolean got = false;
            long deadline = t0 + timeoutMs;
            while (!cancel.isCancelled()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    if (pending.latch.await(Math.min(left, 250), TimeUnit.MILLISECONDS)) {
                        got = true;
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!got) {
                // The command keeps running inside Termux; we can only stop waiting for it.
                return new ExecResult(id(), -1, "", cancel.isCancelled() ? "" : "No result from Termux within the timeout. " + noAnswerHelp(),
                        !cancel.isCancelled(), cancel.isCancelled(), System.currentTimeMillis() - t0, "");
            }
            Bundle b = pending.result;
            if (b == null) return ExecResult.backendError(id(), "Termux returned no result bundle");
            String errmsg = b.getString("errmsg", "");
            int err = b.getInt("err", -1);
            String stdout = b.getString("stdout", "");
            String stderr = b.getString("stderr", "");
            int exit = b.containsKey("exitCode") ? b.getInt("exitCode", -1) : -1;
            String backendErr = "";
            if (err != -1 && err != 0 && !errmsg.isEmpty() && exit == -1) backendErr = "Termux error " + err + ": " + errmsg;
            return new ExecResult(id(), exit, stdout, stderr.isEmpty() && !errmsg.isEmpty() && exit != 0 ? errmsg : stderr,
                    false, false, System.currentTimeMillis() - t0, backendErr);
        } finally {
            PENDING.remove(execId);
        }
    }

    /** Called by {@link TermuxResultReceiver}. */
    static void deliver(int execId, Bundle result) {
        Pending p = PENDING.get(execId);
        if (p != null) {
            p.result = result;
            p.latch.countDown();
        }
    }
}
