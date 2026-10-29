package com.suvo.agent.voice;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;
import com.suvo.agent.data.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The OmniVoice clone voice: a reference recording (ref.wav, 3–10 s recommended) and its exact
 * transcript (ref.txt), plus generation options. The user sets these in the Voice screen and must
 * have the right to use the recorded voice.
 */
public final class VoiceProfile {

    public String refWavPath = "";
    public String refText = "";
    public String language = "";
    public String instruct = "";
    public float speed = 1.0f;
    public int steps = 32;

    public static VoiceProfile fromSettings(Settings s) {
        VoiceProfile v = new VoiceProfile();
        v.refWavPath = s.str(Settings.REF_WAV_PATH, "");
        v.refText = s.str(Settings.REF_TEXT, "");
        v.language = s.str(Settings.VOICE_LANGUAGE, "");
        v.instruct = s.str(Settings.VOICE_INSTRUCT, "");
        v.speed = s.flt(Settings.VOICE_SPEED, 1.0f);
        v.steps = s.integer(Settings.VOICE_STEPS, 32);
        return v;
    }

    public boolean hasReference() {
        return !refWavPath.isEmpty() && new File(refWavPath).isFile();
    }

    /** Loads the reference audio as mono PCM (resampled to 24 kHz, OmniVoice's rate). */
    public Pcm loadReference() throws IOException {
        if (!hasReference()) return null;
        try (InputStream in = new FileInputStream(refWavPath)) {
            Pcm p = Wav.decode(Wav.readAll(in, 20 * 1024 * 1024));
            if (p.durationSeconds() > 30) throw new IOException("Reference audio is longer than 30 s; use a 3–10 s clip");
            return com.suvo.agent.nativebridge.NativeAudio.resample(p, 24000);
        }
    }
}
