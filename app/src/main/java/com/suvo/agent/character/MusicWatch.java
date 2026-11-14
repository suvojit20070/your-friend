package com.suvo.agent.character;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.suvo.agent.core.audio.BeatTracker;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Notices music playing on the phone (any app) so the companion can dance:
 * <ul>
 *   <li>Playing or not, and which song: the playing media session (title, artist, app). This
 *       needs notification access (the auto-reply permission); without it, or without a song
 *       title, nothing is reported at all — the companion dances only to songs it can name.</li>
 *   <li>Tempo: the system audio visualizer's bass energy goes to a {@link BeatTracker}. This needs
 *       the microphone permission (Android's rule for the visualizer; nothing is recorded or
 *       kept, only the loudness of the bass is read). Phones that block it: no measured tempo,
 *       the AI or a default tempo is used.</li>
 * </ul>
 * The companion's own voice is not counted as music.
 */
final class MusicWatch {

    interface Listener {
        /** Main thread: music started or stopped, or the song changed. */
        void onMusic(boolean playing, String title, String artist, String app);
    }

    private static final String TAG = "YF.Music";
    private static final long POLL_MS = 2000;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BeatTracker beats = new BeatTracker();
    private final Object beatLock = new Object();
    private Visualizer viz;
    private boolean running;
    private boolean playing;
    private int playingPolls, silentPolls;
    private String title = "", artist = "", app = "";
    private float peak = 1e-6f;

    MusicWatch(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (running) return;
        running = true;
        main.post(poll);
    }

    void stop() {
        running = false;
        main.removeCallbacks(poll);
        stopViz();
        if (playing) {
            playing = false;
            listener.onMusic(false, title, artist, app);
        }
    }

    boolean isPlaying() {
        return playing;
    }

    /** Measured tempo, 0 when unknown. */
    float bpm() {
        synchronized (beatLock) {
            return beats.bpm();
        }
    }

    /** A moment a beat fell, as System.nanoTime(), or 0 when unknown. */
    long beatAnchorNs() {
        long a;
        synchronized (beatLock) {
            a = beats.beatAnchorMs();
        }
        if (a < 0) return 0;
        return System.nanoTime() - (SystemClock.uptimeMillis() - a) * 1_000_000L;
    }

    /** 0..1 how loud the music is now (relative to the loudest lately). */
    float loudness() {
        synchronized (beatLock) {
            return Math.max(0f, Math.min(1f, beats.level() / peak));
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            check();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void check() {
        boolean own = false;
        try {
            com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
            // The companion talking (its voice is "media" too) or a call: not music.
            own = g.speech.isActive() || com.suvo.agent.voice.CallService.active();
        } catch (RuntimeException ignored) {
        }
        String[] meta = new String[3];
        Boolean session = sessionPlaying(meta);
        boolean active;
        if (session != null) {
            active = session;
        } else {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            active = am != null && am.isMusicActive() && !own;
        }
        if (own && session == null) active = playing; // keep the current state while it talks
        // Without the song's name (no notification access) there is nothing to dance to: as if silent.
        if (meta[0] == null || meta[0].trim().isEmpty()) active = false;
        if (active) {
            playingPolls++;
            silentPolls = 0;
        } else {
            silentPolls++;
            playingPolls = 0;
        }
        String t = meta[0] == null ? "" : meta[0], a = meta[1] == null ? "" : meta[1], p = meta[2] == null ? "" : meta[2];
        boolean songChanged = !t.equals(title) || !a.equals(artist);
        title = t;
        artist = a;
        app = p;
        // A few seconds of music before dancing, a few of silence before stopping (pauses, ads).
        if (!playing && playingPolls >= 2) {
            playing = true;
            startViz();
            listener.onMusic(true, title, artist, app);
        } else if (playing && silentPolls >= 3) {
            playing = false;
            stopViz();
            listener.onMusic(false, title, artist, app);
        } else if (playing && songChanged) {
            synchronized (beatLock) {
                beats.reset();
            }
            listener.onMusic(true, title, artist, app);
        }
    }

    /** True/false from the media sessions (with notification access), null when not allowed. */
    private Boolean sessionPlaying(String[] meta) {
        try {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(new ComponentName(ctx, com.suvo.agent.autoreply.ReplyListener.class));
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                if (ctx.getPackageName().equals(c.getPackageName())) continue;
                MediaMetadata md = c.getMetadata();
                if (md != null) {
                    meta[0] = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    meta[1] = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (meta[1] == null) meta[1] = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                }
                meta[2] = label(c.getPackageName());
                return true;
            }
            return false;
        } catch (SecurityException e) {
            return null; // no notification access
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---- Tempo from the output mix ---------------------------------------------------------------

    private void startViz() {
        if (viz != null) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(range[1], 1024)));
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    onFft(fft);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            viz = v;
        } catch (Throwable e) {
            // Blocked on this phone (or in use): dance without a measured tempo.
            Log.i(TAG, "No visualizer: " + e.getMessage());
            viz = null;
        }
    }

    private void onFft(byte[] fft) {
        if (fft == null || fft.length < 20) return;
        // Bins 1..8: the bass, where the kick drum is.
        float e = 0f;
        for (int k = 1; k <= 8; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            e += re * re + im * im;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (beatLock) {
            beats.accept(e, now);
            peak = Math.max(peak * 0.999f, beats.level());
        }
    }

    private void stopViz() {
        Visualizer v = viz;
        viz = null;
        if (v != null) {
            try {
                v.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            v.release();
        }
        synchronized (beatLock) {
            beats.reset();
        }
    }
}
