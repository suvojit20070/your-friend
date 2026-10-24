package com.suvo.agent.voice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * Always-on listening that does not interrupt music or videos.
 *
 * Android's SpeechRecognizer takes audio focus every time it opens the microphone, which pauses
 * whatever is playing. VoiceGate instead records with its own {@link AudioRecord} (recording never
 * takes audio focus, like a call recorder) and only detects whether someone is speaking. When speech
 * starts, the recognizer is given that audio:
 *
 * <ul>
 *   <li>Android 13+: through {@code EXTRA_AUDIO_SOURCE}, so the recognizer never opens the mic
 *   itself. The 0.6 s before speech was detected is included, so the first word is not cut.</li>
 *   <li>Older versions, or recognizers that refuse an audio source: VoiceGate pauses its recorder
 *   and lets the recognizer listen normally, but only once speech has started, so focus is taken
 *   for the length of one sentence instead of every few seconds.</li>
 * </ul>
 *
 * All callbacks arrive on the main thread.
 */
public final class VoiceGate {

    private static final String TAG = "YF.VoiceGate";
    private static final int RATE = 16000;
    private static final int FRAME = 320; // 20 ms
    private static final int PRE_ROLL_FRAMES = 30; // 600 ms
    private static final int START_FRAMES = 6; // 120 ms of loud audio starts an utterance
    private static final int END_SILENCE_FRAMES = 45; // 900 ms of quiet ends it
    private static final int MAX_UTTERANCE_FRAMES = 50 * 20; // 20 s

    public interface Callback {
        /** Microphone level 0..1 (for UI). */
        default void onLevel(float level) {
        }

        default void onSpeechStarted() {
        }

        default void onPartial(String text) {
        }

        void onText(String text);

        void onError(String message, boolean fatal);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Callback cb;
    private String language = "";
    private volatile boolean running;
    private volatile boolean paused;
    private Thread thread;

    // Utterance state (main thread).
    private SpeechRecognizer recognizer;
    private volatile FileOutputStream pipeOut;
    private volatile boolean feeding;
    private boolean audioSourceUnsupported = Build.VERSION.SDK_INT < 33;
    /** The on-device recognizer failed (often: language not available offline); use the normal one. */
    private boolean onDeviceFailed;
    private boolean usingOnDevice;
    /** Sentences fed through the pipe that produced nothing; after two, use the recognizer's own mic. */
    private int pipeMisses;

