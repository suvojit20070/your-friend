package com.suvo.agent.core.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class SmartVadTest {

    private static final Random R = new Random(7);

    private static float[] frame(float noiseAmp, float toneAmp, int t) {
        float[] f = new float[SmartVad.FRAME];
        for (int i = 0; i < f.length; i++) {
            float n = (float) (R.nextGaussian() * noiseAmp);
            float s = (float) (toneAmp * Math.sin(2 * Math.PI * 220 * (t * SmartVad.FRAME + i) / SmartVad.RATE));
            f[i] = n + s;
        }
        return f;
    }

    private static List<float[]> run(SmartVad v, float noise, float tone, int ms, int[] t) {
        for (int i = 0; i < ms / 20; i++) v.accept(frame(noise, tone, t[0]++));
        return null;
    }

    @Test
    public void speechBetweenSilenceGivesOneSegment() {
        List<float[]> segs = new ArrayList<>();
        int[] starts = {0};
        SmartVad v = new SmartVad(new SmartVad.Listener() {
            public void onSpeechStart() { starts[0]++; }
            public void onSegment(float[] s) { segs.add(s); }
        });
        int[] t = {0};
        run(v, 0.003f, 0, 1000, t);          // room noise
        run(v, 0.003f, 0.2f, 1500, t);       // talking
        run(v, 0.003f, 0, 300, t);           // short pause inside the sentence
        run(v, 0.003f, 0.2f, 800, t);        // more talking
        run(v, 0.003f, 0, 2000, t);          // done
        assertEquals(1, starts[0]);
        assertEquals(1, segs.size());
        // ~2.6 s of speech + pause + lead-in, minus trimmed tail
        assertTrue(segs.get(0).length > SmartVad.RATE * 2);
    }

    @Test
    public void clickIsIgnored() {
        List<float[]> segs = new ArrayList<>();
        SmartVad v = new SmartVad(new SmartVad.Listener() {
            public void onSpeechStart() { }
            public void onSegment(float[] s) { segs.add(s); }
        });
        int[] t = {0};
        run(v, 0.003f, 0, 1000, t);
        run(v, 0.003f, 0.3f, 60, t);         // 60 ms click
        run(v, 0.003f, 0, 2000, t);
        assertEquals(0, segs.size());
    }

    @Test
    public void echoGuardNeedsLongerSpeech() {
        int[] starts = {0};
        SmartVad v = new SmartVad(new SmartVad.Listener() {
            public void onSpeechStart() { starts[0]++; }
            public void onSegment(float[] s) { }
        });
        v.setEchoGuard(true);
        int[] t = {0};
        run(v, 0.003f, 0, 1000, t);
        run(v, 0.003f, 0.2f, 160, t);        // short burst: not enough while the companion talks
        run(v, 0.003f, 0, 1000, t);
        assertEquals(0, starts[0]);
        run(v, 0.003f, 0.2f, 600, t);        // real talking over it
        assertEquals(1, starts[0]);
    }
}
