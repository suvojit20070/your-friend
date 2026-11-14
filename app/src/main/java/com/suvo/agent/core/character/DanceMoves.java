package com.suvo.agent.core.character;

import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * The companion's dance steps. Each move is a set of joint offsets that follow the beat: given a
 * move and the beat position (in beats, from the music's tempo) it gives the angles to add to the
 * normal standing pose. Angles are degrees in the renderer's camera-facing space (same signs as
 * {@code SitStyles}): upper arm X negative = forward, Y positive = inward, "up" positive = raised
 * (the renderer mirrors it per side); upper leg X negative = thigh forward; knee positive = bent;
 * head pitch positive = down. HIP_X / HIP_Y are metres. TURN turns the whole body (degrees).
 * Pure Java (unit-tested).
 */
public final class DanceMoves {

    private DanceMoves() {
    }

    public static final int HEAD_BOB = 0, SWAY = 1, BOUNCE = 2, CLAP = 3, ARMS_UP = 4, SIDE_STEP = 5, SHIMMY = 6,
            POINT_UP = 7, HIP_SWAY = 8, WAVE_ARMS = 9, SPIN = 10, KICK = 11;
    public static final int COUNT = 12;

    /** Names the AI chooses from (same order as the ids). */
    public static final String[] NAMES = {"head_bob", "sway", "bounce", "clap", "arms_up", "side_step", "shimmy",
            "point_up", "hip_sway", "wave_arms", "spin", "kick"};

    /** One line per move for the AI. */
    public static final String[] DESCRIPTIONS = {
            "nodding the head to the beat, knees bouncing lightly (calm songs)",
            "swaying the body side to side every two beats (slow, romantic)",
            "bouncing on the knees with fists pumping (energetic)",
            "clapping in front of the chest on every beat (happy, upbeat)",
            "both arms raised high, moving with the beat (chorus, drop, hype)",
            "stepping side to side with arm swings (groovy)",
            "quick shoulder shimmy (fun, playful)",
            "pointing up with one hand, then the other (pop, disco)",
            "hands on the hips, hips swaying (sassy, cool)",
            "arms up waving left and right (crowd, anthem)",
            "a full spin, then bouncing (big moment, use rarely)",
            "little kicks forward, one leg then the other (punk, fast)"};

    /** Energy of each move 0..1 (for picking without the AI). */
    public static final float[] ENERGY = {0.2f, 0.25f, 0.8f, 0.55f, 0.85f, 0.5f, 0.6f, 0.65f, 0.45f, 0.75f, 0.9f, 0.85f};

    // Channels
    public static final int HIP_X = 0, HIP_Y = 1, HIP_YAW = 2, HIP_ROLL = 3, SPINE_ROLL = 4, SPINE_X = 5, CHEST_YAW = 6,
            UP_L = 7, UP_R = 8, UA_X_L = 9, UA_X_R = 10, UA_Y_L = 11, UA_Y_R = 12, ELBOW_L = 13, ELBOW_R = 14,
            HEAD_PITCH = 15, HEAD_ROLL = 16, HEAD_YAW = 17, UL_X_L = 18, UL_X_R = 19, LL_X_L = 20, LL_X_R = 21,
            UL_Z_L = 22, UL_Z_R = 23, TURN = 24;
    public static final int CHANNELS = 25;

    /** Upper-body channels (also used while sitting). */
    public static boolean upperBody(int ch) {
        return ch == SPINE_ROLL || ch == SPINE_X || ch == CHEST_YAW || (ch >= UP_L && ch <= HEAD_YAW);
    }

