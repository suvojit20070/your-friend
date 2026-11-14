package com.suvo.agent.voice.stt;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Offline speech to text with Whisper through sherpa-onnx (on the phone, no internet). One
 * recognizer is kept loaded while in use; each finished utterance from the voice detector is
 * turned into text. Known Whisper "ghost" lines on noise ("thank you for watching") are dropped.
 */
public final class WhisperStt {

    private static final String TAG = "YF.Whisper";
    private static OfflineRecognizer recognizer;
    private static String loadedKey = "";

    private WhisperStt() {
    }

    /** Loads (or reuses) the recognizer for this model and language. Off the main thread. */
    private static synchronized OfflineRecognizer get(Context c, String id, String language) {
        String key = id + "/" + language;
        if (recognizer != null && key.equals(loadedKey)) return recognizer;
        release();
        OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
        w.setEncoder(SttModels.encoder(c, id).getAbsolutePath());
        w.setDecoder(SttModels.decoder(c, id).getAbsolutePath());
        w.setLanguage(language);
        w.setTask("transcribe");
        OfflineModelConfig m = new OfflineModelConfig();
        m.setWhisper(w);
        m.setTokens(SttModels.tokens(c, id).getAbsolutePath());
        m.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        m.setDebug(false);
        m.setModelType("whisper");
        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setModelConfig(m);
        recognizer = new OfflineRecognizer(null, cfg);
        loadedKey = key;
        return recognizer;
    }

    public static synchronized void release() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Throwable ignored) {
            }
        }
        recognizer = null;
        loadedKey = "";
    }

    /** "hi-IN" → "hi"; empty = let Whisper detect the language. */
    public static String whisperLanguage(String sttLanguage) {
        if (sttLanguage == null) return "";
        String t = sttLanguage.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        int dash = t.indexOf('-');
        if (dash < 0) dash = t.indexOf('_');
        return dash > 0 ? t.substring(0, dash) : t;
    }

    /** Text for one utterance (16 kHz mono); "" when nothing useful was said. */
    public static String transcribe(Context c, String id, String language, float[] samples) {
        OfflineStream s = null;
        try {
            OfflineRecognizer r = get(c, id, language);
            synchronized (WhisperStt.class) {
                s = r.createStream();
                s.acceptWaveform(samples, 16000);
                r.decode(s);
                String text = r.getResult(s).getText();
                return clean(text);
            }
        } catch (Throwable t) {
            Log.w(TAG, "transcribe failed", t);
            return "";
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final String[] GHOSTS = {"thank you for watching", "thanks for watching", "please subscribe",
            "subscribe to my channel", "like and subscribe", "you", "bye.", "[music]", "(music)", "[blank_audio]", "(silence)",
            "subtitles by", "amara.org"};

    static String clean(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ").replaceAll("\\s+", " ").trim();
        String low = t.toLowerCase(Locale.ROOT);
        for (String g : GHOSTS) {
            if (low.equals(g) || low.equals(g + ".") || (g.length() > 8 && low.contains(g))) return "";
        }
        // Only punctuation / one repeated character.
        if (t.replaceAll("[\\p{Punct}\\s।…]", "").isEmpty()) return "";
        return t;
    }
}
