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
    }

    private static volatile boolean running;
    private static final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private VoiceGate gate;
    private AgentListener agentListener;
    private SpeechController.Listener speechListener;
    private boolean waitingForAgent;
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

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, HandsFreeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, HandsFreeService.class));
    }

    private void publish(String status) {
        for (StateListener l : listeners) l.onHandsFreeChanged(running, status);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        AppGraph g = AppGraph.get();
        if (!g.settings.bool(Settings.ALLOW_BACKGROUND_LISTENING, false)
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
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
        boolean busy = waitingForAgent || g.agent.isRunning() || g.speech.isActive();
        if (gate.isRunning()) {
            gate.pause(busy);
            if (!busy) publish(getString(R.string.handsfree_listening));
            return;
        }
        if (busy) return;
        publish(getString(R.string.handsfree_listening));
        gate.start(g.settings.str(Settings.STT_LANGUAGE, ""), new VoiceGate.Callback() {
            @Override
            public void onPartial(String text) {
                publish(text);
            }

            @Override
            public void onText(String text) {
                consecutiveErrors = 0;
                publish(text);
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
                if (fatal || consecutiveErrors >= 8) {
                    stopSelf();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (gate != null) gate.stop();
        gate = null;
        try {
            AppGraph g = AppGraph.get();
            if (agentListener != null) g.agent.removeListener(agentListener);
            if (speechListener != null) g.speech.removeListener(speechListener);
        } catch (IllegalStateException ignored) {
        }
        publish(getString(R.string.handsfree_stopped));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
