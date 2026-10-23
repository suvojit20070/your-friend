package com.suvo.agent.terminal;

import com.suvo.agent.core.util.CancellationToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands with the device's /system/bin/sh as this app's own Linux user. That user cannot
 * read other apps' data and has no root; the working directory is the private workspace. Android's
 * toybox provides common utilities (ls, cat, grep, sed...). No package manager or compilers exist
 * here — those come from Termux.
 */
public final class LocalShellBackend implements ShellBackend {

    private static final int MAX_OUTPUT = 1024 * 1024;

    private final File workDir;
    private final File tmpDir;
    private final Map<String, String> extraEnv;

    public LocalShellBackend(File workDir, File tmpDir, Map<String, String> extraEnv) {
        this.workDir = workDir;
        this.tmpDir = tmpDir;
        this.extraEnv = extraEnv;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public String unavailableReason() {
        return new File("/system/bin/sh").exists() ? null : "/system/bin/sh not found";
    }

    @Override
    public ExecResult run(String command, String stdin, long timeoutMs, CancellationToken cancel) {
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
        pb.directory(workDir);
        Map<String, String> env = pb.environment();
        env.put("HOME", workDir.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("WORKSPACE", workDir.getAbsolutePath());
        if (extraEnv != null) env.putAll(extraEnv);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return ExecResult.backendError(id(), "Could not start shell: " + e.getMessage());
        }
        final Process proc = p;
        Runnable killer = proc::destroy;
        cancel.onCancel(killer);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread to = pump(proc.getInputStream(), out);
        Thread te = pump(proc.getErrorStream(), err);
        try (OutputStream os = proc.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) os.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // process may not read stdin
        }
        boolean finished;
        try {
            finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        boolean timedOut = !finished && !cancel.isCancelled();
        if (!finished) {
            proc.destroy();
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly();
            } catch (InterruptedException ignored) {
                proc.destroyForcibly();
            }
        }
        try {
            to.join(2000);
            te.join(2000);
        } catch (InterruptedException ignored) {
        }
        cancel.removeListener(killer);
        int code;
        try {
            code = proc.exitValue();
        } catch (IllegalThreadStateException e) {
            code = -1;
        }
        return new ExecResult(id(), code, out.toString(), err.toString(), timedOut, cancel.isCancelled(),
                System.currentTimeMillis() - t0, "");
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    synchronized (sink) {
                        if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size()));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "shell-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
