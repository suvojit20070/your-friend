package com.suvo.agent.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

/**
 * Notification channels and builders.
 *
 * The user can pick their own notification tone (Settings → Notification tone). Android fixes a
 * channel's sound when the channel is created, so the channels that make a sound (task done,
 * approvals, auto reply drafts, new app alerts) get a versioned id ("general_t3"): changing the tone
 * deletes the old channels and creates new ones with the new sound. Callers keep using the base
 * names; {@link #builder} maps them.
 */
public final class Notifications {

    public static final String CHANNEL_AGENT = "agent";
    public static final String CHANNEL_APPROVAL = "approval";
    public static final String CHANNEL_CAPTURE = "capture";
    public static final String CHANNEL_LISTEN = "listen";
    public static final String CHANNEL_COMPANION = "companion";
    public static final String CHANNEL_GENERAL = "general";
    public static final String CHANNEL_AUTO_REPLY = "auto_reply";
    public static final String CHANNEL_APP_ALERT = "app_alert";
    public static final String CHANNEL_MEDIA = "media";

    /** Channels that ring (their sound follows the user's tone). */
    private static final String[] SOUNDED = {CHANNEL_GENERAL, CHANNEL_APPROVAL, CHANNEL_AUTO_REPLY, CHANNEL_APP_ALERT};

    /** Settings: chosen tone ("" = system default, "silent" = no sound) and its version. */
    public static final String TONE = "notif_tone";
    public static final String TONE_VERSION = "notif_tone_version";
    public static final String SILENT = "silent";

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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_CAPTURE, ctx.getString(R.string.channel_capture), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_LISTEN, ctx.getString(R.string.channel_listen), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_COMPANION, ctx.getString(R.string.channel_companion), NotificationManager.IMPORTANCE_MIN));
        NotificationChannel media = new NotificationChannel(CHANNEL_MEDIA, ctx.getString(R.string.channel_media), NotificationManager.IMPORTANCE_LOW);
        media.setShowBadge(false);
        nm.createNotificationChannel(media);
        createSounded(ctx, nm);
    }

    private static void createSounded(Context ctx, NotificationManager nm) {
        Settings s = Settings.get(ctx);
        String tone = s.str(TONE, "");
        int v = s.integer(TONE_VERSION, 0);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        for (String base : SOUNDED) {
            int importance;
            int name;
            switch (base) {
                case CHANNEL_APPROVAL:
                    importance = NotificationManager.IMPORTANCE_HIGH;
                    name = R.string.channel_approval;
                    break;
                case CHANNEL_AUTO_REPLY:
                    importance = NotificationManager.IMPORTANCE_HIGH;
                    name = R.string.channel_auto_reply;
                    break;
                case CHANNEL_APP_ALERT:
                    importance = NotificationManager.IMPORTANCE_HIGH;
                    name = R.string.channel_app_alert;
                    break;
                default:
                    importance = NotificationManager.IMPORTANCE_DEFAULT;
                    name = R.string.channel_general;
                    break;
            }
            NotificationChannel ch = new NotificationChannel(id(base, v), ctx.getString(name), importance);
            if (base.equals(CHANNEL_APPROVAL)) ch.setDescription(ctx.getString(R.string.channel_approval_desc));
            if (SILENT.equals(tone)) {
                ch.setSound(null, null);
            } else if (!tone.isEmpty()) {
                ch.setSound(Uri.parse(tone), attrs);
            }
            nm.createNotificationChannel(ch);
        }
    }

    private static String id(String base, int version) {
        return version == 0 ? base : base + "_t" + version;
    }

    /** The real channel id for a base name (sounded channels are versioned by the tone). */
    public static String channel(Context ctx, String base) {
        for (String b : SOUNDED) if (b.equals(base)) return id(base, Settings.get(ctx).integer(TONE_VERSION, 0));
        return base;
    }

    /**
     * Saves a new tone ("" = default, {@link #SILENT}, or a sound content:// / file URI) and rebuilds
     * the sounded channels with it.
     */
    public static void setTone(Context ctx, String uri) {
        Settings s = Settings.get(ctx);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        int old = s.integer(TONE_VERSION, 0);
        s.put(TONE, uri == null ? "" : uri);
        s.put(TONE_VERSION, old + 1);
        if (nm == null) return;
        for (String b : SOUNDED) {
            try {
                nm.deleteNotificationChannel(id(b, old));
            } catch (RuntimeException ignored) {
            }
        }
        createSounded(ctx, nm);
    }

    public static Notification.Builder builder(Context ctx, String channel) {
        return new Notification.Builder(ctx, channel(ctx, channel));
    }
}
