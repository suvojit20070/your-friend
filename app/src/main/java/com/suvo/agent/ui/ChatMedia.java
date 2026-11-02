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
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * Playback of audio and video shown in the chat, with a real media notification.
 * <ul>
 *   <li>Audio plays as a queue: every audio track in the chat, in order. Previous / next, shuffle
 *       and repeat (off / all / one) from the card, the notification, the lock screen and headset
 *       buttons. It keeps playing when the chat is closed (a small foreground service of the
 *       media-playback type).</li>
 *   <li>Video plays one at a time while it is on screen.</li>
 * </ul>
 */
public final class ChatMedia {

    /** What is playing (the queue's MediaPlayer, or a VideoView). */
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

    /** One audio track of the queue. {@code file} is a local path when the source is a file. */
    public static final class Track {
        public final String src, title, file;

        public Track(String src, String title, String file) {
            this.src = src;
            this.title = title == null || title.isEmpty() ? src : title;
            this.file = file;
        }
    }

    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final String S_SHUFFLE = "media_shuffle", S_REPEAT = "media_repeat";

    static final String ACTION_TOGGLE = "com.suvo.agent.media.TOGGLE";
    static final String ACTION_STOP = "com.suvo.agent.media.STOP";
    static final String ACTION_NEXT = "com.suvo.agent.media.NEXT";
    static final String ACTION_PREV = "com.suvo.agent.media.PREV";
    static final String ACTION_SHUFFLE = "com.suvo.agent.media.SHUFFLE";
    static final String ACTION_REPEAT = "com.suvo.agent.media.REPEAT";
    private static final int NOTIF_ID = 1007;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context app;
    private static MediaSession session;
    private static Target target;
    private static String key = "", title = "";
    private static boolean audio;

    // Audio queue.
    private static final List<Track> queue = new ArrayList<>();
    /** Play order: indexes into {@link #queue} (shuffled or not). */
    private static final List<Integer> order = new ArrayList<>();
    private static int pos = -1; // position in order
    private static boolean shuffle;
    private static int repeat = REPEAT_OFF;

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

    public static boolean shuffle() {
        return shuffle;
    }

    public static int repeat() {
        return repeat;
    }

    /** "3 / 10" for the playing track of the queue, or "". */
    public static String queuePosition() {
        if (target == null || !audio || queue.size() < 2 || pos < 0 || pos >= order.size()) return "";
        return (order.get(pos) + 1) + " / " + queue.size();
    }

    private static void loadModes(Context ctx) {
        Settings s = Settings.get(ctx);
        shuffle = s.bool(S_SHUFFLE, false);
        repeat = s.integer(S_REPEAT, REPEAT_OFF);
    }

    // ------------------------------------------------------------------ audio queue

    /** Plays {@code tracks} as a queue starting at {@code start} (an index into tracks). */
    public static void playQueue(Context ctx, List<Track> tracks, int start) {
        app = ctx.getApplicationContext();
        loadModes(app);
        queue.clear();
        queue.addAll(tracks);
        buildOrder(Math.max(0, Math.min(start, queue.size() - 1)));
        playAt(pos);
    }

    /** Order of play; the chosen track first when shuffled. */
    private static void buildOrder(int startTrack) {
        order.clear();
        for (int i = 0; i < queue.size(); i++) order.add(i);
        if (shuffle && queue.size() > 1) {
            order.remove(Integer.valueOf(startTrack));
            Collections.shuffle(order);
            order.add(0, startTrack);
            pos = 0;
        } else {
            pos = startTrack;
        }
    }

    private static int currentTrack() {
        return pos >= 0 && pos < order.size() ? order.get(pos) : -1;
    }

