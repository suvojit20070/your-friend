package com.suvo.agent.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suvo.agent.core.audio.LipSync;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;
import com.suvo.agent.nativebridge.NativeAudio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Text → configured TTS engine → playback with lip sync. Sentences are synthesized one ahead of
 * playback to cut latency. stop() interrupts synthesis and audio immediately and always returns the
 * character to a closed-mouth idle state.
 */
public final class SpeechController {

    private static final String TAG = "YF.Speech";

    public enum State {IDLE, SYNTHESIZING, SPEAKING, ERROR}

    public interface Listener {
        default void onSpeechState(State state, String detail) {
        }

        default void onMouth(float open, float form) {
        }
    }

    private final Context ctx;
    private final Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AudioPlayback playback = new AudioPlayback();
    private final OmniVoiceNativeEngine omniNative;
    private final OmniVoiceServerEngine omniServer;
    private final SystemTtsEngine system;
    private volatile CancellationToken current;
    private volatile State state = State.IDLE;
    private volatile String lastError = "";
    private AudioFocusRequest focusRequest;

    public SpeechController(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        omniNative = new OmniVoiceNativeEngine(ctx, settings);
        omniServer = new OmniVoiceServerEngine(settings);
        system = new SystemTtsEngine(ctx, settings);
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public State state() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    public OmniVoiceNativeEngine nativeEngine() {
        return omniNative;
    }

    public OmniVoiceServerEngine serverEngine() {
        return omniServer;
    }

    public TtsEngine engine() {
        switch (settings.str(Settings.VOICE_ENGINE, "omnivoice_native")) {
            case "omnivoice_server":
                return omniServer;
            case "system":
                return system;
            default:
                return omniNative;
        }
    }

    private void setState(State s, String detail) {
        state = s;
        if (s == State.ERROR) lastError = detail;
        main.post(() -> {
            for (Listener l : listeners) l.onSpeechState(s, detail);
        });
    }

    private void mouth(float open, float form) {
        main.post(() -> {
            for (Listener l : listeners) l.onMouth(open, form);
        });
    }

    public void stop() {
        CancellationToken c = current;
        if (c != null) c.cancel("stopped");
        playback.stop();
        mouth(0f, 0.5f);
    }

    public boolean isActive() {
        return state == State.SYNTHESIZING || state == State.SPEAKING;
    }

    /** Speaks text (interrupting anything already playing). */
    public void speak(String text) {
        stop();
        String clean = forSpeech(text);
        if (clean.isEmpty()) return;
        TtsEngine engine = engine();
        String why = engine.notReadyReason();
        if (why != null) {
            setState(State.ERROR, why);
            return;
        }
        CancellationToken token = new CancellationToken();
        current = token;
        List<String> chunks = chunk(clean, 220);
        LinkedBlockingQueue<Object> ready = new LinkedBlockingQueue<>(2);
        Object end = new Object();
        Thread producer = new Thread(() -> {
            try {
                for (String c : chunks) {
                    if (token.isCancelled()) break;
                    if (state != State.SPEAKING) setState(State.SYNTHESIZING, engine.id());
                    Pcm pcm = engine.synthesize(c, token).normalized();
                    while (!token.isCancelled() && !ready.offer(pcm, 200, TimeUnit.MILLISECONDS)) {
                        // wait for the player
                    }
                }
            } catch (CancellationToken.CancelledException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Synthesis failed", e);
                ready.offer(e);
            } finally {
                ready.offer(end);
            }
        }, "tts-synth");
        Thread consumer = new Thread(() -> {
            boolean focused = requestFocus();
            try {
                while (!token.isCancelled()) {
                    Object o = ready.poll(200, TimeUnit.MILLISECONDS);
                    if (o == null) continue;
                    if (o == end) break;
                    if (o instanceof Exception) {
                        setState(State.ERROR, ((Exception) o).getMessage());
                        return;
                    }
                    Pcm pcm = (Pcm) o;
                    LipSync.Track track = NativeAudio.analyze(pcm, 60);
                    setState(State.SPEAKING, "");
                    playback.play(pcm, track, token, this::mouth);
                }
                setState(State.IDLE, "");
            } catch (InterruptedException e) {
                setState(State.IDLE, "");
            } finally {
                mouth(0f, 0.5f);
                if (focused) abandonFocus();
                if (state != State.ERROR && state != State.IDLE) setState(State.IDLE, "");
                if (current == token) current = null;
            }
        }, "tts-play");
        producer.start();
        consumer.start();
    }

    /** Synthesizes and plays a short test phrase; returns an error or null (blocking). */
    public String test(String phrase) {
        TtsEngine engine = engine();
        String why = engine.notReadyReason();
        if (why != null) return why;
        try {
            CancellationToken t = new CancellationToken();
            current = t;
            setState(State.SYNTHESIZING, engine.id());
            Pcm p = engine.synthesize(phrase, t).normalized();
            setState(State.SPEAKING, "");
            playback.play(p, NativeAudio.analyze(p, 60), t, this::mouth);
            setState(State.IDLE, "");
            return null;
        } catch (Exception e) {
            setState(State.ERROR, e.getMessage());
            return e.getMessage();
        }
    }

    private boolean requestFocus() {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return false;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop();
                })
                .build();
        return am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am != null && focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
    }

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Removes markdown, code and URLs that should not be read aloud. */
    public static String forSpeech(String text) {
        if (text == null) return "";
        String t = CODE_BLOCK.matcher(text).replaceAll(" (code omitted) ");
        t = URL.matcher(t).replaceAll(" link ");
        t = t.replaceAll("[*_`#>|~]+", " ").replaceAll("\\[(.*?)]\\(.*?\\)", "$1");
        t = t.replaceAll("(?m)^\\s*[-•]\\s+", "");
        return t.replaceAll("[ \\t]+", " ").trim();
    }

    /** Splits into sentence-sized chunks (handles Latin, Devanagari danda and CJK punctuation). */
    public static List<String> chunk(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?।॥。！？\\n])\\s*")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + s.length() + 1 > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            while (s.length() > max) {
                int cut = s.lastIndexOf(' ', max);
                if (cut <= 0) cut = max;
                out.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
