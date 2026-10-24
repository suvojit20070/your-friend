package com.suvo.agent.character.vrm;

import java.util.Random;

/**
 * Your Friend — Original project by Suvo.
 *
 * Different ways to sit, chosen by mood and changed every so often, so the floating character
 * sitting on a button or the screen edge never looks like a statue:
 * swinging the legs, legs crossed, leaning back on the hands, hugging the knees (sad / shy /
 * sleepy) and chin in hand (thoughtful). Styles blend smoothly into each other.
 *
 * Each style is a table of joint angles in degrees (display space, see VrmRenderer.pose).
 * Pure Java so the choice logic can be unit-tested.
 */
public final class SitStyles {

    public static final int KICK = 0, CROSSED = 1, LEAN_BACK = 2, HUG_KNEES = 3, CHIN = 4;
    public static final int COUNT = 5;

    // Angle slots
    public static final int UL_X_L = 0, UL_X_R = 1, UL_Z_L = 2, UL_Z_R = 3, LL_X_L = 4, LL_X_R = 5, FOOT_X = 6,
            KICK_AMP = 7, KICK_SPEED = 8, SPINE_X = 9, HEAD_PITCH = 10, HEAD_ROLL = 11,
            UA_X_L = 12, UA_X_R = 13, UA_Y_L = 14, UA_Y_R = 15, UA_Z_L = 16, UA_Z_R = 17,
            ELBOW_L = 18, ELBOW_R = 19, HAND_X = 20, CROSS_KICK = 21;
    public static final int SLOTS = 22;

    /** Upper legs: negative X = thigh forward/up. Lower legs: positive X = knee bent. */
    static final float[][] POSE = new float[COUNT][SLOTS];

    static {
        float[] k = POSE[KICK];
        k[UL_X_L] = -86; k[UL_X_R] = -82; k[LL_X_L] = 80; k[LL_X_R] = 84; k[KICK_AMP] = 9; k[KICK_SPEED] = 1.3f;
        k[SPINE_X] = 6; k[HEAD_PITCH] = 6; k[UA_X_L] = -14; k[UA_X_R] = -14; k[UA_Y_L] = 10; k[UA_Y_R] = 10;
        k[ELBOW_L] = 40; k[ELBOW_R] = 40;

        float[] c = POSE[CROSSED]; // right leg over the left knee, a slow bob of the crossed foot
        c[UL_X_L] = -84; c[UL_X_R] = -100; c[UL_Z_R] = 16; c[LL_X_L] = 82; c[LL_X_R] = 72; c[CROSS_KICK] = 6;
        c[KICK_SPEED] = 0.9f; c[SPINE_X] = 4; c[HEAD_PITCH] = 4; c[HEAD_ROLL] = -4; c[UA_X_L] = -22; c[UA_X_R] = -22;
        c[UA_Y_L] = 16; c[UA_Y_R] = 16; c[ELBOW_L] = 50; c[ELBOW_R] = 50;

        float[] b = POSE[LEAN_BACK]; // hands on the surface behind, legs stretched a little
        b[UL_X_L] = -80; b[UL_X_R] = -78; b[LL_X_L] = 58; b[LL_X_R] = 66; b[KICK_AMP] = 7; b[KICK_SPEED] = 0.8f;
        b[SPINE_X] = -10; b[HEAD_PITCH] = -5; b[UA_X_L] = 32; b[UA_X_R] = 32; b[UA_Z_L] = 12; b[UA_Z_R] = -12;
        b[ELBOW_L] = 6; b[ELBOW_R] = 6; b[HAND_X] = -30;

        float[] h = POSE[HUG_KNEES]; // knees pulled up, arms around them, head down a little
        h[UL_X_L] = -122; h[UL_X_R] = -120; h[UL_Z_L] = 4; h[UL_Z_R] = -4; h[LL_X_L] = 132; h[LL_X_R] = 130;
        h[KICK_AMP] = 0; h[SPINE_X] = 20; h[HEAD_PITCH] = 14; h[HEAD_ROLL] = 5; h[UA_X_L] = -55; h[UA_X_R] = -55;
        h[UA_Y_L] = 30; h[UA_Y_R] = 30; h[ELBOW_L] = 80; h[ELBOW_R] = 80;

        float[] t = POSE[CHIN]; // leaning forward, right hand under the chin
        t[UL_X_L] = -88; t[UL_X_R] = -86; t[LL_X_L] = 86; t[LL_X_R] = 80; t[KICK_AMP] = 4; t[KICK_SPEED] = 0.6f;
        t[SPINE_X] = 16; t[HEAD_PITCH] = 4; t[HEAD_ROLL] = 7; t[UA_X_L] = -16; t[UA_X_R] = -46; t[UA_Y_L] = 12;
        t[UA_Y_R] = 18; t[ELBOW_L] = 45; t[ELBOW_R] = 122;
    }

