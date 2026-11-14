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
import android.os.SystemClock;
import android.speech.SpeechRecognizer;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.Notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * A voice call with the companion: the microphone stays open for the whole call. What you say is
 * collected until you pause (short pauses inside a sentence are fine) and then sent; the answer is
 * spoken, and the microphone keeps listening the whole time — while tools run and while the
 * companion talks (talking over it stops it). While a tool runs, a short line says what is going on
 * ("searching the web…"), so you know without looking at the phone.
 *
 * It is a foreground service (microphone), so the call keeps going while the assistant card steps
 * aside for a screen task or the screen locks. It ends with the End button, when you say goodbye,
 * or after a quiet time set in Settings (Voice → Call ends after).
 */
public final class CallService extends Service {

    /** Settings: seconds of silence (no speech, no work) after which the call ends. */
    public static final String TIMEOUT = "call_timeout_sec";
    public static final int DEFAULT_TIMEOUT = 45;
    /** Settings: speak short lines about what a tool is doing during a call. */
    public static final String SPEAK_STATUS = "call_speak_status";

    private static final String ACTION_END = "com.suvo.agent.call.END";
    private static final String EXTRA_CONVERSATION = "conversation";
    /** A pause this long after you stop talking sends what you said. */
    private static final long SEND_PAUSE_MS = 1500;
    private static final int NOTIF_ID = 1014;

    public enum State {LISTENING, HEARING, THINKING, ENDED}

    public interface Listener {
        /** State changes; {@code text} is what was heard so far (HEARING) or sent (THINKING). */
        void onCallState(State state, String text);

        /** Microphone level (dB) for animations. */
        default void onLevel(float rmsDb) {
        }
    }

    /** The assistant card takes what was said (to show it and send it); without a card the call sends it. */
    public interface Sink {
        void onUtterance(String text);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile CallService instance;
    private static volatile Sink sink;
    private static volatile long conversation = -1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechInput rec;
    private final StringBuilder pending = new StringBuilder();
    /** Said while the agent was still working: sent as soon as it is done. */
    private final StringBuilder queued = new StringBuilder();
    private long lastActivity;
    private boolean ended;
    private int errorsInARow;
    private AgentListener agentListener;
    /** What the companion said lately (to ignore our own voice coming back through the mic). */
    private final List<String> spoken = new ArrayList<>();
    private final List<Long> spokenAt = new ArrayList<>();
    private long lastStatusAt;
    private String lastStatusKind = "";

    // ---- Public API ------------------------------------------------------------------------------

