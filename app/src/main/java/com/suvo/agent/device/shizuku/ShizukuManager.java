package com.suvo.agent.device.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.suvo.agent.BuildConfig;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.ShellBackend;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku integration. Shizuku (a separate app) runs a service with ADB shell identity
 * (or root on rooted devices) after the user starts it via Wireless debugging; Your Friend asks
 * for Shizuku permission, then binds a small user service that executes shell commands.
 * No root is required. Without Shizuku everything falls back to Accessibility / normal APIs.
 */
public final class ShizukuManager implements ShellBackend {

    private static final String TAG = "YF.Shizuku";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final int REQUEST_CODE = 4711;

    public enum State {
        NOT_INSTALLED, NOT_RUNNING, UNSUPPORTED_VERSION, PERMISSION_REQUIRED, PERMISSION_DENIED, READY
    }

    public interface Listener {
        void onShizukuStateChanged(State state);
    }

    private final Context ctx;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile IShellService service;
    private volatile CountDownLatch connectLatch = new CountDownLatch(1);
    private volatile boolean binding;
    private volatile boolean binderAlive;

    private final Shizuku.UserServiceArgs serviceArgs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder);
                Log.i(TAG, "Shell user service connected");
            }
            binding = false;
            connectLatch.countDown();
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            connectLatch = new CountDownLatch(1);
            notifyListeners();
        }
    };

    public ShizukuManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(this.ctx.getPackageName(), ShellUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(BuildConfig.DEBUG)
                .tag("yf_shell")
                .version(BuildConfig.VERSION_CODE);
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                binderAlive = true;
                notifyListeners();
            });
            Shizuku.addBinderDeadListener(() -> {
                binderAlive = false;
                service = null;
                connectLatch = new CountDownLatch(1);
                notifyListeners();
            });
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> notifyListeners());
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku listeners unavailable: " + t);
        }
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        State s = state();
        for (Listener l : listeners) l.onShizukuStateChanged(s);
    }

    public boolean isInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public State state() {
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        binderAlive = alive;
        if (!alive) return isInstalled() ? State.NOT_RUNNING : State.NOT_INSTALLED;
        try {
            if (Shizuku.isPreV11()) return State.UNSUPPORTED_VERSION;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return State.READY;
            return Shizuku.shouldShowRequestPermissionRationale() ? State.PERMISSION_DENIED : State.PERMISSION_REQUIRED;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    /** Shows Shizuku's permission dialog. Result arrives through the listener. */
    public boolean requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(REQUEST_CODE);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed: " + t);
        }
        return false;
    }

    /** "root" (uid 0), "adb" (uid 2000) or "unknown". */
    public String identity() {
        try {
            int uid = Shizuku.getUid();
            return uid == 0 ? "root" : uid == 2000 ? "adb" : "uid " + uid;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private IShellService ensureService(long waitMs) {
        IShellService s = service;
        if (s != null && s.asBinder().pingBinder()) return s;
        if (state() != State.READY) return null;
        synchronized (this) {
            if (!binding) {
                binding = true;
                connectLatch = new CountDownLatch(1);
                try {
                    Shizuku.bindUserService(serviceArgs, connection);
                } catch (Throwable t) {
                    binding = false;
                    Log.w(TAG, "bindUserService failed: " + t);
                    return null;
                }
            }
        }
        try {
            connectLatch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return service;
    }

    public void unbind() {
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (Throwable ignored) {
        }
        service = null;
    }

    @Override
    public String id() {
        return "shizuku";
    }

    @Override
    public String unavailableReason() {
        switch (state()) {
            case READY:
                return null;
            case NOT_INSTALLED:
                return "Shizuku is not installed";
            case NOT_RUNNING:
                return "Shizuku is installed but not running (start it from the Shizuku app)";
            case UNSUPPORTED_VERSION:
                return "Shizuku is too old (v11 or newer is required)";
            case PERMISSION_DENIED:
                return "Shizuku permission was denied for Your Friend";
            case PERMISSION_REQUIRED:
            default:
                return "Shizuku permission has not been granted yet";
        }
    }

    @Override
    public ExecResult run(String command, String stdin, long timeoutMs, CancellationToken cancel) {
        String why = unavailableReason();
        if (why != null) return ExecResult.backendError(id(), why);
        IShellService s = ensureService(10_000);
        if (s == null) return ExecResult.backendError(id(), "Could not connect to the Shizuku shell service");
        String full = stdin == null || stdin.isEmpty() ? command
                : "printf '%s' " + shellQuote(stdin) + " | (" + command + ")";
        try {
            Bundle b = s.exec(full, timeoutMs);
            return new ExecResult(id(), b.getInt("exitCode", -1), b.getString("stdout", ""), b.getString("stderr", ""),
                    b.getBoolean("timedOut", false), false, b.getLong("durationMs", 0), "");
        } catch (RemoteException | RuntimeException e) {
            service = null;
            return ExecResult.backendError(id(), "Shizuku service call failed: " + e.getMessage());
        }
    }

    public String serviceInfo() {
        IShellService s = ensureService(5000);
        if (s == null) return "";
        try {
            return s.info();
        } catch (RemoteException e) {
            return "";
        }
    }

    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
