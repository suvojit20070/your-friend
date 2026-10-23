package com.suvo.agent;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import com.suvo.agent.character.FloatingCompanionService;
import com.suvo.agent.ui.LocaleHelper;
import com.suvo.agent.ui.Notifications;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Your Friend — Original project by Suvo.
 * Application entry point: installs the crash handler, builds the dependency graph and tracks
 * whether any activity is visible (camera and screen-capture consent need the app in the foreground).
 */
public final class App extends Application {

    private static volatile int startedActivities;

    public static boolean isInForeground() {
        return startedActivities > 0;
    }

    @Override
    protected void attachBaseContext(Context base) {
        // Installed first, so even a crash in the locale setup or App.onCreate reaches DebugActivity.
        installCrashHandler(base);
        Context wrapped;
        try {
            wrapped = isDebugProcess() ? base : LocaleHelper.wrap(base);
        } catch (Throwable t) {
            Log.e("YF.App", "Locale setup failed, using the system locale", t);
            wrapped = base;
        }
        super.attachBaseContext(wrapped);
    }

    private void installCrashHandler(Context base) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("YF.Crash", "Uncaught exception in " + thread.getName(), throwable);
            if (!isDebugProcess()) {
                Context ctx = getBaseContext() != null ? this : base;
                DebugActivity.handleCrash(ctx, thread, throwable);
            }
            Process.killProcess(Process.myPid());
            System.exit(1);
        });
    }

    /** True in the separate ":debug" process that only hosts DebugActivity. */
    private static boolean isDebugProcess() {
        return processName().endsWith(":debug");
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) {
            String n = Application.getProcessName();
            if (n != null) return n;
        }
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            int end = 0;
            while (end < Math.max(n, 0) && buf[end] != 0) end++;
            return new String(buf, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The crash screen's process must not run any app setup: if that setup is what crashed,
        // DebugActivity would crash too.
        if (isDebugProcess()) return;

        Notifications.createChannels(this);
        AppGraph g = AppGraph.init(this);
        g.io.execute(() -> {
            try {
                int n = g.tasks.markInterrupted();
                if (n > 0) Log.i("YF.App", n + " task(s) were interrupted by a previous process stop");
                g.memory.pruneExpired();
            } catch (RuntimeException e) {
                Log.w("YF.App", "Startup maintenance failed", e);
            }
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedActivities++;
                if (startedActivities == 1) FloatingCompanionService.onAppVisibilityChanged(App.this, true);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0) FloatingCompanionService.onAppVisibilityChanged(App.this, false);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }
}
