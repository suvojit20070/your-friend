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
import android.speech.SpeechRecognizer;

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
 * "listening" notification with a Stop button. It pauses listening while the companion speaks.
 */
public final class HandsFreeService extends Service {

    public static final String ACTION_STOP = "com.suvo.agent.handsfree.STOP";

    public interface StateListener {
        void onHandsFreeChanged(boolean running, String status);
    }

    private static volatile boolean running;
    private static final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechInput input;
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
            input = new SpeechInput(this);
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
                    }
                }
            };
            g.agent.addListener(agentListener);
            g.speech.addListener(speechListener);
            main.post(this::listenIfIdle);
        }
        return START_NOT_STICKY;
    }

    private void listenIfIdle() {
        if (!running || input == null || input.isListening()) return;
        AppGraph g = AppGraph.get();
        if (waitingForAgent || g.agent.isRunning() || g.speech.isActive()) return;
        publish(getString(R.string.handsfree_listening));
        input.start(g.settings.str(Settings.STT_LANGUAGE, ""), new SpeechInput.Callback() {
            @Override
            public void onFinal(String text) {
                consecutiveErrors = 0;
                if (text == null || text.trim().isEmpty()) {
                    main.postDelayed(HandsFreeService.this::listenIfIdle, 300);
                    return;
                }
                publish(text);
                waitingForAgent = true;
                String err = g.agent.submit(g.agent.activeConversation(), text, null);
                if (err != null) {
                    waitingForAgent = false;
                    publish(err);
                    main.postDelayed(HandsFreeService.this::listenIfIdle, 1500);
                }
            }

            @Override
            public void onError(int code, String message) {
                publish(message);
                boolean benign = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (!benign) consecutiveErrors++;
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || consecutiveErrors >= 5) {
                    stopSelf();
                    return;
                }
                main.postDelayed(HandsFreeService.this::listenIfIdle, benign ? 300 : 2000);
            }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (input != null) input.cancel();
        input = null;
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
