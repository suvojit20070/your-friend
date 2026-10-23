package com.suvo.agent.core;

import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.character.RigParams;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CharacterAnimatorTest {

    private static void run(CharacterAnimator a, float seconds) {
        for (float t = 0; t < seconds; t += 1f / 60f) a.update(1f / 60f);
    }

    @Test
    public void blinksPeriodically() {
        CharacterAnimator a = new CharacterAnimator(42);
        int closedFrames = 0;
        for (int i = 0; i < 60 * 20; i++) {
            a.update(1f / 60f);
            if (a.params().eyeLOpen < 0.3f) closedFrames++;
        }
        assertTrue("should blink a few times in 20 s, closed frames=" + closedFrames, closedFrames >= 3);
        assertTrue("but eyes are mostly open", closedFrames < 60 * 3);
    }

    @Test
    public void lipSyncDrivesMouthOnlyWhileSpeaking() {
        CharacterAnimator a = new CharacterAnimator(1);
        a.setActivity(CharacterAnimator.Activity.SPEAKING);
        for (int i = 0; i < 30; i++) {
            a.setMouthInput(0.9f, 0.8f);
            a.update(1f / 60f);
        }
        assertTrue(a.params().mouthOpen > 0.7f);
        // Speech ends: mouth must close even without a final "0" frame.
        a.setActivity(CharacterAnimator.Activity.IDLE);
        run(a, 0.5f);
        assertEquals(0f, a.params().mouthOpen, 0.02f);
    }

    @Test
    public void staleLipSyncInputDoesNotLeaveMouthOpen() {
        CharacterAnimator a = new CharacterAnimator(2);
        a.setActivity(CharacterAnimator.Activity.SPEAKING);
        a.setMouthInput(1f, 0.5f);
        a.update(1f / 60f);
        // Audio stopped sending frames (e.g. playback interrupted) but state still says SPEAKING.
        run(a, 1.0f);
        assertTrue("mouth=" + a.params().mouthOpen, a.params().mouthOpen < 0.05f);
    }

    @Test
    public void expressionsAndTouchReactions() {
        CharacterAnimator a = new CharacterAnimator(3);
        a.setExpression(CharacterAnimator.Expression.HAPPY);
        run(a, 1f);
        RigParams p = a.params();
        assertTrue(p.eyeSmile > 0.5f);
        assertTrue(p.mouthForm > 0.6f);
        a.setExpression(CharacterAnimator.Expression.NEUTRAL);
        assertEquals(CharacterAnimator.Expression.SHY, a.touch(CharacterAnimator.TouchRegion.FACE));
        run(a, 0.5f);
        assertTrue(a.params().cheek > 0.5f);
        run(a, 3f);
        assertEquals(CharacterAnimator.Expression.NEUTRAL, a.expression());
        // Poking repeatedly annoys the character.
        CharacterAnimator.Expression e = null;
        for (int i = 0; i < 4; i++) e = a.touch(CharacterAnimator.TouchRegion.BODY);
        assertEquals(CharacterAnimator.Expression.ANGRY, e);
    }

    @Test
    public void thinkingAndListeningChangePose() {
        CharacterAnimator a = new CharacterAnimator(4);
        a.setActivity(CharacterAnimator.Activity.THINKING);
        run(a, 1.5f);
        assertTrue(a.params().thinkDots > 0.8f);
        assertTrue(a.params().angleY < -0.1f);
        a.setActivity(CharacterAnimator.Activity.LISTENING);
        a.setListenLevel(0.8f);
        run(a, 1.5f);
        assertTrue(a.params().angleZ > 0.15f);
        assertTrue(a.params().listenLevel > 0.5f);
    }

    @Test
    public void hairRespondsToHeadMotionAndSettles() {
        CharacterAnimator a = new CharacterAnimator(5);
        a.reducedMotion = true;
        a.lookAt(1f, 0f, 0.6f);
        float maxSway = 0;
        for (int i = 0; i < 40; i++) {
            a.update(1f / 60f);
            maxSway = Math.max(maxSway, Math.abs(a.params().hairSide));
        }
        assertTrue("hair should swing when the head turns: " + maxSway, maxSway > 0.01f);
        run(a, 6f);
        assertTrue(Math.abs(a.params().hairSide) < 0.1f);
    }
}
