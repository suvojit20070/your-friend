package com.suvo.agent.voice;

import android.os.Handler;
import android.os.Looper;

import com.suvo.agent.AppGraph;

/**
 * Your Friend — Original project by Suvo.
 *
 * "Listen after the answer": when the companion has finished answering (and finished saying it out
 * loud), the microphone opens by itself, so the user can simply reply. If nothing is said it closes
 * again after a few seconds.
 */
public final class AutoListen {

    /** Setting (Voice): open the microphone after every answer. */
    public static final String SETTING = "auto_listen_after_reply";
    /** How long the microphone stays open when nothing is said. */
    public static final long QUIET_MS = 7000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AutoListen() {
    }

    public static boolean on(AppGraph g) {
        return g.settings.bool(SETTING, false) && g.settings.bool(com.suvo.agent.data.Settings.ALLOW_VOICE_INPUT, true);
    }

    /** A pending wait (cancel it when the screen goes away or the user acts first). */
    public static final class Pending {
        volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }
    }

    /**
     * Runs {@code open} on the main thread once the reply is no longer being spoken (at most two
     * minutes later), unless cancelled.
     */
    public static Pending afterSpeech(AppGraph g, Runnable open) {
        Pending p = new Pending();
        final long until = System.currentTimeMillis() + 120_000;
        Runnable check = new Runnable() {
            int quietTicks;

            @Override
            public void run() {
                if (p.cancelled || System.currentTimeMillis() > until) return;
                // Speaking starts a moment after the answer ends: wait until it has been quiet for a bit.
                if (g.speech.isActive()) quietTicks = 0;
                else quietTicks++;
                if (quietTicks >= 3) open.run();
                else MAIN.postDelayed(this, 250);
            }
        };
        MAIN.postDelayed(check, 400);
        return p;
    }
}
