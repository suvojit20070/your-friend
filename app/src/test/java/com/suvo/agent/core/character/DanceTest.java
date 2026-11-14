package com.suvo.agent.core.character;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.suvo.agent.core.audio.BeatTracker;

import org.junit.Test;

import java.util.Random;

public class DanceTest {

    private static final String REC = "```json\n{\"mood\":\"romantic\",\"style\":\"slow\",\"dance\":true,\"bpm\":92,\"face\":\"happy\","
            + "\"phrases\":{\"A\":{\"beats\":4,\"keys\":["
            + "{\"t\":0,\"w\":-0.6,\"hp\":[-3,-2,0,-4],\"la\":[40,10,0],\"le\":[30,0]},"
            + "{\"t\":1,\"ra\":[60,20,10],\"re\":[70,30],\"hd\":[5,-12,6]},"
            + "{\"t\":2,\"w\":0.6,\"hp\":[3,-2,0,4],\"la\":[900,0,0],},"   // out of range + trailing comma
            + "{\"t\":3,\"ra\":[10,0,0],\"e\":\"snap\"}]}},"
            + "\"order\":[\"A\",\"rest:2\",\"A\"]}\n```";

    @Test
    public void parsesAndClamps() {
        DanceRecord r = DanceRecord.parse(REC);
        assertNotNull(r);
        assertTrue(r.playable());
        assertEquals(92f, r.bpm, 0.01f);
        assertEquals(10f, r.totalBeats, 0.001f);
        float[] p = new float[DanceRecord.CHANNELS];
        assertEquals(1f, r.sample(0f, p), 0.001f);
        assertEquals(40f, p[DanceRecord.LA_RAISE], 0.01f);
        assertEquals(-0.6f, p[DanceRecord.WEIGHT], 0.01f);
        r.sample(2f, p);
        assertEquals(175f, p[DanceRecord.LA_RAISE], 0.01f); // 900 clamped
        // Half way between two keys of one joint: in between, eased.
        r.sample(1f, p);
        assertTrue(p[DanceRecord.LA_RAISE] > 40f && p[DanceRecord.LA_RAISE] < 175f);
    }

    @Test
    public void jointsKeepTheirOwnTiming() {
        DanceRecord r = DanceRecord.parse(REC);
        float[] p = new float[DanceRecord.CHANNELS];
        // The right arm's first key is at beat 1: at beat 0.5 it is still coming from its last key of the loop.
        r.sample(1f, p);
        assertEquals(60f, p[DanceRecord.RA_RAISE], 0.01f);
        r.sample(3f, p);
        assertEquals(10f, p[DanceRecord.RA_RAISE], 0.01f);
        // Snap: most of the move happens late.
        r.sample(2f, p);
        assertTrue(p[DanceRecord.RA_RAISE] > 55f);
    }

    @Test
    public void restsFadeOutAndLoop() {
        DanceRecord r = DanceRecord.parse(REC);
        float[] p = new float[DanceRecord.CHANNELS];
        assertEquals(0f, r.sample(5f, p), 0.001f);       // middle of the rest (beats 4–6)
        assertEquals(DanceRecord.neutral(DanceRecord.LA_RAISE), p[DanceRecord.LA_RAISE], 0.5f);
        assertEquals(1f, r.sample(7f, p), 0.001f);
        float[] q = new float[DanceRecord.CHANNELS];
        r.sample(3.2f, p);
        r.sample(3.2f + 10f * 3, q);                     // loops
        for (int c = 0; c < DanceRecord.CHANNELS; c++) assertEquals(p[c], q[c], 0.01f);
        for (float b = 0; b < 20; b += 0.07f) {
            r.sample(b, p);
            for (float v : p) assertFalse(Float.isNaN(v));
        }
    }

    @Test
    public void skipWithComfortLine() {
        DanceRecord r = DanceRecord.parse("{\"mood\":\"sad\",\"dance\":false,\"face\":\"sad\",\"say\":\"I'm here with you.\"}");
        assertNotNull(r);
        assertFalse(r.playable());
        assertEquals("I'm here with you.", r.say);
        assertNull(DanceRecord.parse("I love this song!"));
        assertNull(DanceRecord.parse("{\"mood\":\"happy\",\"dance\":true}"));
    }

    @Test
    public void liftedFootCarriesNoWeight() {
        DanceRecord r = DanceRecord.parse("{\"dance\":true,\"phrases\":{\"A\":{\"beats\":4,\"keys\":["
                + "{\"t\":0,\"w\":-1,\"lf\":[0,0,12]},{\"t\":2,\"w\":0,\"lf\":[10,0,0]}]}},\"order\":[\"A\"]}");
        float[] p = new float[DanceRecord.CHANNELS];
        r.sample(0f, p);
        assertTrue(p[DanceRecord.WEIGHT] > 0.5f); // the left foot is in the air: weight on the right
    }

    @Test
    public void promptsCarryTheFormat() {
        String sys = DanceCoach.systemPrompt("Aiko", "Hindi");
        assertTrue(sys.contains("\"dance\":false"));
        assertTrue(sys.contains("la / ra"));
        assertTrue(DanceCoach.firstRequest("Ibadaat", "Prankur", "YouTube", 0).contains("Ibadaat"));
        assertTrue(DanceCoach.improveRequest("x", "", 100, "{}").contains("{}"));
    }

    @Test
    public void beatTrackerFindsTempo() {
        BeatTracker t = new BeatTracker();
        Random r = new Random(7);
        float bpm = 124f;
        double period = 60_000.0 / bpm;
        long time = 0;
        while (time < 12_000) {
            time += 40 + r.nextInt(25);
            double ph = (time % period) / period;
            float e = 0.2f + 0.05f * r.nextFloat() + (ph < 0.12 ? 1.2f : 0f);
            t.accept(e, time);
        }
        assertEquals(bpm, t.bpm(), 3f);
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
