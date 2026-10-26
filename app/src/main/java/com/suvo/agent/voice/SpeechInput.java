package com.suvo.agent.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Microphone input through Android's SpeechRecognizer (the device's installed recognition service).
 * Must be used from the main thread. Recording happens only between start() and the final result,
 * stop() or cancel().
 */
public final class SpeechInput {

    public interface Callback {
        default void onReady() {
        }

        default void onLevel(float rmsDb) {
        }

        default void onPartial(String text) {
        }

        void onFinal(String text);

        void onError(int code, String message);
    }

    private final Context ctx;
    private SpeechRecognizer recognizer;
    private boolean listening;

    public SpeechInput(Context ctx) {
        this.ctx = ctx;
    }

    public static boolean isAvailable(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    public boolean isListening() {
        return listening;
    }

    public void start(String languageTag, Callback cb) {
        cancel();
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                cb.onReady();
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                cb.onLevel(rmsdB);
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                BeepMuter.muteFor(ctx, 1500); // the "done" sound
            }

            @Override
            public void onError(int error) {
                listening = false;
                BeepMuter.muteFor(ctx, 600);
                cb.onError(error, describe(error));
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                BeepMuter.muteFor(ctx, 600);
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                cb.onFinal(r == null || r.isEmpty() ? "" : r.get(0));
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> r = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) cb.onPartial(r.get(0));
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if (languageTag != null && !languageTag.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
        listening = true;
        BeepMuter.muteFor(ctx, 1600); // the "start listening" sound
        recognizer.startListening(i);
    }

    /** Stops recording and delivers the final result. */
    public void stop() {
        if (recognizer != null && listening) {
            BeepMuter.muteFor(ctx, 1500);
            recognizer.stopListening();
        }
    }

    /** Stops immediately without a result. */
    public void cancel() {
        listening = false;
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
    }

    public static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Recognizer client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is missing";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "The speech recognizer needs a network connection";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Didn't catch that";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer is busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Speech recognition server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech heard";
            case 10:
                return "Too many speech requests, wait a moment";
            case 11:
                return "The speech service disconnected (error 11). Check the internet connection or the Google app's speech settings";
            case 12:
                return "This recognition language is not supported — pick another one in Voice settings";
            case 13:
                return "This recognition language is not downloaded for offline use";
            default:
                return "Speech recognition error " + error;
        }
    }
}