    public static int parse(String name) {
        if (name == null) return -1;
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(n)) return i;
        switch (n) {
            case "nod":
            case "bob":
                return HEAD_BOB;
            case "jump":
            case "pump":
                return BOUNCE;
            case "hands_up":
            case "raise_hands":
                return ARMS_UP;
            case "step":
                return SIDE_STEP;
            case "point":
                return POINT_UP;
            case "wave":
                return WAVE_ARMS;
            case "turn":
            case "half_turn":
                return SPIN;
            default:
                return -1;
        }
    }

    private static final float TWO_PI = (float) (Math.PI * 2);

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    /**
     * Writes the move's offsets at beat position {@code beat} into {@code out} (cleared first).
     * {@code energy} 0..1 scales how big the motion is.
     */
    public static void pose(int move, float beat, float energy, float[] out) {
        java.util.Arrays.fill(out, 0f);
        if (move < 0 || move >= COUNT) return;
        float amp = 0.65f + 0.55f * Math.max(0f, Math.min(1f, energy));
        // u: one soft bump per beat (down on the beat); side: -1..1 over two beats.
        float u = (float) Math.abs(Math.sin(Math.PI * beat));
        float side = (float) Math.sin(Math.PI * beat);
        float fast = (float) Math.sin(TWO_PI * 2f * beat);
        switch (move) {
            case HEAD_BOB:
                out[HEAD_PITCH] = 9f * u;
                out[HIP_Y] = -0.008f * u;
                out[LL_X_L] = out[LL_X_R] = 8f * u;
                out[UL_X_L] = out[UL_X_R] = -4f * u;
                out[SPINE_X] = 2f * u;
                out[HEAD_ROLL] = 3f * side;
                break;
            case SWAY:
                out[HIP_X] = 0.03f * side;
                out[HIP_ROLL] = 5f * side;
                out[SPINE_ROLL] = -7f * side;
                out[HEAD_ROLL] = 6f * side;
                out[UP_L] = 14f + 10f * side;
                out[UP_R] = 14f - 10f * side;
                out[ELBOW_L] = out[ELBOW_R] = 20f;
                out[LL_X_L] = 10f * Math.max(0f, -side);
                out[LL_X_R] = 10f * Math.max(0f, side);
                break;
            case BOUNCE:
                out[HIP_Y] = -0.03f * u;
                out[LL_X_L] = out[LL_X_R] = 22f * u;
                out[UL_X_L] = out[UL_X_R] = -12f * u;
                out[UA_X_L] = out[UA_X_R] = -22f;
                out[ELBOW_L] = 75f + 22f * u;
                out[ELBOW_R] = 75f + 22f * (1f - u);
                out[HEAD_PITCH] = 5f * u;
                out[SPINE_X] = 4f * u;
                break;
            case CLAP: {
                float open = 1f - u; // hands meet on the beat
                out[UA_X_L] = out[UA_X_R] = -50f;
                out[UA_Y_L] = out[UA_Y_R] = 30f - 14f * open;
                out[ELBOW_L] = out[ELBOW_R] = 62f + 10f * open;
                out[HIP_Y] = -0.012f * u;
                out[LL_X_L] = out[LL_X_R] = 9f * u;
                out[HEAD_PITCH] = 4f * u;
                out[HEAD_ROLL] = 4f * side;
                break;
            }
            case ARMS_UP:
                out[UP_L] = 140f + 10f * side;
                out[UP_R] = 140f - 10f * side;
                out[ELBOW_L] = out[ELBOW_R] = 18f + 10f * u;
                out[HIP_X] = 0.02f * side;
                out[HIP_Y] = -0.016f * u;
                out[LL_X_L] = out[LL_X_R] = 12f * u;
                out[HEAD_PITCH] = -8f;
                out[HEAD_ROLL] = 5f * side;
                break;
            case SIDE_STEP:
                out[HIP_X] = 0.06f * side;
                out[UL_Z_L] = 10f * Math.max(0f, -side);
                out[UL_Z_R] = 10f * Math.max(0f, side);
                out[LL_X_L] = out[LL_X_R] = 12f * u;
                out[UA_X_L] = 20f * side;
                out[UA_X_R] = -20f * side;
                out[ELBOW_L] = out[ELBOW_R] = 35f;
                out[HIP_ROLL] = 4f * side;
                out[HEAD_ROLL] = -3f * side;
                break;
            case SHIMMY:
                out[CHEST_YAW] = 14f * fast;
                out[SPINE_ROLL] = 4f * fast;
                out[UP_L] = out[UP_R] = 30f;
                out[UA_X_L] = out[UA_X_R] = -25f;
                out[ELBOW_L] = out[ELBOW_R] = 80f;
                out[LL_X_L] = out[LL_X_R] = 10f * u;
                out[HIP_Y] = -0.012f * u;
                out[HEAD_ROLL] = 4f * fast;
                break;
            case POINT_UP: {
                boolean left = ((int) Math.floor(beat / 2f)) % 2 == 0;
                float hold = smooth((beat % 2f) * 2f);
                float pt = 160f * hold, rest = 35f;
                out[UP_L] = left ? pt : rest;
                out[UP_R] = left ? rest : pt;
                out[ELBOW_L] = left ? 10f : 95f;
                out[ELBOW_R] = left ? 95f : 10f;
                out[UA_X_L] = left ? -10f : 12f;
                out[UA_X_R] = left ? 12f : -10f;
                out[HEAD_PITCH] = -10f * hold;
                out[HEAD_YAW] = (left ? -15f : 15f) * hold;
                out[HIP_Y] = -0.014f * u;
                out[LL_X_L] = out[LL_X_R] = 10f * u;
                out[HIP_X] = (left ? -0.015f : 0.015f) * hold;
                break;
            }
            case HIP_SWAY:
                out[HIP_X] = 0.035f * side;
                out[HIP_ROLL] = 10f * side;
                out[SPINE_ROLL] = -12f * side;
                out[UP_L] = out[UP_R] = 40f;
                out[ELBOW_L] = out[ELBOW_R] = 100f;
                out[UA_X_L] = out[UA_X_R] = 15f;
                out[LL_X_L] = 15f * Math.max(0f, -side);
                out[LL_X_R] = 15f * Math.max(0f, side);
                out[HEAD_ROLL] = 5f * side;
                break;
            case WAVE_ARMS:
                out[UP_L] = 115f + 25f * side;
                out[UP_R] = 115f - 25f * side;
                out[ELBOW_L] = out[ELBOW_R] = 15f;
                out[HIP_X] = -0.02f * side;
                out[SPINE_ROLL] = 8f * side;
                out[HEAD_ROLL] = 5f * side;
                out[LL_X_L] = out[LL_X_R] = 8f * u;
                break;
            case SPIN: {
                // A full turn over the first two beats of every four, then bouncing.
                float t = beat % 4f;
                out[TURN] = 360f * smooth(t / 2f);
                out[UP_L] = out[UP_R] = 70f * (t < 2f ? 1f : 0.4f);
                out[ELBOW_L] = out[ELBOW_R] = 25f;
                out[HIP_Y] = -0.015f * u;
                out[LL_X_L] = out[LL_X_R] = 12f * u;
                break;
            }
            case KICK: {
                boolean left = ((int) Math.floor(beat)) % 2 == 0;
                float k = u;
                out[UL_X_L] = left ? -45f * k : -4f;
                out[UL_X_R] = left ? -4f : -45f * k;
                out[LL_X_L] = left ? 25f * (1f - k) : 14f;
                out[LL_X_R] = left ? 14f : 25f * (1f - k);
                out[UA_X_L] = left ? 25f : -25f;
                out[UA_X_R] = left ? -25f : 25f;
                out[ELBOW_L] = out[ELBOW_R] = 60f;
                out[HIP_Y] = -0.01f * u;
                out[SPINE_X] = -3f * k;
                break;
            }
            default:
                break;
        }
        for (int i = 0; i < CHANNELS; i++) if (i != TURN) out[i] *= amp;
    }
}
