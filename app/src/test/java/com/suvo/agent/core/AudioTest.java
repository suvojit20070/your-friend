package com.suvo.agent.core;

import com.suvo.agent.core.audio.LipSync;
import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Wav;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AudioTest {

    private static Pcm tone(double freq, double seconds, int rate, float amp) {
        float[] s = new float[(int) (seconds * rate)];
        for (int i = 0; i < s.length; i++) s[i] = (float) (amp * Math.sin(2 * Math.PI * freq * i / rate));
        return new Pcm(s, rate);
    }

    @Test
    public void wavRoundTrip16() throws IOException {
        Pcm p = tone(440, 0.5, 24000, 0.5f);
        Pcm back = Wav.decode(Wav.encode16(p));
        assertEquals(24000, back.sampleRate);
        assertEquals(p.samples.length, back.samples.length);
        assertEquals(p.samples[100], back.samples[100], 1e-3);
    }

    @Test
    public void decodesStereoFloatAndMixesToMono() throws IOException {
        int frames = 100;
        ByteBuffer b = ByteBuffer.allocate(44 + frames * 8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + frames * 8).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 3).putShort((short) 2).putInt(16000).putInt(16000 * 8).putShort((short) 8).putShort((short) 32);
        b.putInt(0x61746164).putInt(frames * 8);
        for (int i = 0; i < frames; i++) {
            b.putFloat(0.5f);
            b.putFloat(-0.1f);
        }
        Pcm p = Wav.decode(b.array());
        assertEquals(16000, p.sampleRate);
        assertEquals(frames, p.samples.length);
        assertEquals(0.2f, p.samples[0], 1e-6);
    }

    @Test(expected = IOException.class)
    public void rejectsNonWav() throws IOException {
        Wav.decode("hello world, not audio".getBytes());
    }

    @Test
    public void resampleKeepsDuration() {
        Pcm p = tone(220, 1.0, 22050, 0.3f);
        Pcm r = p.resample(16000);
        assertEquals(16000, r.samples.length, 2);
        assertEquals(1.0, r.durationSeconds(), 0.01);
    }

    @Test
    public void lipSyncFollowsLoudnessAndClosesInSilence() {
        int rate = 16000;
        Pcm loud = tone(300, 0.3, rate, 0.8f);
        Pcm silence = new Pcm(new float[(int) (0.5 * rate)], rate);
        LipSync.Track t = LipSync.analyze(Pcm.concat(loud, silence), 60);
        int midLoud = t.frameAt((long) (0.15 * rate));
        int lateSilence = t.frameAt((long) (0.75 * rate));
        assertTrue("mouth opens when loud: " + t.open[midLoud], t.open[midLoud] > 0.5f);
        assertTrue("mouth closes in silence: " + t.open[lateSilence], t.open[lateSilence] < 0.05f);
        // A high tone reads as a wider mouth shape than a low tone.
        LipSync.Track lowT = LipSync.analyze(tone(600, 0.3, rate, 0.6f), 60);
        LipSync.Track highT = LipSync.analyze(tone(2600, 0.3, rate, 0.6f), 60);
        assertTrue(highT.form[10] > lowT.form[10]);
    }
}
