package com.suvo.agent.connectors;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.suvo.agent.R;
import com.suvo.agent.ui.Notifications;

/**
 * Your Friend — Original project by Suvo.
 *
 * Owner of the agent browser while it has tabs. A screen (BrowserActivity, the chat, the floating
 * window) only borrows a tab to show it; when the screen goes away the tab stays here and keeps
 * running (music, video, scripts, logins). The foreground service keeps the app's process alive
 * for that, also when no agent task runs. It starts with the first tab and stops with the last
 * one; its notification can close the browser.
 */
public final class BrowserService extends Service {

    public static final int NOTIF_ID = 1010;
    static final String ACTION_CLOSE = "com.suvo.agent.browser.CLOSE";

    private static boolean running;

    /** Called when the first tab opens (and again whenever the tab count changes). */
    static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, BrowserService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i);
            else app.startService(i);
        } catch (RuntimeException ignored) {
            // Not allowed right now (app in the background): the tabs still work while the process lives.
        }
    }

    /** Called when the last tab closed. */
    static void stop(Context ctx) {
        if (!running) return;
        Context app = ctx.getApplicationContext();
        app.stopService(new Intent(app, BrowserService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CLOSE.equals(intent.getAction())) {
            // "Close browser" in the notification: every tab closes, then the service ends.
            WebSession.closeAllTabs();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (WebSession.tabCount() == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF_ID, n);
            running = true;
        } catch (RuntimeException e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification build() {
        Notifications.createChannels(this);
        int n = WebSession.tabCount();
        Intent open = new Intent(this, com.suvo.agent.ui.BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pClose = PendingIntent.getService(this, 1, new Intent(this, BrowserService.class).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, Notifications.CHANNEL_AGENT)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.browser_title))
                .setContentText(getResources().getQuantityString(R.plurals.browser_service_tabs, n, n))
                .setContentIntent(pOpen)
                .addAction(0, getString(R.string.browser_service_close), pClose)
                // Swiped away by mistake: it comes back (the browser is still running). Stop it with
                // "Close browser" here, in the chat or in the browser screen's menu.
                .setDeleteIntent(PendingIntent.getService(this, 2, new Intent(this, BrowserService.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
