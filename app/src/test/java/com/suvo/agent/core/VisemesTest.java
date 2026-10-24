package com.suvo.agent.core;

import com.suvo.agent.core.audio.Pcm;
import com.suvo.agent.core.audio.Visemes;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Vowel-like test signals (two formant peaks over a voice pitch) map to the right mouth shape. */
public class VisemesTest {

    /** A voiced sound: pitch harmonics whose loudness follows two formant resonances. */
    private static Pcm vowel(float f1, float f2) {
        int rate = 24000;
        float[] s = new float[rate / 2];
        double pitch = 210;
        for (int h = 1; h * pitch < 4000; h++) {
            double fh = h * pitch;
            double amp = Math.exp(-Math.pow((fh - f1) / 90, 2)) + 0.6 * Math.exp(-Math.pow((fh - f2) / 140, 2)) + 0.02;
            for (int i = 0; i < s.length; i++) s[i] += (float) (amp * Math.sin(2 * Math.PI * fh * i / rate));
        }
        return new Pcm(s, rate);
    }

    private static float[] mean(float[][] v) {
        float[] m = new float[5];
        int n = v[0].length;
        for (int k = 0; k < 5; k++) {
            for (int f = n / 3; f < n; f++) m[k] += v[k][f];
            m[k] /= (n - n / 3);
        }
        return m;
    }

    private static int argmax(float[] m) {
        int b = 0;
        for (int k = 1; k < m.length; k++) if (m[k] > m[b]) b = k;
        return b;
    }

    @Test
    public void openVowelIsA() {
        assertTrue(argmax(mean(Visemes.analyze(vowel(840, 1430), 400))) == Visemes.A);
    }

    @Test
    public void closeFrontVowelIsI() {
        assertTrue(argmax(mean(Visemes.analyze(vowel(320, 2600), 400))) == Visemes.I);
    }

    @Test
    public void roundedVowelsAreUorO() {
        float[] m = mean(Visemes.analyze(vowel(380, 900), 400));
        assertTrue(m[Visemes.U] + m[Visemes.O] > m[Visemes.A] + m[Visemes.I] + m[Visemes.E]);
    }

    @Test
    public void midFrontVowelIsE() {
        assertTrue(argmax(mean(Visemes.analyze(vowel(520, 2150), 400))) == Visemes.E);
    }
}
