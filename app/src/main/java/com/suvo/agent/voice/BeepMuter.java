package com.suvo.agent.voice;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Silences the "tok tok" start/stop sounds the phone's speech recognizer plays. Android has no
 * switch for them, so the streams the recognizer uses for these sounds are muted for a moment
 * around the start and the end of listening, then restored. Media is only muted when nothing is
 * playing, so music and videos are never interrupted. Streams the user had muted stay muted.
 */
public final class BeepMuter {

    private static final int[] STREAMS = {AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Integer> muted = new ArrayList<>();
    private static long unmuteAt;
    private static final Runnable UNMUTE = BeepMuter::restore;

    private BeepMuter() {
    }

    /** Mutes the beep streams for {@code ms} milliseconds (extends an active mute). Main thread or any. */
    public static synchronized void muteFor(Context ctx, long ms) {
        AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
        if (am == null) return;
        for (int s : STREAMS) {
            if (muted.contains(s)) continue;
            if (s == AudioManager.STREAM_MUSIC && am.isMusicActive()) continue;
            try {
                if (am.isStreamMute(s)) continue;
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0);
                muted.add(s);
            } catch (RuntimeException ignored) {
                // Some phones refuse (Do Not Disturb rules): then the sound simply plays.
            }
        }
        if (am.isMusicActive() && muted.contains(AudioManager.STREAM_MUSIC)) {
            // Music started meanwhile: give it its sound back at once.
            unmute(am, AudioManager.STREAM_MUSIC);
        }
        save(ctx);
        unmuteAt = Math.max(unmuteAt, android.os.SystemClock.uptimeMillis() + ms);
        MAIN.removeCallbacks(UNMUTE);
        MAIN.postAtTime(UNMUTE, unmuteAt);
        appCtx = ctx.getApplicationContext();
    }

    private static Context appCtx;

    private static synchronized void restore() {
        if (appCtx == null) return;
        AudioManager am = appCtx.getSystemService(AudioManager.class);
        if (am == null) return;
        for (Integer s : new ArrayList<>(muted)) unmute(am, s);
        muted.clear();
        unmuteAt = 0;
        save(appCtx);
    }

    /** Remembers what is muted, so a crash or kill in that second can never leave the phone silent. */
    private static void save(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Integer s : muted) sb.append(s).append(',');
        ctx.getApplicationContext().getSharedPreferences("beep_muter", Context.MODE_PRIVATE).edit().putString("muted", sb.toString()).apply();
    }

    /** Called at app start: un-mutes anything a previous run left muted. */
    public static synchronized void recover(Context ctx) {
        android.content.SharedPreferences p = ctx.getApplicationContext().getSharedPreferences("beep_muter", Context.MODE_PRIVATE);
        String list = p.getString("muted", "");
        if (list.isEmpty()) return;
        AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
        if (am != null) {
            for (String x : list.split(",")) {
                if (x.isEmpty()) continue;
                try {
                    am.adjustStreamVolume(Integer.parseInt(x), AudioManager.ADJUST_UNMUTE, 0);
                } catch (RuntimeException ignored) {
                }
            }
        }
        p.edit().remove("muted").apply();
    }

    private static void unmute(AudioManager am, int s) {
        try {
            am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0);
        } catch (RuntimeException ignored) {
        }
        muted.remove(Integer.valueOf(s));
    }
}
