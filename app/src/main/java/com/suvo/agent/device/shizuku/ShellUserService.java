package com.suvo.agent.device.shizuku;

import android.content.Context;
import android.os.Bundle;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Runs inside the process Shizuku starts with shell (uid 2000) or root (uid 0) identity. It only
 * executes commands the app sends after the app's own permission and approval checks. With ADB
 * identity it can do what `adb shell` can do (input events, screencap, uiautomator, pm list) and
 * nothing more.
 */
public class ShellUserService extends IShellService.Stub {

    private static final int MAX_OUTPUT = 2 * 1024 * 1024;

    /** Required no-arg constructor (Shizuku &lt; v13). */
    public ShellUserService() {
    }

    /** Constructor used by Shizuku v13+. */
    public ShellUserService(Context context) {
    }

    @Override
    public Bundle exec(String command, long timeoutMs) {
        Bundle b = new Bundle();
        long t0 = System.currentTimeMillis();
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", command).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = pump(p.getInputStream(), out);
            Thread te = pump(p.getErrorStream(), err);
            p.getOutputStream().close();
            boolean done = p.waitFor(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
            }
            to.join(2000);
            te.join(2000);
            b.putInt("exitCode", done ? p.exitValue() : -1);
            b.putBoolean("timedOut", !done);
            b.putString("stdout", out.toString());
            b.putString("stderr", err.toString());
        } catch (IOException | InterruptedException e) {
            b.putInt("exitCode", -1);
            b.putString("stdout", "");
            b.putString("stderr", e.getClass().getSimpleName() + ": " + e.getMessage());
            if (p != null) p.destroyForcibly();
        }
        b.putLong("durationMs", System.currentTimeMillis() - t0);
        return b;
    }

    @Override
    public String info() {
        String ctx;
        try {
            ctx = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/attr/current"))).trim();
        } catch (Exception e) {
            ctx = "unknown";
        }
        return "uid=" + Os.getuid() + " pid=" + Os.getpid() + " context=" + ctx;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size()));
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
