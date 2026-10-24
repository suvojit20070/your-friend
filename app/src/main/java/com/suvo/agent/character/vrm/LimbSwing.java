package com.suvo.agent.character.vrm;

/**
 * Your Friend — Original project by Suvo.
 *
 * Arms and legs that swing when the floating character is carried around, like the hair and
 * dress already do. Each limb is a two-stage damped oscillator: the upper part (thigh / upper arm)
 * trails the motion, the lower part (shin / forearm) trails the upper part, which gives the soft
 * "whip" of a real dangling limb. When the motion stops the limbs swing a few times and settle.
 *
 * Input is the same "wind" the window gives the hair (opposite to the drag velocity, x right,
 * y up). Pure Java so it can be unit-tested.
 */
public final class LimbSwing {

    public static final int LEFT_ARM = 0, RIGHT_ARM = 1, LEFT_LEG = 2, RIGHT_LEG = 3;

    // Slightly different natural frequencies so the four limbs never move in lockstep.
    private static final float[] OMEGA = {5.4f, 5.9f, 4.3f, 4.7f};
    private static final float ZETA1 = 0.2f, ZETA2 = 0.28f;
    private static final float STEP = 1f / 120f;

    /** Upper / lower stage positions and velocities, per limb, for the x (sideways) and y (vertical) axes. */
    final float[] ux = new float[4], uy = new float[4], lx = new float[4], ly = new float[4];
    private final float[] uvx = new float[4], uvy = new float[4], lvx = new float[4], lvy = new float[4];

    /** Advances the simulation. {@code driveX/Y} are clamped to ±1.2. */
    public void step(float dt, float driveX, float driveY) {
        float dx = clamp(driveX), dy = clamp(driveY);
        float left = Math.max(0f, Math.min(0.2f, dt));
        while (left > 1e-5f) {
            float h = Math.min(STEP, left);
            left -= h;
            for (int i = 0; i < 4; i++) {
                float w = OMEGA[i], w2 = w * 1.45f;
                // Stage 1 follows the drive.
                uvx[i] += (-w * w * (ux[i] - dx) - 2 * ZETA1 * w * uvx[i]) * h;
                uvy[i] += (-w * w * (uy[i] - dy) - 2 * ZETA1 * w * uvy[i]) * h;
                ux[i] += uvx[i] * h;
                uy[i] += uvy[i] * h;
                // Stage 2 follows stage 1.
                lvx[i] += (-w2 * w2 * (lx[i] - ux[i]) - 2 * ZETA2 * w2 * lvx[i]) * h;
                lvy[i] += (-w2 * w2 * (ly[i] - uy[i]) - 2 * ZETA2 * w2 * lvy[i]) * h;
                lx[i] += lvx[i] * h;
                ly[i] += lvy[i] * h;
            }
        }
    }

    /** Sideways trail of the upper part (+ = toward +x). */
    public float upperX(int limb) {
        return ux[limb];
    }

    /** Vertical trail of the upper part (+ = up, i.e. the body is moving down). */
    public float upperY(int limb) {
        return uy[limb];
    }

    /** How far the lower part lags behind the upper part (drives extra knee / elbow bend). */
    public float whip(int limb) {
        float a = lx[limb] - ux[limb], b = ly[limb] - uy[limb];
        return (float) Math.sqrt(a * a + b * b);
    }

    /** Total motion energy; ~0 when everything has settled. */
    public float energy() {
        float e = 0;
        for (int i = 0; i < 4; i++) {
            e += ux[i] * ux[i] + uy[i] * uy[i] + uvx[i] * uvx[i] * 0.02f + uvy[i] * uvy[i] * 0.02f;
        }
        return e;
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) return 0;
        return Math.max(-1.2f, Math.min(1.2f, v));
    }
}
