package com.suvo.agent.core.character;

import java.util.Random;

/**
 * Drives {@link RigParams} over time: breathing, blinking, saccades, idle head motion, activity
 * states (idle/listening/thinking/speaking), emotional expressions, touch reactions, look-at, lip
 * sync and spring physics for hair. Pure Java and deterministic for a given seed (unit-tested).
 */
public final class CharacterAnimator {

    public enum Activity {IDLE, LISTENING, THINKING, SPEAKING}

    public enum Expression {NEUTRAL, HAPPY, SAD, SURPRISED, ANGRY, SHY, SLEEPY}

    public enum TouchRegion {HEAD, FACE, BODY}

    private final RigParams p = new RigParams();
    private final RigParams target = new RigParams();
    private final Random rnd;

    private Activity activity = Activity.IDLE;
    private Expression baseExpression = Expression.NEUTRAL;
    private Expression reaction;
    private float reactionTimeLeft;

    private float time;
    private float nextBlink = 2.5f;
    private float blinkPhase = -1f; // -1 = not blinking, else 0..1
    private boolean doubleBlink;
    private float nextSaccade = 1.2f;
    private float gazeX, gazeY;
    private boolean lookActive;
    private float lookX, lookY, lookTimeLeft;

    // Lip-sync input (from audio) and its smoothed value.
    private float mouthInOpen, mouthInForm = 0.5f;
    private float lastMouthInputTime = -10f;

    // Hair spring state.
    private float hairFrontV, hairSideV, hairBackV, prevAngleX, prevBodyX;

    // Tuning from the character spec.
    public float idleIntensity = 1f;
    public float blinkRate = 1f;
    public float expressiveness = 1f;
    public boolean reducedMotion;

    public CharacterAnimator(long seed) {
        rnd = new Random(seed);
    }

    public RigParams params() {
        return p;
    }

    public Activity activity() {
        return activity;
    }

    public Expression expression() {
        return reaction != null ? reaction : baseExpression;
    }

    public void setActivity(Activity a) {
        if (a == activity) return;
        activity = a;
        if (a != Activity.SPEAKING) {
            // Never leave the mouth "stuck" open when speech ends or is interrupted.
            mouthInOpen = 0f;
        }
    }

    private boolean asleep;

    /** Asleep: eyes stay closed, the head droops, breathing slows; no blinking or glancing. */
    public void setAsleep(boolean a) {
        asleep = a;
        if (a) blinkPhase = -1f;
    }

    public boolean isAsleep() {
        return asleep;
    }

    public void setExpression(Expression e) {
        baseExpression = e == null ? Expression.NEUTRAL : e;
    }

    /** Temporary expression (touch reaction, emotion cue) that returns to the base afterwards. */
    public void react(Expression e, float seconds) {
        reaction = e;
        reactionTimeLeft = seconds;
    }

    /** Lip-sync frame from audio playback (0..1). */
    public void setMouthInput(float open, float form) {
        mouthInOpen = clamp(open, 0f, 1f);
        mouthInForm = clamp(form, 0f, 1f);
        lastMouthInputTime = time;
    }

    private final float[] visIn = new float[5];
    private float lastVisTime = -10f;

    /** Mouth shapes a, i, u, e, o measured from the audio playing now. */
    public void setVisemes(float a, float i, float u, float e, float o) {
        visIn[0] = a;
        visIn[1] = i;
        visIn[2] = u;
        visIn[3] = e;
        visIn[4] = o;
        lastVisTime = time;
    }

    public void setListenLevel(float level) {
        target.listenLevel = clamp(level, 0f, 1f);
    }

    /** Look toward a point in normalized character space (-1..1, y down) for a while. */
    public void lookAt(float nx, float ny, float seconds) {
        lookActive = true;
        lookX = clamp(nx, -1f, 1f);
        lookY = clamp(ny, -1f, 1f);
        lookTimeLeft = seconds;
    }

