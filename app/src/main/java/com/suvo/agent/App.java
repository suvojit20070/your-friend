package com.suvo.agent;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.suvo.agent.character.FloatingCompanionService;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.LocaleHelper;
import com.suvo.agent.ui.Notifications;

/**
 * Your Friend — Original project by Suvo.
 * Application entry point: builds the dependency graph and tracks whether any activity is visible
 * (camera and screen-capture consent need the app in the foreground).
 */
public final class App extends Application {

    private static volatile int startedActivities;

    public static boolean isInForeground() {
        return startedActivities > 0;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
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
        if (g.settings.bool(Settings.FLOATING_COMPANION, false) && FloatingCompanionService.canRun(this)) {
            // Restarted only while the app itself is starting (allowed foreground-service start).
            try {
                FloatingCompanionService.start(this);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