    private static void playAt(int p) {
        if (p < 0 || p >= order.size()) return;
        pos = p;
        Track t = queue.get(order.get(p));
        MediaPlayer mp = new MediaPlayer();
        AudioTarget at = new AudioTarget(mp);
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (t.file != null && new File(t.file).exists()) mp.setDataSource(t.file);
            else mp.setDataSource(app, Uri.parse(t.src));
            mp.setOnPreparedListener(m -> {
                at.prepared = true;
                if (target == at) {
                    m.start();
                    changed();
                }
            });
            mp.setOnCompletionListener(m -> {
                if (target == at) MAIN.post(ChatMedia::onTrackEnd);
            });
            mp.setOnErrorListener((m, w, e) -> {
                if (target == at) {
                    Ui.toast(app, R.string.block_audio_error);
                    // Skip a broken track instead of stopping the whole queue.
                    MAIN.post(() -> {
                        if (queue.size() > 1 && pos < order.size() - 1) next();
                        else stop();
                    });
                }
                return true;
            });
            start(app, t.src, t.title, true, at);
            mp.prepareAsync();
        } catch (Exception e) {
            at.release();
            Ui.toast(app, R.string.block_audio_error);
        }
    }

    private static void onTrackEnd() {
        if (repeat == REPEAT_ONE && target != null) {
            target.seekTo(0);
            target.play();
            changed();
            return;
        }
        if (pos < order.size() - 1) {
            playAt(pos + 1);
        } else if (repeat == REPEAT_ALL && !order.isEmpty()) {
            if (shuffle) buildOrder(order.get(0));
            playAt(0);
        } else if (target != null) {
            target.seekTo(0); // the end: stays on the last track, paused at the start
            changed();
        }
    }

    public static void next() {
        if (!audio || order.isEmpty()) return;
        if (pos < order.size() - 1) playAt(pos + 1);
        else if (repeat != REPEAT_OFF || order.size() > 1) playAt(0);
    }

    public static void previous() {
        if (!audio || order.isEmpty()) return;
        // Like every music player: more than 3 s in, "previous" restarts the track.
        if (target != null && target.position() > 3000) {
            target.seekTo(0);
            changed();
            return;
        }
        if (pos > 0) playAt(pos - 1);
        else if (repeat == REPEAT_ALL) playAt(order.size() - 1);
        else if (target != null) {
            target.seekTo(0);
            changed();
        }
    }

    public static void toggleShuffle(Context ctx) {
        if (app == null) app = ctx.getApplicationContext();
        shuffle = !shuffle;
        Settings.get(app).put(S_SHUFFLE, shuffle);
        int cur = currentTrack();
        if (cur >= 0) buildOrder(cur);
        changed();
        notifyModes();
    }

    public static void cycleRepeat(Context ctx) {
        if (app == null) app = ctx.getApplicationContext();
        repeat = (repeat + 1) % 3;
        Settings.get(app).put(S_REPEAT, repeat);
        changed();
        notifyModes();
    }

    private static void notifyModes() {
        for (Listener l : LISTENERS) MAIN.post(l::onMediaChanged);
    }

    private static final class AudioTarget implements Target {
        final MediaPlayer mp;
        boolean prepared;

        AudioTarget(MediaPlayer mp) {
            this.mp = mp;
        }

        @Override
        public void play() {
            if (prepared) mp.start();
        }

        @Override
        public void pause() {
            if (prepared && mp.isPlaying()) mp.pause();
        }

        @Override
        public void release() {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public boolean isPlaying() {
            try {
                return prepared && mp.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }

        @Override
        public int position() {
            try {
                return prepared ? mp.getCurrentPosition() : 0;
            } catch (IllegalStateException e) {
                return 0;
            }
        }

        @Override
        public int duration() {
            try {
                return prepared ? mp.getDuration() : 0;
            } catch (IllegalStateException e) {
                return 0;
            }
        }

        @Override
        public void seekTo(int ms) {
            if (prepared) mp.seekTo(ms);
        }
    }

    // ------------------------------------------------------------------ any target (audio / video)

    /** Takes over playback with a new target (stops the previous one). Video calls this directly. */
    public static void start(Context ctx, String src, String name, boolean isAudio, Target t) {
        app = ctx.getApplicationContext();
        if (target != null && target != t) {
            try {
                target.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (!isAudio) {
            queue.clear();
            order.clear();
            pos = -1;
        }
        target = t;
        key = src;
        title = name == null || name.isEmpty() ? src : name;
        audio = isAudio;
        ensureSession();
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

    /** Called when the state changes (prepared, playing, paused, finished, modes). */
    public static void changed() {
        if (target == null) return;
        if (session != null) {
            boolean playing = target.isPlaying();
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
            PlaybackState.Builder ps = new PlaybackState.Builder()
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, Math.max(0, safePosition()), playing ? 1f : 0f);
            if (audio && queue.size() > 1) {
                actions |= PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                // Shuffle and repeat as custom actions (shown by the system media controls).
                ps.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_SHUFFLE, app.getString(R.string.media_shuffle),
                        shuffle ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off).build());
                ps.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_REPEAT, app.getString(R.string.media_repeat),
                        repeatIcon()).build());
            }
            ps.setActions(actions);
            session.setPlaybackState(ps.build());
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, app.getString(R.string.app_name))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, queuePosition())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, safeDuration()))
                    .build());
        }
        notifyNow();
        for (Listener l : LISTENERS) MAIN.post(l::onMediaChanged);
    }

    private static int repeatIcon() {
        return repeat == REPEAT_ONE ? R.drawable.ic_repeat_one : repeat == REPEAT_ALL ? R.drawable.ic_repeat : R.drawable.ic_repeat_off;
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
            public void onSeekTo(long p) {
                if (target != null) target.seekTo((int) p);
                changed();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (ACTION_SHUFFLE.equals(action)) toggleShuffle(app);
                else if (ACTION_REPEAT.equals(action)) cycleRepeat(app);
            }
        });
    }

    private static PendingIntent button(Context c, String action, int req) {
        return PendingIntent.getBroadcast(c, req, new Intent(c, Buttons.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification notification() {
        Context c = app;
        boolean playing = target != null && target.isPlaying();
        boolean list = audio && queue.size() > 1;
        Intent open = new Intent(c, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, 7101, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pStop = button(c, ACTION_STOP, 7103);
        Notification.Builder b = Notifications.builder(c, Notifications.CHANNEL_MEDIA)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(title)
                .setContentText(list ? c.getString(R.string.media_queue, queuePosition())
                        : c.getString(audio ? R.string.media_audio : R.string.media_video))
                .setContentIntent(pOpen)
                .setDeleteIntent(pStop)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) style.setMediaSession(session.getSessionToken());
        Notification.Action toggle = new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                c.getString(playing ? R.string.media_pause : R.string.media_play), button(c, ACTION_TOGGLE, 7102)).build();
        if (list) {
            // shuffle · previous · play/pause · next · repeat (compact: previous, play, next)
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, shuffle ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off),
                    c.getString(R.string.media_shuffle), button(c, ACTION_SHUFFLE, 7104)).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_skip_prev),
                    c.getString(R.string.media_prev), button(c, ACTION_PREV, 7105)).build());
            b.addAction(toggle);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_skip_next),
                    c.getString(R.string.media_next), button(c, ACTION_NEXT, 7106)).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, repeatIcon()),
                    c.getString(R.string.media_repeat), button(c, ACTION_REPEAT, 7107)).build());
            style.setShowActionsInCompactView(1, 2, 3);
        } else {
            b.addAction(toggle);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_close),
                    c.getString(R.string.media_stop), pStop).build());
            style.setShowActionsInCompactView(0, 1);
        }
        b.setStyle(style);
        return b.build();
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

    /** The buttons of the media notification. */
    public static final class Buttons extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_TOGGLE:
                    toggle();
                    break;
                case ACTION_STOP:
                    stop();
                    break;
                case ACTION_NEXT:
                    next();
                    break;
                case ACTION_PREV:
                    previous();
                    break;
                case ACTION_SHUFFLE:
                    toggleShuffle(context);
                    break;
                case ACTION_REPEAT:
                    cycleRepeat(context);
                    break;
                default:
                    break;
            }
        }
    }
}
