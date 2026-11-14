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

        /** Mouth shapes a, i, u, e, o of the sound playing now (from the audio itself). */
        default void onVisemes(float a, float i, float u, float e, float o) {
        }
    }

    private final Context ctx;
    private final Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AudioPlayback playback = new AudioPlayback();
    private final OmniVoiceServerEngine omniServer;
    private final SystemTtsEngine system;
    private final HfSpaceEngine hfSpace;
    private final FishAudioEngine fish;
    private volatile CancellationToken current;
    private volatile State state = State.IDLE;
    private volatile String lastError = "";
    private AudioFocusRequest focusRequest;

    public SpeechController(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        // The on-device engine was removed; anyone who had picked it now gets the phone's own voice.
        String eng = settings.str(Settings.VOICE_ENGINE, "");
        if ("omnivoice_native".equals(eng) || "woof".equals(eng)) settings.put(Settings.VOICE_ENGINE, "system");
        omniServer = new OmniVoiceServerEngine(settings);
        hfSpace = new HfSpaceEngine(settings);
        fish = new FishAudioEngine(settings);
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

    public HfSpaceEngine spaceEngine() {
        return hfSpace;
    }

    /** "Use the phone's voice when the chosen engine fails" (offline, quota used up...). On by default. */
    public static final String FALLBACK_KEY = "voice_fallback_system";

    private boolean fallbackAllowed(TtsEngine e) {
        return e != system && settings.bool(FALLBACK_KEY, true) && system.notReadyReason() == null;
    }

    public FishAudioEngine fishEngine() {
        return fish;
    }

    public OmniVoiceServerEngine serverEngine() {
        return omniServer;
    }

    public TtsEngine engine() {
        switch (settings.str(Settings.VOICE_ENGINE, "system")) {
            case "omnivoice_server":
                return omniServer;
            case "hf_space":
                return hfSpace;
            case "fish_audio":
                return fish;
            default:
                return system;
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

    private final AudioPlayback.MouthCallback mouthCb = new AudioPlayback.MouthCallback() {
        @Override
        public void onMouth(float open, float form) {
            mouth(open, form);
        }

        @Override
        public void onVisemes(float a, float i, float u, float e, float o) {
            main.post(() -> {
                for (Listener l : listeners) l.onVisemes(a, i, u, e, o);
            });
        }
    };

    /** Lip-sync data for one clip: loudness/form plus the a/i/u/e/o mouth shapes. */
    private static LipSync.Track analyzeMouth(Pcm pcm) {
        LipSync.Track t = NativeAudio.analyze(pcm, 60);
        try {
            t.vis = NativeAudio.visemes(pcm, t.hopSamples);
        } catch (RuntimeException ignored) {
        }
        return t;
    }

    public void stop() {
        CancellationToken c = current;
        if (c != null) c.cancel("stopped");
        Talk t = talk;
        if (t != null) t.token.cancel("stopped");
        talk = null;
        playback.stop();
        playback.setVolume(1f);
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
        TtsEngine chosen = engine();
        String why = chosen.notReadyReason();
        if (why != null) {
            if (!fallbackAllowed(chosen)) {
                setState(State.ERROR, why);
                return;
            }
            chosen = system;
        }
        if (chosen == fish) {
            speakStreaming(clean);
            return;
        }
        speakChunks(chosen, clean);
    }

    /** The usual way: each chunk is made in full, then played (the next one is made meanwhile). */
    private void speakChunks(TtsEngine chosen, String clean) {
        CancellationToken token = new CancellationToken();
        current = token;
        LinkedBlockingQueue<Object> texts = new LinkedBlockingQueue<>();
        for (String c : chunk(clean, 220)) texts.add(new Piece(c, false));
        texts.add(END_TEXT);
        runPipeline(chosen, texts, token);
    }

    /** One piece of text to say; {@code quick} = a fixed short line (cached, or the phone's voice now). */
    private static final class Piece {
        final String text;
        final boolean quick;

        Piece(String text, boolean quick) {
            this.text = text;
            this.quick = quick;
        }
    }

    private static final Object END_TEXT = new Object();

    /**
     * Text in, sound out: pieces arrive on {@code texts} (all at once, or bit by bit while the model
     * is still writing) until {@link #END_TEXT}. One thread makes the sound of the next piece while
     * the other plays the current one. Between pieces with nothing to say (tools running), the state
     * is IDLE so the microphone side does not think the companion is still talking.
     */
    private void runPipeline(TtsEngine chosen, LinkedBlockingQueue<Object> texts, CancellationToken token) {
        final TtsEngine[] engineRef = {chosen};
        LinkedBlockingQueue<Object> ready = new LinkedBlockingQueue<>(2);
        Object end = new Object();
        final boolean[] waitingText = {false};
        Thread producer = new Thread(() -> {
            try {
                while (!token.isCancelled()) {
                    waitingText[0] = true;
                    Object o = texts.poll(200, TimeUnit.MILLISECONDS);
                    if (o == null) continue;
                    waitingText[0] = false;
                    if (o == END_TEXT) break;
                    Piece p = (Piece) o;
                    TtsEngine engine = engineRef[0];
                    if (state == State.IDLE || state == State.ERROR) setState(State.SYNTHESIZING, engine.id());
                    Pcm pcm = cached(engine, p.text);
                    if (pcm == null && p.quick && engine != system && system.notReadyReason() == null) {
                        // A fixed line not made with this voice yet: the phone's voice now (no wait),
                        // the real voice is made in the background for next time.
                        prewarm(java.util.Collections.singletonList(p.text));
                        pcm = NativeAudio.normalized(system.synthesize(p.text, token));
                    }
                    if (pcm == null) {
                        try {
                            pcm = NativeAudio.normalized(engine.synthesize(p.text, token));
                            if (p.quick) store(engine, p.text, pcm);
                        } catch (CancellationToken.CancelledException ce) {
                            throw ce;
                        } catch (Exception e) {
                            if (token.isCancelled() || !fallbackAllowed(engine)) throw e;
                            // Network down, quota used up...: finish this reply with the phone's voice.
                            Log.w(TAG, engine.id() + " failed, using system TTS", e);
                            lastError = engine.id() + ": " + e.getMessage();
                            engineRef[0] = system;
                            pcm = NativeAudio.normalized(system.synthesize(p.text, token));
                        }
                    }
                    Object[] item = {pcm, p.text};
                    while (!token.isCancelled() && !ready.offer(item, 200, TimeUnit.MILLISECONDS)) {
                        // wait for the player
                    }
                }
            } catch (CancellationToken.CancelledException | InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Synthesis failed", e);
                ready.offer(e);
            } finally {
                ready.offer(end);
            }
        }, "tts-synth");
        Thread consumer = new Thread(() -> {
            boolean focused = false;
            try {
                while (!token.isCancelled()) {
                    Object o = ready.poll(200, TimeUnit.MILLISECONDS);
                    if (o == null) {
                        // Nothing to say right now (the model is still working): not "talking".
                        if (waitingText[0] && state != State.IDLE) {
                            setState(State.IDLE, "");
                            if (focused) abandonFocus();
                            focused = false;
                        }
                        continue;
                    }
                    if (o == end) break;
                    if (o instanceof Exception) {
                        setState(State.ERROR, ((Exception) o).getMessage());
                        return;
                    }
                    Object[] item = (Object[]) o;
                    Pcm pcm = (Pcm) item[0];
                    if (!focused) focused = requestFocus();
                    LipSync.Track track = analyzeMouth(pcm);
                    setState(State.SPEAKING, "");
                    noteSpoken((String) item[1], pcm);
                    playback.play(pcm, track, token, mouthCb);
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

    // ---- Short fixed lines ("searching the web…", greetings): cached, never kept waiting ----------

    private static final int CACHE_MAX = 64;
    private final java.util.LinkedHashMap<String, Pcm> phraseCache = new java.util.LinkedHashMap<String, Pcm>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Pcm> e) {
            return size() > CACHE_MAX;
        }
    };
    private final java.util.concurrent.ExecutorService warmer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-warm");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final java.util.Set<String> warming = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Which voice an engine speaks with now: a cached line is only reused for the same voice. */
    private String voiceSig(TtsEngine e) {
        StringBuilder b = new StringBuilder(e.id());
        String[] keys = {Settings.VOICE_LANGUAGE, Settings.OMNI_SERVER_URL, Settings.OMNI_SERVER_MODE, Settings.OMNI_SERVER_VOICE,
                HfSpaceEngine.URL_KEY, HfSpaceEngine.API_NAME_KEY, FishAudioEngine.BASE_URL, FishAudioEngine.REFERENCE_ID,
                FishAudioEngine.MODEL, Settings.VOICE_INSTRUCT};
        for (String k : keys) b.append('|').append(settings.str(k, ""));
        b.append('|').append(settings.flt(Settings.VOICE_SPEED, 1f)).append('|').append(settings.integer(Settings.VOICE_STEPS, 0));
        return b.toString();
    }

    private static String sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private java.io.File cacheFile(String key) {
        return new java.io.File(new java.io.File(ctx.getCacheDir(), "tts_phrases"), sha1(key) + ".pcm");
    }

    /** The line said with this engine's current voice, if made before (memory, then disk). */
    private Pcm cached(TtsEngine e, String text) {
        String key = voiceSig(e) + "\n" + text;
        synchronized (phraseCache) {
            Pcm p = phraseCache.get(key);
            if (p != null) return p;
        }
        java.io.File f = cacheFile(key);
        if (!f.isFile()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            int rate = in.readInt();
            int n = in.readInt();
            if (rate <= 0 || n <= 0 || n > 60 * rate) return null;
            float[] s = new float[n];
            for (int i = 0; i < n; i++) s[i] = in.readShort() / 32768f;
            Pcm p = new Pcm(s, rate);
            synchronized (phraseCache) {
                phraseCache.put(key, p);
            }
            return p;
        } catch (java.io.IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private void store(TtsEngine e, String text, Pcm pcm) {
        if (pcm == null || pcm.samples.length == 0) return;
        String key = voiceSig(e) + "\n" + text;
        synchronized (phraseCache) {
            phraseCache.put(key, pcm);
        }
        java.io.File f = cacheFile(key);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            out.writeInt(pcm.sampleRate);
            out.writeInt(pcm.samples.length);
            for (float v : pcm.samples) out.writeShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f))));
        } catch (java.io.IOException ex) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    /**
     * Makes these lines with the chosen voice in the background (once; kept on disk), so they can be
     * said instantly later. Nothing is done for the phone's own voice (it is quick anyway).
     */
    public void prewarm(List<String> lines) {
        TtsEngine e = engine();
        if (e == system || e.notReadyReason() != null) return;
        for (String raw : lines) {
            String text = forSpeech(raw);
            if (text.isEmpty() || text.length() > 200) continue;
            String key = voiceSig(e) + "\n" + text;
            if (!warming.add(key)) continue;
            warmer.execute(() -> {
                try {
                    if (cached(e, text) != null) return;
                    Pcm p = NativeAudio.normalized(e.synthesize(text, new CancellationToken()));
                    store(e, text, p);
                } catch (Exception ex) {
                    Log.w(TAG, "Prewarm failed: " + ex.getMessage());
                } finally {
                    warming.remove(key);
                }
            });
        }
    }

    /**
     * Says a short fixed line right away: with the chosen voice when it was made before, otherwise
     * with the phone's voice (no waiting for a cloud voice). While a reply is being spoken bit by bit,
     * the line is slipped in after the current sentence instead of cutting the reply off.
     */
    public void speakQuick(String text) {
        String clean = forSpeech(text);
        if (clean.isEmpty()) return;
        Talk t = talk;
        if (t != null && t.alive()) {
            t.texts.add(new Piece(clean, true));
            return;
        }
        stop();
        TtsEngine chosen = engine();
        if (chosen.notReadyReason() != null) {
            if (!fallbackAllowed(chosen) && chosen != system) {
                setState(State.ERROR, chosen.notReadyReason());
                return;
            }
            chosen = system;
        }
        CancellationToken token = new CancellationToken();
        current = token;
        LinkedBlockingQueue<Object> texts = new LinkedBlockingQueue<>();
        texts.add(new Piece(clean, true));
        texts.add(END_TEXT);
        runPipeline(chosen, texts, token);
    }

    // ---- What was said lately (to recognise our own voice coming back through the mic) ----------

    private final java.util.ArrayDeque<Object[]> said = new java.util.ArrayDeque<>();

    private void noteSpoken(String text, Pcm pcm) {
        long until = android.os.SystemClock.uptimeMillis() + (long) (1000.0 * pcm.samples.length / Math.max(1, pcm.sampleRate));
        synchronized (said) {
            said.addLast(new Object[]{text, until});
            while (said.size() > 12) said.removeFirst();
        }
    }

    /** Text playing now or finished within {@code withinMs} (empty if none). */
    public String recentlySpoken(long withinMs) {
        long now = android.os.SystemClock.uptimeMillis();
        StringBuilder b = new StringBuilder();
        synchronized (said) {
            for (Object[] s : said) {
                if ((Long) s[1] + withinMs >= now) b.append(s[0]).append(' ');
            }
        }
        return b.toString().trim();
    }

    // ---- Quieter while you might be talking ------------------------------------------------------

    /** Turns the companion's voice down (you made a sound) or back up; stop() also brings it back. */
    public void duck(boolean on) {
        playback.setVolume(on ? 0.25f : 1f);
    }

    // ---- A reply spoken while the model is still writing it --------------------------------------

    private volatile Talk talk;

    /**
     * Starts speaking a reply as it streams in. Feed it the model's text ({@link Talk#delta}),
     * {@link Talk#stepEnd} after each model message, and {@link Talk#finish} at the end. Code,
     * tool calls and JSON are never said; thinking ({@code <think>}) is skipped.
     */
    public Talk startTalk() {
        stop();
        TtsEngine chosen = engine();
        if (chosen.notReadyReason() != null) {
            if (!fallbackAllowed(chosen)) {
                if (chosen != system) setState(State.ERROR, chosen.notReadyReason());
                return null;
            }
            chosen = system;
        }
        CancellationToken token = new CancellationToken();
        Talk t = new Talk(token);
        talk = t;
        current = token;
        // The player waits (quietly, IDLE) until the first sentence is there.
        runPipeline(chosen, t.texts, token);
        return t;
    }

    public final class Talk {
        private final CancellationToken token;
        final LinkedBlockingQueue<Object> texts = new LinkedBlockingQueue<>();
        private final StringBuilder raw = new StringBuilder();
        private int consumed;
        private int pieces;
        private volatile boolean finished;

        Talk(CancellationToken token) {
            this.token = token;
        }

        boolean alive() {
            return !finished && !token.isCancelled();
        }

        /** Something was handed to the voice (so the full reply need not be said again). */
        public synchronized boolean spoke() {
            return pieces > 0;
        }

        public boolean cancelled() {
            return token.isCancelled();
        }

        public synchronized void delta(String d) {
            if (d == null || d.isEmpty() || finished || token.isCancelled()) return;
            raw.append(d);
            flush(false);
        }

        /** One model message is complete (a tool may run next): say what is left of it. */
        public synchronized void stepEnd(String content) {
            if (finished || token.isCancelled()) return;
            // A provider that does not stream: the whole message at once.
            if (raw.length() == 0 && content != null) raw.append(content);
            flush(true);
            raw.setLength(0);
            consumed = 0;
        }

        public synchronized void finish() {
            if (finished) return;
            if (!token.isCancelled()) flush(true);
            finished = true;
            if (talk == this) talk = null;
            texts.add(END_TEXT);
        }

        private void flush(boolean all) {
            String visible = com.suvo.agent.core.audio.SpeakableText.speakable(raw.toString());
            while (consumed < visible.length()) {
                String rest = visible.substring(consumed);
                int cut = com.suvo.agent.core.audio.SpeakableText.cutPoint(rest, pieces == 0 ? 40 : 120, all);
                if (cut <= 0) return;
                String piece = rest.substring(0, cut);
                consumed += cut;
                if (com.suvo.agent.core.audio.SpeakableText.looksLikeMarkup(piece)) continue;
                String clean = forSpeech(piece);
                if (clean.isEmpty() || !clean.matches("(?s).*[\\p{L}\\p{N}].*")) continue;
                enqueue(clean);
            }
        }

        private void enqueue(String clean) {
            pieces++;
            texts.add(new Piece(clean, false));
        }
    }

    private static final Object FALLBACK = new Object();

    /**
     * Fish Audio: the audio streams in and plays as it arrives, on one audio track (no gaps). If it
     * fails before any sound, the reply is said with the phone's voice instead (when allowed).
     */
    private void speakStreaming(String clean) {
        CancellationToken token = new CancellationToken();
        current = token;
        LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
        Object end = new Object();
        final boolean[] gotAudio = {false};
        Thread producer = new Thread(() -> {
            try {
                setState(State.SYNTHESIZING, fish.id());
                for (String c : chunk(clean, 600)) {
                    if (token.isCancelled()) break;
                    fish.stream(c, token, (samples, rate) -> {
                        gotAudio[0] = true;
                        q.put(samples);
                    });
                }
            } catch (CancellationToken.CancelledException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Fish Audio failed", e);
                lastError = fish.id() + ": " + e.getMessage();
                if (!token.isCancelled() && !gotAudio[0] && fallbackAllowed(fish)) q.offer(FALLBACK);
                else if (!token.isCancelled()) q.offer(e);
            } finally {
                q.offer(end);
            }
        }, "tts-fish");
        Thread consumer = new Thread(() -> {
            boolean focused = requestFocus();
            final Exception[] error = {null};
            final boolean[] fallback = {false};
            try {
                playback.playStream(FishAudioEngine.RATE, () -> {
                    while (!token.isCancelled()) {
                        Object o = q.poll(200, TimeUnit.MILLISECONDS);
                        if (o == null) continue;
                        if (o == end) return null;
                        if (o == FALLBACK) {
                            fallback[0] = true;
                            return null;
                        }
                        if (o instanceof Exception) {
                            error[0] = (Exception) o;
                            return null;
                        }
                        if (state != State.SPEAKING) setState(State.SPEAKING, "");
                        return (float[]) o;
                    }
                    return null;
                }, token, mouthCb);
                if (error[0] != null) setState(State.ERROR, error[0].getMessage());
                else if (!fallback[0]) setState(State.IDLE, "");
            } catch (InterruptedException e) {
                setState(State.IDLE, "");
            } finally {
                mouth(0f, 0.5f);
                if (focused) abandonFocus();
                if (current == token) current = null;
            }
            if (fallback[0] && !token.isCancelled()) speakChunks(system, clean);
        }, "tts-fish-play");
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
            Pcm p = NativeAudio.normalized(engine.synthesize(phrase, t));
            setState(State.SPEAKING, "");
            playback.play(p, analyzeMouth(p), t, mouthCb);
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
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(com.suvo.agent.voice.stt.CallAudio.active() ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {
                    // On a call the microphone (speech recognizer) takes focus again and again: that must
                    // not cut the companion off. Only talking over it (CallService) stops it then.
                    if (CallService.active()) return;
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