    /** Mood levels 0..1. */
    public static final class Mood {
        public float happy, sad, think, sleepy, shy;
    }

    private final Random rnd;
    private int current = KICK;
    private float nextChangeAt = 8f;
    private float lastChange = -100f;
    private final float[] weight = new float[COUNT];
    private final float[] mixed = new float[SLOTS];

    public SitStyles(Random rnd) {
        this.rnd = rnd;
        weight[KICK] = 1f;
    }

    public int current() {
        return current;
    }

    /** How much a mood asks for each style (higher = more likely). */
    static float[] preference(Mood m) {
        float[] p = new float[COUNT];
        p[KICK] = 1.0f + m.happy * 2.5f;
        p[CROSSED] = 0.9f + m.shy * 1.2f;
        p[LEAN_BACK] = 0.8f + m.happy * 1.0f + m.sleepy * 1.5f;
        p[HUG_KNEES] = 0.25f + m.sad * 6f + m.shy * 1.5f + m.sleepy * 1.0f;
        p[CHIN] = 0.4f + m.think * 6f;
        float damp = Math.max(m.sad, m.think);
        p[KICK] *= 1f - 0.8f * m.sad;
        p[LEAN_BACK] *= 1f - 0.7f * damp;
        return p;
    }

    /** Weighted random pick, never the style already in use when there is a choice. */
    int pick(Mood m) {
        float[] p = preference(m);
        float sum = 0;
        for (int i = 0; i < COUNT; i++) if (i != current) sum += p[i];
        float r = rnd.nextFloat() * sum;
        for (int i = 0; i < COUNT; i++) {
            if (i == current) continue;
            r -= p[i];
            if (r <= 0) return i;
        }
        return current == KICK ? CROSSED : KICK;
    }

    /**
     * Advances the choice and the blend; returns the mixed angle table for this frame.
     *
     * @param sitting whether the character is sitting at all (style changes only while sitting)
     */
    public float[] update(float time, float dt, boolean sitting, Mood m) {
        if (sitting) {
            float[] p = preference(m);
            // A strong mood that the current style does not fit switches soon, not after the full wait.
            int best = 0;
            for (int i = 1; i < COUNT; i++) if (p[i] > p[best]) best = i;
            boolean misfit = p[best] > 3f && best != current && p[current] < p[best] * 0.35f;
            if (time > nextChangeAt || (misfit && time - lastChange > 2.5f)) {
                current = misfit ? best : pick(m);
                lastChange = time;
                nextChangeAt = time + 9f + rnd.nextFloat() * 10f;
            }
        } else {
            nextChangeAt = Math.max(nextChangeAt, time + 4f); // sit down in the current style, change later
        }
        float k = 1f - (float) Math.exp(-1.8f * Math.max(0f, dt));
        for (int i = 0; i < COUNT; i++) weight[i] += ((i == current ? 1f : 0f) - weight[i]) * k;
        float total = 0;
        for (float w : weight) total += w;
        java.util.Arrays.fill(mixed, 0f);
        for (int i = 0; i < COUNT; i++) {
            float w = total > 1e-6f ? weight[i] / total : (i == current ? 1f : 0f);
            if (w < 1e-4f) continue;
            for (int s = 0; s < SLOTS; s++) mixed[s] += POSE[i][s] * w;
        }
        return mixed;
    }

    /** Weight of one style in the current blend (0..1). */
    public float weight(int style) {
        float total = 0;
        for (float w : weight) total += w;
        return total > 1e-6f ? weight[style] / total : 0f;
    }
}