    public VoiceGate(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    public void start(String languageTag, Callback callback) {
        stop();
        cb = callback;
        language = languageTag == null ? "" : languageTag;
        running = true;
        paused = false;
        thread = new Thread(this::loop, "yf-voicegate");
        thread.start();
    }

    /** Stops detecting speech (e.g. while the companion is talking) without releasing everything. */
    public void pause(boolean p) {
        paused = p;
        if (p) main.post(this::endUtterance);
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        main.post(() -> {
            endUtterance();
            destroyRecognizer();
        });
    }

    @SuppressLint("MissingPermission")
    private void loop() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone is not available");
            rec.startRecording();
            short[] frame = new short[FRAME];
            ArrayDeque<short[]> preRoll = new ArrayDeque<>();
            float noise = 300f;
            int loud = 0;
            int quiet = 0;
            int uttFrames = 0;
            boolean inSpeech = false;
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = rec.read(frame, 0, FRAME);
                if (n <= 0) continue;
                float rms = rms(frame, n);
                final float level = Math.min(1f, rms / 6000f);
                main.post(() -> {
                    if (cb != null) cb.onLevel(level);
                });
                if (paused) {
                    inSpeech = false;
                    loud = 0;
                    preRoll.clear();
                    continue;
                }
                // Speech = clearly above the room's noise. The floor is low enough for phones whose
                // VOICE_RECOGNITION input has no gain (no AGC).
                boolean isLoud = rms > Math.max(noise * 2.6f, 380f);
                if (!inSpeech) {
                    // Track background noise only while nobody speaks.
                    if (!isLoud) noise = noise * 0.97f + rms * 0.03f;
                    preRoll.addLast(frame.clone());
                    if (preRoll.size() > PRE_ROLL_FRAMES) preRoll.removeFirst();
                    loud = isLoud ? loud + 1 : 0;
                    if (loud >= START_FRAMES) {
                        inSpeech = true;
                        quiet = 0;
                        uttFrames = 0;
                        final ArrayList<short[]> head = new ArrayList<>(preRoll);
                        preRoll.clear();
                        boolean viaPipe = !audioSourceUnsupported;
                        main.post(() -> beginUtterance(head));
                        if (!viaPipe) {
                            // Hand the microphone to the recognizer for this sentence.
                            rec.stop();
                            waitWhileFeedingOrRecognizing();
                            if (!running) break;
                            rec.startRecording();
                            inSpeech = false;
                            loud = 0;
                        }
                    }
                } else {
                    if (feeding) write(frame, n);
                    uttFrames++;
                    quiet = isLoud ? 0 : quiet + 1;
                    if (quiet >= END_SILENCE_FRAMES || uttFrames >= MAX_UTTERANCE_FRAMES) {
                        inSpeech = false;
                        loud = 0;
                        main.post(this::finishFeeding);
                    }
                }
            }
        } catch (RuntimeException e) {
            final String msg = e.getMessage() == null ? "Microphone error" : e.getMessage();
            main.post(() -> {
                if (cb != null) cb.onError(msg, true);
            });
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
        }
    }

    private volatile boolean recognizing;

    private void waitWhileFeedingOrRecognizing() {
        long deadline = System.currentTimeMillis() + 25_000;
        try {
            Thread.sleep(200);
            while (running && recognizing && System.currentTimeMillis() < deadline) Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static float rms(short[] s, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (double) s[i] * s[i];
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    // ---- main thread ----

    private void beginUtterance(ArrayList<short[]> head) {
        if (!running || cb == null) return;
        cb.onSpeechStarted();
        recognizing = true;
        destroyRecognizer();
        recognizer = createRecognizer();
        if (recognizer == null) {
            recognizing = false;
            cb.onError("No speech recognizer is installed on this phone", true);
            return;
        }
        recognizer.setRecognitionListener(listener);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (!language.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        ParcelFileDescriptor readEnd = null;
        if (!audioSourceUnsupported && Build.VERSION.SDK_INT >= 33) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
                pipeOut = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                feeding = true;
                readEnd = pipe[0];
                for (short[] f : head) write(f, f.length);
            } catch (IOException e) {
                Log.w(TAG, "Audio pipe failed, falling back to the recognizer's microphone", e);
                audioSourceUnsupported = true;
                closePipe();
            }
        }
        // When the recognizer opens the microphone itself it plays its start sound; silence it.
        if (readEnd == null) BeepMuter.muteFor(ctx, 1800);
        recognizer.startListening(i);
        if (readEnd != null) {
            // startListening() sent the recognizer its own copy of the read end; ours can go.
            try {
                readEnd.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * The phone's normal recognizer first: it supports far more languages (Bengali, Hindi,
     * Hinglish...). The on-device one is only used when no normal recognizer exists.
     */
    private SpeechRecognizer createRecognizer() {
        usingOnDevice = false;
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) return SpeechRecognizer.createSpeechRecognizer(ctx);
        try {
            if (!onDeviceFailed && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                usingOnDevice = true;
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void finishFeeding() {
        if (!feeding) return;
        closePipe();
        if (recognizer != null) {
            try {
                recognizer.stopListening();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void endUtterance() {
        closePipe();
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        recognizing = false;
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
    }

    private void closePipe() {
        feeding = false;
        FileOutputStream o = pipeOut;
        pipeOut = null;
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void write(short[] f, int n) {
        FileOutputStream o = pipeOut;
        if (o == null) return;
        byte[] b = new byte[n * 2];
        for (int k = 0; k < n; k++) {
            b[2 * k] = (byte) (f[k] & 0xff);
            b[2 * k + 1] = (byte) ((f[k] >> 8) & 0xff);
        }
        try {
            o.write(b);
        } catch (IOException e) {
            // Recognizer closed its end (finished early); stop feeding.
            feeding = false;
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            if (pipeOut == null) BeepMuter.muteFor(ctx, 1500);
        }

        @Override
        public void onError(int error) {
            boolean usedPipe = feeding || pipeOut != null;
            closePipe();
            recognizing = false;
            if (!usedPipe) BeepMuter.muteFor(ctx, 800);
            if (usingOnDevice && (error == 12 || error == 13 || error == 14 || error == SpeechRecognizer.ERROR_CLIENT)) {
                onDeviceFailed = true;
                Log.i(TAG, "On-device recognizer failed (" + error + "); using the normal recognizer");
            }
            if (usedPipe && !audioSourceUnsupported && ++pipeMisses >= 2) {
                // Recognizers that ignore the audio we hand them hear silence: switch to their own mic.
                audioSourceUnsupported = true;
                Log.i(TAG, "No results from the audio pipe twice; switching to the recognizer's microphone");
            }
            if (usedPipe && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_AUDIO
                    || error == 13 /* ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS */ || error == 14 /* ERROR_CANNOT_CHECK_SUPPORT */)) {
                // This recognizer does not accept an external audio source; use its microphone next time.
                audioSourceUnsupported = true;
                Log.i(TAG, "Recognizer refused external audio (" + error + "); switching to its own microphone");
            }
            boolean benign = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (cb != null && !benign) cb.onError(SpeechInput.describe(error), error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        }

        @Override
        public void onResults(Bundle results) {
            boolean usedPipe = feeding || pipeOut != null;
            closePipe();
            recognizing = false;
            if (!usedPipe) BeepMuter.muteFor(ctx, 800);
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = r == null || r.isEmpty() ? "" : r.get(0);
            if (!text.trim().isEmpty()) pipeMisses = 0;
            if (cb != null && !text.trim().isEmpty()) cb.onText(text.trim());
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> r = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (cb != null && r != null && !r.isEmpty()) cb.onPartial(r.get(0));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
