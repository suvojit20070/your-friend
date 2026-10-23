package com.suvo.agent.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.suvo.agent.R;
import com.suvo.agent.ui.MainActivity;

/**
 * Keeps Aura alive 24/7 as a foreground service. A persistent notification is
 * always visible (transparency: user always knows the agent is running) and acts
 * as the kill-switch entry point.
 *
 * The overlay character window and mic session attach to this service in later phases.
 */
public class AgentService extends LifecycleService {

    private static final String CHANNEL = "aura_agent";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_STOP = "com.suvo.agent.STOP";

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AgentService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, AgentService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;   // OS restarts us if killed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Aura agent", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps your assistant active");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, AgentService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Aura is active")
                .setContentText("Tap to open · agent running")
                .setSmallIcon(R.drawable.ic_aura_notification)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build();
    }
}
