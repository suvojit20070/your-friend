package com.suvo.agent.core;

import com.suvo.agent.character.vrm.LimbSwing;
import com.suvo.agent.character.vrm.SitStyles;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Dangling limbs while carried, and sitting styles that follow the mood. */
public class BodyMotionTest {

    @Test
    public void limbsTrailTheMotionThenSettle() {
        LimbSwing l = new LimbSwing();
        for (int i = 0; i < 30; i++) l.step(1 / 30f, -1f, 0f); // dragged to the right: wind to the left
        assertTrue(l.upperX(LimbSwing.LEFT_LEG) < -0.5f);
        assertTrue(l.whip(LimbSwing.LEFT_ARM) >= 0f);
        for (int i = 0; i < 30 * 8; i++) l.step(1 / 30f, 0f, 0f);
        assertTrue("settles", l.energy() < 1e-3f);
    }

    @Test
    public void limbsOvershootAfterAStop() {
        LimbSwing l = new LimbSwing();
        for (int i = 0; i < 60; i++) l.step(1 / 30f, 1f, 0f);
        float min = 1;
        for (int i = 0; i < 30; i++) {
            l.step(1 / 30f, 0f, 0f);
            min = Math.min(min, l.upperX(LimbSwing.RIGHT_LEG));
        }
        assertTrue("swings past rest", min < -0.1f);
    }

    @Test
    public void sadMoodMostlyHugsKnees() {
        SitStyles.Mood sad = new SitStyles.Mood();
        sad.sad = 1f;
        int hug = 0;
        for (int seed = 0; seed < 40; seed++) {
            SitStyles s = new SitStyles(new Random(seed));
            float t = 0;
            for (int i = 0; i < 30 * 6; i++, t += 1 / 30f) s.update(t, 1 / 30f, true, sad);
            if (s.current() == SitStyles.HUG_KNEES) hug++;
        }
        assertTrue("hug " + hug, hug >= 30);
    }

    @Test
    public void stylesChangeOverTimeAndBlend() {
        SitStyles s = new SitStyles(new Random(3));
        SitStyles.Mood calm = new SitStyles.Mood();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        float t = 0;
        float[] last = null;
        for (int i = 0; i < 30 * 120; i++, t += 1 / 30f) {
            last = s.update(t, 1 / 30f, true, calm);
            seen.add(s.current());
        }
        assertTrue("several styles in two minutes: " + seen, seen.size() >= 3);
        float sum = 0;
        for (int k = 0; k < SitStyles.COUNT; k++) sum += s.weight(k);
        assertEquals(1f, sum, 1e-3f);
        assertTrue(last[SitStyles.UL_X_L] < -60f); // still a sitting pose
    }
}
