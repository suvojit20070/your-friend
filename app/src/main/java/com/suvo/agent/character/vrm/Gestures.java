package com.suvo.agent.character.vrm;

import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * Body gestures the character can play on request (the companion's AI "brain" picks them), on
 * top of the small idle actions it does by itself. The numbers are the renderer's action ids.
 * Pure Java.
 */
public final class Gestures {

    private Gestures() {
    }

    public static final int LOOK_AROUND = 0, TOUCH_HAIR = 1, HANDS_TOGETHER = 2, SWAY = 3, GLANCE = 4, STRETCH = 5,
            WAVE = 6, NOD = 7, SHAKE_HEAD = 8, CHEER = 9, THINK = 10,
            /** Turns around (back to you), looks back over the shoulder, turns to you again. */
            TURN_BACK = 11,
            /** Annoyed: arms crossed, turned away, chin up. */
            POUT = 12,
            /** Dizzy after being shaken: the head circles, the body wobbles. */
            DIZZY = 13,
            /** Sad: shoulders drop, head down, a sigh. */
            SIGH = 14;

    /** Name → id, -1 for none/unknown. */
    public static int parse(String name) {
        if (name == null) return -1;
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "look_around":
                return LOOK_AROUND;
            case "touch_hair":
            case "hair":
                return TOUCH_HAIR;
            case "hands_together":
            case "shy_hands":
                return HANDS_TOGETHER;
            case "sway":
                return SWAY;
            case "glance":
                return GLANCE;
            case "stretch":
                return STRETCH;
            case "wave":
                return WAVE;
            case "nod":
                return NOD;
            case "shake_head":
            case "no":
                return SHAKE_HEAD;
            case "cheer":
            case "yay":
                return CHEER;
            case "think":
                return THINK;
            case "turn_around":
            case "look_back":
                return TURN_BACK;
            case "pout":
            case "arms_crossed":
            case "annoyed":
                return POUT;
            case "dizzy":
                return DIZZY;
            case "sigh":
                return SIGH;
            default:
                return -1;
        }
    }

    /** How long a requested gesture lasts (seconds). */
    public static float length(int g) {
        switch (g) {
            case NOD:
                return 1.7f;
            case SHAKE_HEAD:
                return 1.9f;
            case CHEER:
                return 2.4f;
            case WAVE:
                return 2.8f;
            case THINK:
                return 5f;
            case GLANCE:
                return 3f;
            case TURN_BACK:
                return 6.5f;
            case POUT:
                return 5f;
            case DIZZY:
                return 4f;
            case SIGH:
                return 4.5f;
            default:
                return 4f;
        }
    }
}
