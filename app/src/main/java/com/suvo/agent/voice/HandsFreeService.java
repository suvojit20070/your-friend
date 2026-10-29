package com.suvo.agent.voice;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.MainActivity;
import com.suvo.agent.ui.Notifications;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional hands-free conversation. Runs only after the user switches it on in the app (Android
 * requires microphone foreground services to start from the foreground) and shows a persistent
 * "listening" notification with a Stop button. It pauses listening while the companion speaks or a
 * task runs.
 *
 * Listening goes through {@link VoiceGate}, which never takes audio focus, so music and videos keep
 * playing while the microphone waits for you to speak.
 */
public final class HandsFreeService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.handsfree.STOP";

    public interface StateListener {
        void onHandsFreeChanged(boolean running, String status);

        /** Microphone level 0..1, about ten times a second while listening. */
        default void onLevel(float level) {
        }
    }

    private static volatile String lastStatus = "";

    /** The last thing hands-free reported (shown in Voice settings). */
    public static String lastStatus() {
        return lastStatus;
    }

    /** Wake words are matched case-insensitively; empty = every sentence is a command. */
    private long followUpUntil;
    private long lastLevelAt;

    private static volatile boolean running;
    private static volatile HandsFreeService instance;
    /** The assistant card opened by the wake word is on screen: do not listen meanwhile. */
    private boolean assistOpen;
    public static final String SENSITIVITY = "hands_free_wake_sensitivity";
    private static final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceGate gate;
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;
    private boolean waitingForAgent;
    /** Why it stopped by itself (shown in Voice settings), null when you stopped it. */
    private String stopReason;
    private int consecutiveErrors;

    public static boolean isRunning() {
        return running;
    }

    public static void addListener(StateListener l) {
        listeners.addIfAbsent(l);
    }

    public static void removeListener(StateListener l) {
        listeners.remove(l);
    }

    /** True when hands-free may run: consent given and the microphone permission granted. */
    public static boolean canRun(Context ctx) {
        AppGraph g = AppGraph.get();
        return g.settings.bool(Settings.ALLOW_BACKGROUND_LISTENING, false)
                && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts listening. Checks first: a service started with startForegroundService() that stops
     * before calling startForeground() crashes the app ("did not then call startForeground"),
     * and Android refuses a microphone foreground service without the permission.
     */
    public static boolean start(Context ctx) {
        if (!canRun(ctx)) return false;
        Intent i = new Intent(ctx, HandsFreeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
            return true;
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException when the app is not in the foreground.
            lastStatus = String.valueOf(e.getMessage());
            return false;
        }
    }

    /**
     * Hands-free was switched on but is not running (the app was closed or updated, or the phone
     * restarted): start it again. Called when a screen of the app comes to the front, which is the
     * only time Android lets a microphone service start.
     */
    public static void resumeIfWanted(Context ctx) {
        try {
            if (running || !AppGraph.get().settings.bool(Settings.HANDS_FREE, false)) return;
            start(ctx);
        } catch (RuntimeException ignored) {
            // App graph not ready yet.
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, HandsFreeService.class));
    }

    private void publish(String status) {
        lastStatus = status == null ? "" : status;
        for (StateListener l : listeners) l.onHandsFreeChanged(running, status);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppGraph g = AppGraph.get();
        boolean stopAsked = intent != null && ACTION_STOP.equals(intent.getAction());
        if (stopAsked) {
            // Stopped from the notification (a plain service start, already foreground if running):
            // stays off until switched on again.
            g.settings.put(Settings.HANDS_FREE, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent stop = PendingIntent.getService(this, 30, new Intent(this, HandsFreeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 31, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(this, Notifications.CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.handsfree_notification_title))
                .setContentText(getString(R.string.handsfree_notification_text))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(Notifications.ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(Notifications.ID_LISTEN, n);
            }
        } catch (RuntimeException e) {
            // Android refused (e.g. started from the background): do not record.
            stopReason = e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        // startForeground() has been called, so stopping now is safe.
        if (!canRun(this)) {
            stopReason = getString(R.string.handsfree_mic_needed);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            instance = this;
            gate = new VoiceGate(this);
            agentListener = new AgentListener() {
                @Override
                public void onFinished(TaskState task) {
                    waitingForAgent = false;
                    main.postDelayed(HandsFreeService.this::listenIfIdle, 400);
                }
            };
            speechListener = new SpeechController.Listener() {
                @Override
                public void onSpeechState(SpeechController.State state, String detail) {
                    if (state == SpeechController.State.IDLE || state == SpeechController.State.ERROR) {
                        main.postDelayed(HandsFreeService.this::listenIfIdle, 400);
                    } else if (gate != null && gate.isRunning()) {
                        // Do not hear our own voice as a command.
                        gate.pause(true);
                    }
                }
            };
            g.agent.addListener(agentListener);
            g.speech.addListener(speechListener);
            main.post(this::listenIfIdle);
        }
        return START_NOT_STICKY;
    }

    /** Starts the gate once, then only pauses/resumes it around tasks and speech. */
    private void listenIfIdle() {
        if (!running || gate == null) return;
        AppGraph g = AppGraph.get();
        boolean busy = assistOpen || waitingForAgent || g.agent.isRunning() || g.speech.isActive();
        if (gate.isRunning()) {
            gate.pause(busy);
            if (!busy) publish(getString(R.string.handsfree_listening));
            return;
        }
        if (busy) return;
        publish(getString(R.string.handsfree_listening));
        prepareWake();
        gate.start(g.settings.str(Settings.STT_LANGUAGE, ""), new VoiceGate.Callback() {
            @Override
            public void onLevel(float level) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLevelAt < 90) return;
                lastLevelAt = now;
                for (StateListener l : listeners) l.onLevel(level);
            }

            @Override
            public void onPartial(String text) {
                publish("… " + text);
            }

            @Override
            public void onWake() {
                consecutiveErrors = 0;
                openAssistant();
            }

            @Override
            public void onText(String heard) {
                consecutiveErrors = 0;
                java.util.List<String[]> words = com.suvo.agent.core.util.WakeWords.parse(g.settings.str(Settings.HANDS_FREE_WAKE, ""));
                com.suvo.agent.core.util.WakeWords.Match m = com.suvo.agent.core.util.WakeWords.match(heard, words);
                String text;
                long now = android.os.SystemClock.uptimeMillis();
                if (m.triggered) {
                    text = m.command;
                } else if (now < followUpUntil) {
                    text = heard; // said right after "hey tanu" on its own
                } else {
                    publish(getString(R.string.handsfree_ignored, heard));
                    return;
                }
                if (text.isEmpty()) {
                    // Only the wake word: wait a few seconds for the actual request.
                    followUpUntil = now + 8000;
                    publish(getString(R.string.handsfree_wake_heard));
                    com.suvo.agent.character.FloatingCompanionService.react();
                    return;
                }
                followUpUntil = 0;
                publish("“" + text + "”");
                waitingForAgent = true;
                gate.pause(true);
                String err = g.agent.submit(g.agent.activeConversation(), text, null);
                if (err != null) {
                    waitingForAgent = false;
                    publish(err);
                    main.postDelayed(HandsFreeService.this::listenIfIdle, 1500);
                }
            }

            @Override
            public void onError(String message, boolean fatal) {
                publish(message);
                consecutiveErrors++;
                // Only give up on real failures (no permission, no recognizer); recognition
                // errors on single sentences just keep listening.
                if (fatal || consecutiveErrors >= 30) {
                    // Real failure: switch it off, so the switch shows the truth and it does not
                    // restart into the same error every time the app opens.
                    g.settings.put(Settings.HANDS_FREE, false);
                    stopReason = message;
                    stopSelf();
                }
            }
        });
    }

    // ---- Offline wake word (Vosk) -------------------------------------------------------------

    /**
     * Wake-word mode when the offline model is downloaded and a wake word is set: only the word is
     * listened for (offline, works when you speak quietly); hearing it opens the assistant card,
     * which then records the actual request. Loaded in the background; until then (or without the
     * model) every sentence goes to the recognizer as before.
     */
    private void prepareWake() {
        AppGraph g = AppGraph.get();
        String word = com.suvo.agent.core.util.WakeVariants.single(g.settings.str(Settings.HANDS_FREE_WAKE, ""));
        if (word.isEmpty() || !WakeModel.isReady(this)) {
            if (gate != null) gate.setWake(null);
            return;
        }
        int sens = g.settings.integer(SENSITIVITY, 55);
        g.io.execute(() -> {
            try {
                WakeDetector d = WakeDetector.create(this, word, sens);
                main.post(() -> {
                    if (!running || gate == null) {
                        if (d != null) d.close();
                        return;
                    }
                    gate.setWake(d);
                    publish(getString(R.string.handsfree_wake_ready, word));
                });
            } catch (Throwable e) {
                // Model broken / native library missing: keep the normal mode.
                main.post(() -> publish(getString(R.string.handsfree_wake_failed, String.valueOf(e.getMessage()))));
            }
        });
    }

    /** Settings changed (word, sensitivity, model downloaded / deleted): rebuild the detector. */
    public static void reloadWake() {
        HandsFreeService s = instance;
        if (s != null && running) s.main.post(s::prepareWake);
    }

    /** Wake word heard: the assistant card opens and records the request itself. */
    private void openAssistant() {
        assistOpen = true;
        if (gate != null) gate.pause(true);
        publish(getString(R.string.handsfree_wake_heard));
        com.suvo.agent.character.FloatingCompanionService.react();
        Intent i = new Intent(this, com.suvo.agent.ui.AssistActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.suvo.agent.ui.AssistActivity.EXTRA_FROM_WAKE, true);
        boolean canOpen = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || android.provider.Settings.canDrawOverlays(this);
        try {
            if (!canOpen) throw new SecurityException("background start");
            startActivity(i);
        } catch (RuntimeException e) {
            // Android blocks opening screens from the background without "display over other apps":
            // a heads-up notification that opens the card instead.
            PendingIntent pi = PendingIntent.getActivity(this, 32, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = Notifications.builder(this, Notifications.CHANNEL_LISTEN)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(getString(R.string.handsfree_wake_heard))
                    .setContentText(getString(R.string.handsfree_tap_to_talk))
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build();
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.notify(Notifications.ID_LISTEN + 7, n);
            // Nobody opened it: listen again after a while.
            main.postDelayed(() -> {
                if (assistOpen) assistClosed();
            }, 10_000);
        }
    }

    /** Called by the assistant card when it closes: back to listening for the wake word. */
    public static void assistClosed() {
        HandsFreeService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (!s.assistOpen) return;
            s.assistOpen = false;
            s.main.postDelayed(s::listenIfIdle, 500);
        });
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        if (gate != null) gate.stop();
        gate = null;
        try {
            AppGraph g = AppGraph.get();
            if (agentListener != null) g.agent.removeListener(agentListener);
            if (speechListener != null) g.speech.removeListener(speechListener);
        } catch (IllegalStateException ignored) {
        }
        // Keep the reason it stopped visible (Voice settings shows the last status).
        publish(stopReason == null ? getString(R.string.handsfree_stopped) : getString(R.string.handsfree_stopped) + " — " + stopReason);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
