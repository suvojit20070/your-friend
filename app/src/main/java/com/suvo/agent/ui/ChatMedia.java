package com.suvo.agent.ui;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.suvo.agent.R;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * Playback of audio and video shown in the chat, with a real media notification: one media
 * session (lock screen, quick settings media player, headset buttons) and a notification with
 * play / pause and stop. Audio keeps playing when the chat is left (a small foreground service of
 * the media-playback type holds it); video plays while it is on screen.
 */
public final class ChatMedia {

    /** What is playing (a MediaPlayer for audio, the VideoView for video). */
    public interface Target {
        void play();

        void pause();

        void release();

        boolean isPlaying();

        int position();

        int duration();

        void seekTo(int ms);
    }

    /** Chat players redraw their buttons / progress. */
    public interface Listener {
        void onMediaChanged();
    }

    static final String ACTION_TOGGLE = "com.suvo.agent.media.TOGGLE";
    static final String ACTION_STOP = "com.suvo.agent.media.STOP";
    private static final int NOTIF_ID = 1007;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context app;
    private static MediaSession session;
    private static Target target;
    private static String key = "", title = "";
    private static boolean audio;

    private ChatMedia() {
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** The source now playing / paused ("" = nothing). */
    public static String current() {
        return target == null ? "" : key;
    }

    public static Target target() {
        return target;
    }

    public static boolean isPlaying(String src) {
        return target != null && key.equals(src) && target.isPlaying();
    }

    /** Takes over playback with a new target (stops the previous one). */
    public static void start(Context ctx, String src, String name, boolean isAudio, Target t) {
        app = ctx.getApplicationContext();
        if (target != null && target != t) {
            try {
                target.release();
            } catch (RuntimeException ignored) {
            }
        }
        target = t;
        key = src;
        title = name == null || name.isEmpty() ? src : name;
        audio = isAudio;
        ensureSession();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, app.getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, safeDuration()))
                .build());
        session.setActive(true);
        changed();
        if (audio) {
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(new Intent(app, Holder.class));
                else app.startService(new Intent(app, Holder.class));
            } catch (RuntimeException e) {
                notifyNow(); // could not start the service: at least show the controls
            }
        }
    }

    /** Called by players when their state changes (prepared, playing, paused, finished). */
    public static void changed() {
        if (target == null) return;
        if (session != null) {
            boolean playing = target.isPlaying();
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, Math.max(0, safePosition()), playing ? 1f : 0f)
                    .build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, app.getString(R.string.app_name))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, safeDuration()))
                    .build());
        }
        notifyNow();
        for (Listener l : LISTENERS) MAIN.post(l::onMediaChanged);
    }

    public static void toggle() {
        if (target == null) return;
        if (target.isPlaying()) target.pause();
        else target.play();
        changed();
    }

    /** Stops and forgets the current playback, removes the notification. */
    public static void stop() {
        Target t = target;
        target = null;
        key = "";
        if (t != null) {
            try {
                t.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (session != null) session.setActive(false);
        if (app != null) {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
            app.stopService(new Intent(app, Holder.class));
        }
        for (Listener l : LISTENERS) MAIN.post(l::onMediaChanged);
    }

    /** The chat went to the background: a video cannot keep playing without its surface. */
    public static void stopVideo() {
        if (target != null && !audio) stop();
    }

    /** Video left the screen: stop it if it is the one playing. */
    public static void stopIf(String src, Target t) {
        if (target == t || (target != null && key.equals(src) && !audio)) stop();
    }

    private static int safePosition() {
        try {
            return target == null ? 0 : target.position();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int safeDuration() {
        try {
            return target == null ? 0 : target.duration();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void ensureSession() {
        if (session != null) return;
        session = new MediaSession(app, "YourFriendChat");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (target != null) target.play();
                changed();
            }

            @Override
            public void onPause() {
                if (target != null) target.pause();
                changed();
            }

            @Override
            public void onStop() {
                stop();
            }

            @Override
            public void onSeekTo(long pos) {
                if (target != null) target.seekTo((int) pos);
                changed();
            }
        });
    }

    static Notification notification() {
        Context c = app;
        boolean playing = target != null && target.isPlaying();
        Intent open = new Intent(c, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, 7101, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pToggle = PendingIntent.getBroadcast(c, 7102, new Intent(c, Buttons.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pStop = PendingIntent.getBroadcast(c, 7103, new Intent(c, Buttons.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1);
        if (session != null) style.setMediaSession(session.getSessionToken());
        return Notifications.builder(c, Notifications.CHANNEL_MEDIA)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(title)
                .setContentText(c.getString(audio ? R.string.media_audio : R.string.media_video))
                .setContentIntent(pOpen)
                .setDeleteIntent(pStop)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(style)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        c.getString(playing ? R.string.media_pause : R.string.media_play), pToggle).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_close),
                        c.getString(R.string.media_stop), pStop).build())
                .build();
    }

    private static void notifyNow() {
        if (app == null || target == null) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ID, notification());
        } catch (RuntimeException ignored) {
            // No notification permission: playback still works in the chat.
        }
    }

    /** Keeps audio playing while the chat is closed (foreground service, media playback). */
    public static final class Holder extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (target == null || app == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTIF_ID, notification());
            } catch (RuntimeException e) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }

    /** Play / pause and stop from the notification. */
    public static final class Buttons extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (ACTION_TOGGLE.equals(intent.getAction())) toggle();
            else if (ACTION_STOP.equals(intent.getAction())) stop();
        }
    }
}
