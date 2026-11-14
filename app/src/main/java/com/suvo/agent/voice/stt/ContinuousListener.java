package com.suvo.agent.voice.stt;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suvo.agent.core.audio.SmartVad;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your Friend — Original project by Suvo.
 *
 * Call-style listening: the microphone opens once (voice-call source, with the phone's echo
 * canceller and noise suppressor when it has them) and stays open — no restarts, no beeps, no
 * audio-focus grabs. {@link SmartVad} cuts it into utterances; each one becomes text with
 * offline Whisper. Callbacks come on the main thread.
 */
public final class ContinuousListener {

    public interface Callback {
        void onSpeechStart();

        /** Speech ended and is being turned into text (show "…"). */
        void onTranscribing();

        void onText(String text);

        void onLevel(float db);

        void onError(String message);
    }

    private static final String TAG = "YF.Listen";

    private final Context ctx;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService stt = Executors.newSingleThreadExecutor(r -> new Thread(r, "yf-whisper"));
    private volatile boolean running;
    private volatile boolean echoGuard;
    private Thread reader;
    private AudioRecord rec;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private final String model;
    private final String language;

    public ContinuousListener(Context ctx, String model, String language, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.model = model;
        this.language = language;
        this.cb = cb;
    }

    private volatile boolean muted;

    /** Mic muted for a moment: audio is read but thrown away (the recorder stays open, no restart). */
    public void setMuted(boolean on) {
        muted = on;
    }

    /** The companion is talking: the detector needs clearer speech to start. */
    public void setEchoGuard(boolean on) {
        echoGuard = on;
    }

    public boolean isRunning() {
        return running;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (running) return;
        int min = AudioRecord.getMinBufferSize(SmartVad.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SmartVad.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SmartVad.FRAME * 2 * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
            int session = rec.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
            rec.startRecording();
        } catch (RuntimeException e) {
            stopRecorder();
            cb.onError(e.getMessage() == null ? "Microphone unavailable" : e.getMessage());
            return;
        }
        running = true;
        SmartVad vad = new SmartVad(new SmartVad.Listener() {
            @Override
            public void onSpeechStart() {
                main.post(cb::onSpeechStart);
            }

            @Override
            public void onSegment(float[] samples) {
                main.post(cb::onTranscribing);
                stt.execute(() -> {
                    String text;
                    try {
                        text = WhisperStt.transcribe(ctx, model, language, samples);
                    } catch (Throwable t) {
                        text = "";
                        Log.w(TAG, "whisper", t);
                    }
                    String out = text;
                    if (running) main.post(() -> cb.onText(out));
                });
            }

            @Override
            public void onLevel(float db) {
                // ~10 per second is plenty for animations
            }
        });
        reader = new Thread(() -> {
            short[] buf = new short[SmartVad.FRAME];
            int levelTick = 0;
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n < 0) {
                        Log.w(TAG, "read " + n);
                        break;
                    }
                    continue;
                }
                if (muted) {
                    vad.reset();
                    continue;
                }
                float[] f = new float[SmartVad.FRAME];
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    f[i] = buf[i] / 32768f;
                    sum += f[i] * f[i];
                }
                vad.setEchoGuard(echoGuard);
                vad.accept(f);
                if (++levelTick % 5 == 0) {
                    float db = (float) (10 * Math.log10(sum / Math.max(1, n) + 1e-10));
                    main.post(() -> cb.onLevel(db));
                }
            }
        }, "yf-mic");
        reader.start();
        // Load the model now, so the first sentence is not slow.
        stt.execute(() -> {
            try {
                WhisperStt.transcribe(ctx, model, language, new float[SmartVad.RATE / 10]);
            } catch (Throwable t) {
                main.post(() -> cb.onError("Offline speech recognition could not start: " + t));
            }
        });
    }

    public void stop() {
        running = false;
        if (reader != null) {
            try {
                reader.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        stopRecorder();
        stt.shutdown();
    }

    private void stopRecorder() {
        try {
            if (rec != null) {
                rec.stop();
                rec.release();
            }
        } catch (RuntimeException ignored) {
        }
        rec = null;
        try {
            if (aec != null) aec.release();
            if (ns != null) ns.release();
        } catch (RuntimeException ignored) {
        }
        aec = null;
        ns = null;
    }
}
