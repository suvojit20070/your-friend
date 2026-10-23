package com.suvo.agent.voice;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android's built-in TextToSpeech, used only when the user selects it (it is NOT OmniVoice and
 * cannot clone a voice). Audio is rendered to a WAV file so the character can lip-sync to it.
 */
public final class SystemTtsEngine implements TtsEngine {

    private final Context ctx;
    private final Settings settings;
    private TextToSpeech tts;
    private volatile int initStatus = Integer.MIN_VALUE;
    private final CountDownLatch ready = new CountDownLatch(1);

    public SystemTtsEngine(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
    }

    private synchronized void init() {
        if (tts != null) return;
        tts = new TextToSpeech(ctx, status -> {
            initStatus = status;
            ready.countDown();
        });
    }

    @Override
    public String id() {
        return "system";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public String notReadyReason() {
        init();
        if (initStatus == TextToSpeech.ERROR) return "No text-to-speech engine is installed on this device";
        return null;
    }

    @Override
    public Pcm synthesize(String text, CancellationToken cancel) throws Exception {
        init();
        if (!ready.await(5, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            throw new IllegalStateException("System text-to-speech did not initialize");
        }
        String lang = settings.str(Settings.VOICE_LANGUAGE, "");
        if (!lang.isEmpty()) tts.setLanguage(Locale.forLanguageTag(lang));
        tts.setSpeechRate(settings.flt(Settings.VOICE_SPEED, 1.0f));
        File out = new File(ctx.getCacheDir(), "tts_" + UUID.randomUUID() + ".wav");
        String id = UUID.randomUUID().toString();
        CountDownLatch done = new CountDownLatch(1);
        final String[] error = new String[1];
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                if (id.equals(utteranceId)) done.countDown();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onError(String utteranceId) {
                error[0] = "Text-to-speech failed";
                done.countDown();
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                error[0] = "Text-to-speech failed (code " + errorCode + ")";
                done.countDown();
            }
        });
        int r = tts.synthesizeToFile(text, new Bundle(), out, id);
        if (r != TextToSpeech.SUCCESS) throw new IllegalStateException("Text-to-speech rejected the text");
        while (!done.await(200, TimeUnit.MILLISECONDS)) {
            if (cancel.isCancelled()) {
                tts.stop();
                throw new CancellationToken.CancelledException("speech stopped");
            }
        }
        try {
            if (error[0] != null) throw new IllegalStateException(error[0]);
            try (InputStream in = new FileInputStream(out)) {
                return Wav.decode(Wav.readAll(in, 50 * 1024 * 1024));
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    @Override
    public synchronized void close() {
        if (tts != null) tts.shutdown();
        tts = null;
    }
}
