package com.suvo.agent.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import com.suvo.agent.R;

/** Notification channels and builders. */
public final class Notifications {

    public static final String CHANNEL_AGENT = "agent";
    public static final String CHANNEL_APPROVAL = "approval";
    public static final String CHANNEL_CAPTURE = "capture";
    public static final String CHANNEL_LISTEN = "listen";
    public static final String CHANNEL_COMPANION = "companion";
    public static final String CHANNEL_GENERAL = "general";

    public static final int ID_AGENT = 1001;
    public static final int ID_CAPTURE = 1002;
    public static final int ID_LISTEN = 1003;
    public static final int ID_COMPANION = 1004;
    public static final int ID_APPROVAL_BASE = 2000;
    public static final int ID_TASK_DONE = 1005;

    private Notifications() {
    }

    public static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_AGENT, ctx.getString(R.string.channel_agent), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel approval = new NotificationChannel(CHANNEL_APPROVAL, ctx.getString(R.string.channel_approval), NotificationManager.IMPORTANCE_HIGH);
        approval.setDescription(ctx.getString(R.string.channel_approval_desc));
        nm.createNotificationChannel(approval);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_CAPTURE, ctx.getString(R.string.channel_capture), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_LISTEN, ctx.getString(R.string.channel_listen), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_COMPANION, ctx.getString(R.string.channel_companion), NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_GENERAL, ctx.getString(R.string.channel_general), NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static Notification.Builder builder(Context ctx, String channel) {
        return new Notification.Builder(ctx, channel);
    }
}