    /** Touch reaction. Repeated pokes make the character annoyed. */
    private int recentPokes;
    private float pokeWindow;

    public Expression touch(TouchRegion region) {
        recentPokes++;
        pokeWindow = 1.6f;
        Expression e;
        if (recentPokes >= 4) e = Expression.ANGRY;
        else if (region == TouchRegion.HEAD) e = Expression.HAPPY;
        else if (region == TouchRegion.FACE) e = Expression.SHY;
        else e = Expression.SURPRISED;
        react(e, region == TouchRegion.HEAD ? 2.2f : 1.6f);
        if (region == TouchRegion.HEAD) blinkPhase = -1f;
        return e;
    }

    public void update(float dt) {
        if (dt <= 0) return;
        dt = Math.min(dt, 0.1f);
        time += dt;
        float motion = reducedMotion ? 0.25f : idleIntensity;
        float ex = expressiveness;

        if (reaction != null) {
            reactionTimeLeft -= dt;
            if (reactionTimeLeft <= 0) reaction = null;
        }
        if (pokeWindow > 0) {
            pokeWindow -= dt;
            if (pokeWindow <= 0) recentPokes = 0;
        }

        // --- Base targets -----------------------------------------------------------------
        float breathe = (float) (0.5 + 0.5 * Math.sin(time * 2 * Math.PI / (activity == Activity.SPEAKING ? 3.0 : asleep ? 5.2 : 3.8)));
        target.breath = breathe;
        target.angleX = motion * (0.10f * (float) Math.sin(time * 0.53) + 0.05f * (float) Math.sin(time * 1.31 + 1.2));
        target.angleY = motion * (0.06f * (float) Math.sin(time * 0.41 + 0.4));
        target.angleZ = motion * (0.07f * (float) Math.sin(time * 0.37 + 2.1));
        target.bodyX = motion * 0.15f * (float) Math.sin(time * 0.29 + 0.7);
        target.bodyZ = motion * 0.08f * (float) Math.sin(time * 0.23 + 1.9);
        target.eyeLOpen = 1f;
        target.eyeROpen = 1f;
        target.eyeSmile = 0f;
        target.browY = 0f;
        target.browForm = 0f;
        target.browInner = 0f;
        target.mouthOpen = 0f;
        target.mouthForm = 0.15f;
        target.mouthWide = 0.5f;
        target.cheek = 0.15f;
        target.sweat = 0f;
        target.sparkle = 0f;
        target.thinkDots = 0f;
        if (activity != Activity.LISTENING) target.listenLevel = 0f;

        // --- Activity -----------------------------------------------------------------------
        switch (activity) {
            case LISTENING:
                target.angleZ += 0.35f * ex;
                target.angleX += 0.08f;
                target.eyeLOpen = target.eyeROpen = 1.08f;
                target.browY = 0.35f * ex;
                target.mouthForm = 0.25f;
                break;
            case THINKING:
                target.angleY += -0.35f * ex;
                target.angleX += 0.25f * ex;
                target.angleZ += -0.2f * ex;
                target.browForm = 0.25f;
                target.browY = 0.2f;
                target.mouthForm = -0.05f;
                target.thinkDots = 1f;
                break;
            case SPEAKING:
                target.angleY += 0.08f * mouthInOpen * motion;
                target.mouthForm = 0.3f;
                break;
            default:
                break;
        }

        // --- Expression ----------------------------------------------------------------------
        switch (expression()) {
            case HAPPY:
                target.eyeSmile = 0.85f * ex;
                target.mouthForm = 1f;
                target.cheek = 0.55f;
                target.browY = 0.3f;
                target.sparkle = 0.6f;
                break;
            case SAD:
                target.eyeLOpen = target.eyeROpen = 0.72f;
                target.browForm = 0.8f * ex;
                target.browInner = 0.7f * ex;
                target.mouthForm = -0.7f * ex;
                target.angleY += -0.2f;
                break;
            case SURPRISED:
                target.eyeLOpen = target.eyeROpen = 1.28f;
                target.browY = 0.9f * ex;
                target.mouthOpen = Math.max(target.mouthOpen, 0.45f * ex);
                target.mouthWide = 0.15f;
                target.mouthForm = 0f;
                break;
            case ANGRY:
                target.browForm = -0.9f * ex;
                target.browY = -0.4f;
                target.browInner = -0.6f;
                target.mouthForm = -0.5f;
                target.eyeLOpen = target.eyeROpen = 0.85f;
                target.cheek = 0.4f;
                target.sweat = 0.6f;
                break;
            case SHY:
                target.cheek = 1f;
                target.eyeBallY = 0.5f;
                target.eyeSmile = 0.35f;
                target.mouthForm = 0.6f;
                target.angleY += 0.15f;
                target.angleZ += 0.2f;
                break;
            case SLEEPY:
                target.eyeLOpen = target.eyeROpen = 0.35f;
                target.browY = -0.2f;
                target.mouthForm = 0f;
                break;
            default:
                break;
        }

        if (asleep) {
            target.eyeLOpen = target.eyeROpen = 0f;
            target.eyeSmile = 0f;
            target.browY = -0.15f;
            target.browForm = 0f;
            target.mouthForm = 0.2f;
            target.angleY += -0.25f;
            target.angleZ += 0.12f;
            target.sparkle = 0f;
            target.thinkDots = 0f;
        }

        // --- Gaze: look-at overrides saccades ------------------------------------------------------
        if (lookActive) {
            lookTimeLeft -= dt;
            if (lookTimeLeft <= 0) lookActive = false;
            target.eyeBallX = lookX;
            target.eyeBallY = lookY;
            target.angleX += lookX * 0.45f;
            target.angleY += lookY * 0.3f;
        } else {
            nextSaccade -= dt;
            if (nextSaccade <= 0) {
                float range = activity == Activity.THINKING ? 0.2f : 0.35f;
                gazeX = (rnd.nextFloat() * 2 - 1) * range;
                gazeY = (rnd.nextFloat() * 2 - 1) * range * 0.6f;
                if (activity == Activity.THINKING) {
                    gazeX += 0.55f;
                    gazeY -= 0.5f;
                }
                nextSaccade = 0.8f + rnd.nextFloat() * 2.4f;
            }
            if (expression() != Expression.SHY) {
                target.eyeBallX = gazeX;
                target.eyeBallY = gazeY;
            }
        }

        // --- Blink ---------------------------------------------------------------------------------
        float blinkClose = 0f;
        boolean eyesShut = expression() == Expression.HAPPY && target.eyeSmile > 0.6f;
        if (blinkPhase < 0) {
            nextBlink -= dt * blinkRate;
            if (nextBlink <= 0 && !eyesShut && !asleep) {
                blinkPhase = 0f;
                doubleBlink = rnd.nextFloat() < 0.15f;
                nextBlink = 1.8f + rnd.nextFloat() * 4.2f;
            }
        } else {
            blinkPhase += dt / 0.14f;
            blinkClose = (float) Math.sin(Math.min(1f, blinkPhase) * Math.PI);
            if (blinkPhase >= 1f) {
                if (doubleBlink) {
                    doubleBlink = false;
                    blinkPhase = -1f;
                    nextBlink = 0.12f;
                } else {
                    blinkPhase = -1f;
                }
            }
        }

        // --- Smoothing toward targets ---------------------------------------------------------------
        float k = 1f - (float) Math.exp(-dt * 7.0);       // general
        float kf = 1f - (float) Math.exp(-dt * 16.0);     // fast (eyes)
        p.angleX += (target.angleX - p.angleX) * k;
        p.angleY += (target.angleY - p.angleY) * k;
        p.angleZ += (target.angleZ - p.angleZ) * k;
        p.bodyX += (target.bodyX - p.bodyX) * k * 0.6f;
        p.bodyZ += (target.bodyZ - p.bodyZ) * k * 0.6f;
        p.breath = target.breath;
        p.eyeBallX += (target.eyeBallX - p.eyeBallX) * kf;
        p.eyeBallY += (target.eyeBallY - p.eyeBallY) * kf;
        p.eyeSmile += (target.eyeSmile - p.eyeSmile) * k;
        float openL = target.eyeLOpen * (1f - blinkClose);
        float openR = target.eyeROpen * (1f - blinkClose);
        p.eyeLOpen += (openL - p.eyeLOpen) * (blinkPhase >= 0 ? 1f : kf);
        p.eyeROpen += (openR - p.eyeROpen) * (blinkPhase >= 0 ? 1f : kf);
        p.browY += (target.browY - p.browY) * k;
        p.browForm += (target.browForm - p.browForm) * k;
        p.browInner += (target.browInner - p.browInner) * k;
        p.cheek += (target.cheek - p.cheek) * k * 0.5f;
        p.sweat += (target.sweat - p.sweat) * k;
        p.sparkle += (target.sparkle - p.sparkle) * k;
        p.thinkDots += (target.thinkDots - p.thinkDots) * k;
        p.listenLevel += (target.listenLevel - p.listenLevel) * kf;

        // Mouth: lip sync while speaking (with a staleness guard), else expression target.
        boolean inputFresh = time - lastMouthInputTime < 0.25f;
        float mouthTarget = target.mouthOpen;
        float wideTarget = target.mouthWide;
        if (activity == Activity.SPEAKING && inputFresh) {
            mouthTarget = Math.max(mouthTarget, mouthInOpen);
            wideTarget = mouthInForm;
        } else if (!inputFresh) {
            mouthInOpen = 0f;
        }
        float km = 1f - (float) Math.exp(-dt * 22.0);
        p.mouthOpen += (mouthTarget - p.mouthOpen) * km;
        p.mouthWide += (wideTarget - p.mouthWide) * km;
        p.mouthForm += (target.mouthForm - p.mouthForm) * k;
        if (p.mouthOpen < 0.003f) p.mouthOpen = 0f;
        boolean visFresh = activity == Activity.SPEAKING && time - lastVisTime < 0.25f;
        float kv = 1f - (float) Math.exp(-dt * 26.0);
        p.visA += (visIn[0] - p.visA) * kv;
        p.visI += (visIn[1] - p.visI) * kv;
        p.visU += (visIn[2] - p.visU) * kv;
        p.visE += (visIn[3] - p.visE) * kv;
        p.visO += (visIn[4] - p.visO) * kv;
        p.visActive += ((visFresh ? 1f : 0f) - p.visActive) * kv;

        // --- Hair springs driven by head/body velocity ---------------------------------------------------
        float vx = (p.angleX - prevAngleX) / dt;
        float vb = (p.bodyX - prevBodyX) / dt;
        prevAngleX = p.angleX;
        prevBodyX = p.bodyX;
        float swayAmp = reducedMotion ? 0.3f : 1f;
        hairFrontV += (-14f * p.hairFront - 3.2f * hairFrontV - vx * 0.9f * swayAmp) * dt;
        hairSideV += (-10f * p.hairSide - 2.4f * hairSideV - (vx + vb) * 1.2f * swayAmp) * dt;
        hairBackV += (-7f * p.hairBack - 1.8f * hairBackV - (vx * 0.6f + vb * 1.5f) * swayAmp) * dt;
        p.hairFront = clamp(p.hairFront + hairFrontV * dt, -1f, 1f);
        p.hairSide = clamp(p.hairSide + hairSideV * dt, -1f, 1f);
        p.hairBack = clamp(p.hairBack + hairBackV * dt, -1f, 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Expression parseExpression(String s) {
        if (s == null) return Expression.NEUTRAL;
        try {
            return Expression.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Expression.NEUTRAL;
        }
    }
}
