package com.suvo.agent.core.character;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.suvo.agent.core.audio.BeatTracker;

import org.junit.Test;

import java.util.List;
import java.util.Random;

public class DanceTest {

    @Test
    public void everyMoveMovesAndStaysInRange() {
        float[] out = new float[DanceMoves.CHANNELS];
        for (int m = 0; m < DanceMoves.COUNT; m++) {
            float maxAbs = 0;
            for (float b = 0; b < 8; b += 0.05f) {
                DanceMoves.pose(m, b, 1f, out);
                for (int c = 0; c < DanceMoves.CHANNELS; c++) {
                    assertTrue(!Float.isNaN(out[c]));
                    if (c == DanceMoves.HIP_X || c == DanceMoves.HIP_Y) assertTrue(Math.abs(out[c]) < 0.12f);
                    else if (c == DanceMoves.TURN) assertTrue(out[c] >= 0 && out[c] <= 360.01f);
                    else assertTrue(DanceMoves.NAMES[m] + " ch " + c + " " + out[c], Math.abs(out[c]) <= 200f);
                    if (c != DanceMoves.HIP_X && c != DanceMoves.HIP_Y) maxAbs = Math.max(maxAbs, Math.abs(out[c]));
                }
            }
            assertTrue(DanceMoves.NAMES[m], maxAbs > 5f);
        }
    }

    @Test
    public void namesParse() {
        for (int i = 0; i < DanceMoves.COUNT; i++) assertEquals(i, DanceMoves.parse(DanceMoves.NAMES[i]));
        assertEquals(DanceMoves.ARMS_UP, DanceMoves.parse("Hands Up"));
        assertEquals(-1, DanceMoves.parse("moonwalk"));
    }

    @Test
    public void parsesAiAnswer() {
        DanceDirector.Answer a = DanceDirector.parse("```json\n{\"moves\":[\"bounce\",\"clap\",\"moonwalk\",\"arms_up\"],\"bpm\":128,\"energy\":0.8}\n```");
        assertEquals(3, a.moves.size());
        assertEquals(DanceMoves.BOUNCE, (int) a.moves.get(0));
        assertEquals(128f, a.bpm, 0.01f);
        assertEquals(0.8f, a.energy, 0.001f);
        DanceDirector.Answer bad = DanceDirector.parse("I like this song!");
        assertTrue(bad.moves.isEmpty());
        assertEquals(-1f, bad.bpm, 0f);
    }

    @Test
    public void fallbackNeverRepeatsBackToBack() {
        DanceDirector d = new DanceDirector(new Random(4));
        d.setBpm(128);
        for (int r = 0; r < 50; r++) {
            List<Integer> p = d.fallbackPlan(0.7f);
            assertEquals(4, p.size());
            for (int i = 1; i < p.size(); i++) assertTrue(!p.get(i).equals(p.get(i - 1)));
        }
    }

    @Test
    public void movesChangeOnlyOnABar() {
        DanceDirector d = new DanceDirector(new Random(1));
        d.setPlan(java.util.Arrays.asList(DanceMoves.SWAY, DanceMoves.CLAP), 0.5f, 0);
        assertEquals(DanceMoves.SWAY, d.moveAt(1000, 2f));
        // Half way: the plan wants CLAP, but mid-bar it keeps SWAY until the bar starts.
        assertEquals(DanceMoves.SWAY, d.moveAt(16_000, 30.5f));
        assertEquals(DanceMoves.CLAP, d.moveAt(16_200, 32.1f));
        assertTrue(d.needsPlan(31_000));
    }

    @Test
    public void beatTrackerFindsTempo() {
        BeatTracker t = new BeatTracker();
        Random r = new Random(7);
        float bpm = 124f;
        double period = 60_000.0 / bpm;
        // Readings ~20 per second at uneven times; a kick on every beat.
        long time = 0;
        while (time < 12_000) {
            time += 40 + r.nextInt(25);
            double ph = (time % period) / period;
            float e = 0.2f + 0.05f * r.nextFloat() + (ph < 0.12 ? 1.2f : 0f);
            t.accept(e, time);
        }
        assertEquals(bpm, t.bpm(), 3f);
        assertTrue(t.confidence() > 0.3f);
        long anchor = t.beatAnchorMs();
        double off = (anchor % period) / period;
        assertTrue("anchor " + off, off < 0.2 || off > 0.9);
    }

    @Test
    public void beatTrackerSilentOnNoise() {
        BeatTracker t = new BeatTracker();
        Random r = new Random(3);
        long time = 0;
        while (time < 12_000) {
            time += 40 + r.nextInt(25);
            t.accept(0.5f + r.nextFloat(), time);
        }
        assertEquals(0f, t.bpm(), 0f);
    }
}
