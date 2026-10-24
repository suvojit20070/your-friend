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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Your Friend — Original project by Suvo.
 *
 * Hold-to-talk: records while the finger is down and turns the recording into text when it lifts.
 * On Android 13+ our own recording is handed to the speech recognizer (EXTRA_AUDIO_SOURCE), so it
 * works from the floating character too. Older versions let the recognizer listen directly between
 * {@link #begin} and {@link #end}. Callbacks arrive on the main thread.
 */
public final class PushToTalk {

    public interface Callback {
        default void onLevel(float level) {
        }

        default void onPartial(String text) {
        }

        void onText(String text);

        void onError(String message);
    }

    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 60;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Callback cb;
    private String language = "";
    private volatile boolean recording;
    private Thread thread;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private SpeechRecognizer recognizer;
    private boolean direct;

    public PushToTalk(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isActive() {
        return recording || recognizer != null;
    }

    /** Finger down: start recording. */
    public void begin(String languageTag, Callback callback) {
        cancel();
        cb = callback;
        language = languageTag == null ? "" : languageTag;
        direct = Build.VERSION.SDK_INT < 33;
        if (direct) {
            startRecognizer(null);
            return;
        }
        synchronized (pcm) {
            pcm.reset();
        }
        recording = true;
        thread = new Thread(this::record, "yf-ptt");
        thread.start();
    }

    /** Finger up: stop recording and recognise what was said. */
    public void end() {
        if (direct) {
            BeepMuter.muteFor(ctx, 1500);
            if (recognizer != null) recognizer.stopListening();
            return;
        }
        if (!recording) return;
        recording = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] audio;
        synchronized (pcm) {
            audio = pcm.toByteArray();
        }
        if (audio.length < RATE * 2 / 4) { // under 0.25 s: treat as an accidental press
            if (cb != null) cb.onError("");
            return;
        }
        startRecognizer(audio);
    }

    public void cancel() {
        recording = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone is not available");
            rec.startRecording();
            byte[] buf = new byte[1280];
            long limit = (long) RATE * 2 * MAX_SECONDS;
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double sum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    short s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    sum += (double) s * s;
                }
                final float level = (float) Math.min(1.0, Math.sqrt(sum / Math.max(1, n / 2)) / 6000.0);
                main.post(() -> {
                    if (cb != null) cb.onLevel(level);
                });
                synchronized (pcm) {
                    if (pcm.size() < limit) pcm.write(buf, 0, n);
                }
            }
        } catch (RuntimeException e) {
            recording = false;
            final String msg = e.getMessage() == null ? "Microphone error" : e.getMessage();
            main.post(() -> {
                if (cb != null) cb.onError(msg);
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

    private void startRecognizer(byte[] audio) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            if (cb != null) cb.onError("No speech recognizer is installed on this phone");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        recognizer.setRecognitionListener(listener);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (!language.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        ParcelFileDescriptor readEnd = null;
        if (audio != null && Build.VERSION.SDK_INT >= 33) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readEnd = pipe[0];
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0]);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
                FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                // Write on a worker: the pipe buffer is smaller than a long recording.
                new Thread(() -> {
                    try {
                        out.write(audio);
                        // A little silence tells the recognizer the speech has ended.
                        out.write(new byte[RATE]);
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                        }
                    }
                }, "yf-ptt-feed").start();
            } catch (IOException e) {
                if (cb != null) cb.onError(e.getMessage() == null ? "Audio pipe failed" : e.getMessage());
                return;
            }
        }
        BeepMuter.muteFor(ctx, 1800);
        recognizer.startListening(i);
        if (readEnd != null) {
            try {
                readEnd.close();
            } catch (IOException ignored) {
            }
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
            if (direct && cb != null) cb.onLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            SpeechRecognizer r = recognizer;
            recognizer = null;
            if (r != null) r.destroy();
            if (cb != null) cb.onError(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "" : SpeechInput.describe(error));
        }

        @Override
        public void onResults(Bundle results) {
            SpeechRecognizer r = recognizer;
            recognizer = null;
            if (r != null) r.destroy();
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = list == null || list.isEmpty() ? "" : list.get(0).trim();
            if (cb != null) {
                if (text.isEmpty()) cb.onError("");
                else cb.onText(text);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (cb != null && list != null && !list.isEmpty()) cb.onPartial(list.get(0));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