    /** Starts (or joins) a call in this chat. Call it while a screen of the app is visible. */
    public static void start(Context ctx, long conversationId) {
        conversation = conversationId;
        Intent i = new Intent(ctx, CallService.class).putExtra(EXTRA_CONVERSATION, conversationId);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void end(Context ctx) {
        CallService s = instance;
        if (s != null) s.main.post(s::finishCall);
    }

    public static boolean active() {
        return instance != null && !instance.ended;
    }

    public static long conversation() {
        return conversation;
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void clearSink(Sink s) {
        if (sink == s) sink = null;
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** The chat changed under a running call (e.g. "new chat"). */
    public static void setConversation(long id) {
        conversation = id;
    }

    // ---- Service ---------------------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END.equals(intent.getAction())) {
            finishCall();
            return START_NOT_STICKY;
        }
        if (intent != null) conversation = intent.getLongExtra(EXTRA_CONVERSATION, conversation);
        PendingIntent endPi = PendingIntent.getService(this, 40, new Intent(this, CallService.class).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 41, new Intent(this, com.suvo.agent.ui.AssistActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(com.suvo.agent.ui.AssistActivity.EXTRA_SHOW_RESULT, true),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(this, Notifications.CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(getString(R.string.call_title))
                .setContentText(getString(R.string.call_text))
                .setOngoing(true)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(new Notification.Action.Builder(null, getString(R.string.call_end), endPi).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_ID, n);
        } catch (RuntimeException e) {
            // Android refused (started from the background): no call.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (instance == null || instance.ended) begin();
        return START_NOT_STICKY;
    }

    private void begin() {
        instance = this;
        ended = false;
        lastActivity = SystemClock.uptimeMillis();
        rec = new SpeechInput(this);
        AppGraph g = AppGraph.get();
        agentListener = new AgentListener() {
            @Override
            public void onToolExecuting(TaskState task, ToolCall call) {
                if (task.conversationId != conversation) return;
                main.post(() -> sayStatus(call.name));
            }

            @Override
            public void onFinished(TaskState task) {
                if (task.conversationId != conversation) return;
                main.post(() -> answered(task));
            }
        };
        g.agent.addListener(agentListener);
        listen();
        main.postDelayed(tick, 1000);
    }

    @Override
    public void onDestroy() {
        if (!ended) finishCall();
        super.onDestroy();
    }

    private void finishCall() {
        if (ended) return;
        ended = true;
        main.removeCallbacksAndMessages(null);
        if (rec != null) rec.cancel();
        AppGraph g = AppGraph.get();
        if (agentListener != null) g.agent.removeListener(agentListener);
        for (Listener l : LISTENERS) l.onCallState(State.ENDED, "");
        if (instance == this) instance = null;
        stopForeground(true);
        stopSelf();
    }

    // ---- Listening loop --------------------------------------------------------------------------

    private void listen() {
        if (ended) return;
        AppGraph g = AppGraph.get();
        rec.start(g.settings.str(Settings.STT_LANGUAGE, ""), new SpeechInput.Callback() {
            @Override
            public void onReady() {
                if (pending.length() == 0) state(State.LISTENING, "");
            }

            @Override
            public void onLevel(float rmsDb) {
                for (Listener l : LISTENERS) l.onLevel(rmsDb);
            }

            @Override
            public void onPartial(String text) {
                if (text.trim().isEmpty() || isEcho(text)) return;
                main.removeCallbacks(send);
                lastActivity = SystemClock.uptimeMillis();
                // Talking over the companion stops it.
                if (g.speech.isActive()) g.speech.stop();
                state(State.HEARING, (pending + " " + text).trim());
            }

            @Override
            public void onFinal(String text) {
                errorsInARow = 0;
                if (!text.trim().isEmpty() && !isEcho(text)) {
                    if (pending.length() > 0) pending.append(' ');
                    pending.append(text.trim());
                    lastActivity = SystemClock.uptimeMillis();
                    main.removeCallbacks(send);
                    main.postDelayed(send, SEND_PAUSE_MS);
                }
                main.postDelayed(CallService.this::listen, 80);
            }

            @Override
            public void onError(int code, String message) {
                if (ended) return;
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    finishCall();
                    return;
                }
                boolean quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                if (!quiet) errorsInARow++;
                if (errorsInARow > 20) {
                    finishCall();
                    return;
                }
                // Nothing said, or the recognizer is busy: just listen again.
                if (pending.length() > 0 && quiet) {
                    main.removeCallbacks(send);
                    main.postDelayed(send, 200);
                }
                main.postDelayed(CallService.this::listen, code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 500 : quiet ? 80 : 400);
            }
        });
    }

    private final Runnable send = this::sendNow;

    private void sendNow() {
        String text = pending.toString().trim();
        pending.setLength(0);
        if (text.isEmpty() || ended) return;
        lastActivity = SystemClock.uptimeMillis();
        AppGraph g = AppGraph.get();
        String low = norm(text);
        if (matches(low, R.string.call_words_end)) {
            say(getString(R.string.call_bye));
            main.postDelayed(this::finishCall, 1800);
            return;
        }
        if (matches(low, R.string.call_words_new_chat)) {
            if (g.agent.isRunning()) g.agent.cancel();
            conversation = g.agent.resetAssistConversation();
            say(getString(R.string.call_new_chat));
            state(State.LISTENING, "");
            return;
        }
        if (g.agent.isRunning()) {
            if (matches(low, R.string.call_words_stop)) {
                g.agent.cancel();
                say(getString(R.string.call_stopped));
                return;
            }
            // Busy: it goes in right after the current answer.
            if (queued.length() > 0) queued.append(' ');
            queued.append(text);
            state(State.THINKING, text);
            return;
        }
        deliver(text);
    }

    private void deliver(String text) {
        state(State.THINKING, text);
        Sink s = sink;
        if (s != null) {
            s.onUtterance(text);
            return;
        }
        AppGraph g = AppGraph.get();
        String err = g.agent.submit(conversation, text, null);
        if (err != null) say(err);
    }

    /** The answer is in: say it (if replies are not spoken anyway), then anything said meanwhile. */
    private void answered(TaskState task) {
        if (ended) return;
        AppGraph g = AppGraph.get();
        lastActivity = SystemClock.uptimeMillis();
        String reply = task.status == TaskState.Status.COMPLETED ? task.finalText.trim() : task.error.trim();
        if (!reply.isEmpty()) {
            remember(reply);
            if (!g.settings.bool(Settings.VOICE_OUTPUT, false) || task.status != TaskState.Status.COMPLETED) g.speech.speak(reply);
        }
        if (queued.length() > 0) {
            String next = queued.toString();
            queued.setLength(0);
            main.postDelayed(() -> deliver(next), 300);
        } else {
            state(State.LISTENING, "");
        }
    }

    /** Ends a quiet call (nobody talked, nothing running, nothing being said) after the set time. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (ended) return;
            AppGraph g = AppGraph.get();
            long timeout = Math.max(10, g.settings.integer(TIMEOUT, DEFAULT_TIMEOUT)) * 1000L;
            boolean busy = g.agent.isRunning() || g.speech.isActive() || pending.length() > 0 || queued.length() > 0;
            if (busy) lastActivity = SystemClock.uptimeMillis();
            else if (SystemClock.uptimeMillis() - lastActivity > timeout) {
                finishCall();
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    // ---- What the companion says during the call -------------------------------------------------

    private void say(String text) {
        remember(text);
        AppGraph.get().speech.speak(text);
    }

    /** A short spoken line about the tool that just started (at most one every few seconds). */
    private void sayStatus(String tool) {
        AppGraph g = AppGraph.get();
        if (ended || !g.settings.bool(SPEAK_STATUS, true) || g.speech.isActive()) return;
        String kind = statusKind(tool);
        if (kind == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastStatusAt < 7000) return;
        if (kind.equals(lastStatusKind) && now - lastStatusAt < 25_000) return;
        int res = statusRes(kind);
        lastStatusAt = now;
        lastStatusKind = kind;
        say(getString(res));
    }

    /** The line to say for a kind of work. */
    static int statusRes(String kind) {
        switch (kind) {
            case "web":
                return R.string.call_say_web;
            case "screen":
                return R.string.call_say_screen;
            case "unlock":
                return R.string.call_say_unlock;
            case "app":
                return R.string.call_say_app;
            case "message":
                return R.string.call_say_message;
            case "team":
                return R.string.call_say_team;
            default:
                return R.string.call_say_working;
        }
    }

    /** Which kind of work a tool is (null = say nothing, e.g. memory). */
    static String statusKind(String tool) {
        if (tool == null) return null;
        String t = tool.toLowerCase(Locale.ROOT);
        if (t.equals("memory") || t.equals("wait") || t.equals("load_skill") || t.equals("list_tools") || t.equals("record_mistake")
                || t.equals("device_status") || t.equals("use_tool")) return null;
        if (t.equals("unlock_screen") || t.equals("lock_screen")) return "unlock";
        if (t.contains("web") || t.equals("browser") || t.equals("https_request") || t.equals("site_api") || t.equals("youtube")) return "web";
        if (t.startsWith("screen") || t.equals("view_image")) return "screen";
        if (t.equals("launch_app") || t.equals("find_installed_apps") || t.equals("open_uri")) return "app";
        if (t.equals("whatsapp") || t.equals("email") || t.equals("auto_reply") || t.equals("instagram") || t.equals("facebook")
                || t.equals("notify")) return "message";
        if (t.equals("delegate_task")) return "team";
        return "work";
    }

    private void state(State s, String text) {
        for (Listener l : LISTENERS) l.onCallState(s, text);
    }

    // ---- Our own voice coming back through the microphone ----------------------------------------

    private void remember(String text) {
        long now = SystemClock.uptimeMillis();
        spoken.add(norm(text));
        spokenAt.add(now);
        while (!spokenAt.isEmpty() && now - spokenAt.get(0) > 120_000) {
            spoken.remove(0);
            spokenAt.remove(0);
        }
    }

    /** True when what the mic heard is mostly words the companion just said (speaker → mic). */
    private boolean isEcho(String heard) {
        AppGraph g = AppGraph.get();
        long now = SystemClock.uptimeMillis();
        boolean recent = g.speech.isActive() || (!spokenAt.isEmpty() && now - spokenAt.get(spokenAt.size() - 1) < 4000);
        if (!recent || spoken.isEmpty()) return false;
        String h = norm(heard);
        if (h.isEmpty()) return false;
        String[] words = h.split(" ");
        if (words.length == 0) return false;
        String said = spoken.get(spoken.size() - 1);
        int hit = 0;
        for (String w : words) if (!w.isEmpty() && said.contains(w)) hit++;
        return hit >= Math.max(2, Math.round(words.length * 0.7f)) || (words.length <= 2 && said.contains(h));
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}“”‘’।]", " ").replaceAll("\\s+", " ").trim();
    }

    /** True when the whole utterance is one of the phrases in the string (comma separated). */
    private boolean matches(String low, int res) {
        for (String w : getString(res).split(",")) {
            String p = norm(w);
            if (!p.isEmpty() && (low.equals(p) || low.equals("ok " + p) || low.equals(p + " please"))) return true;
        }
        return false;
    }
}
