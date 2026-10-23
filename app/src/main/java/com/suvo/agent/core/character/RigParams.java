package com.suvo.agent.core.character;

/**
 * Deformation parameters of the 2D rig (modelled on Live2D's standard parameter set). The renderer
 * reads these every frame; the animator writes them.
 */
public final class RigParams {
    /** Head yaw/pitch/roll, -1..1 (≈ ±30° yaw, ±25° pitch, ±15° roll). */
    public float angleX, angleY, angleZ;
    /** Body sway -1..1 and breathing 0..1. */
    public float bodyX, bodyZ, breath;
    /** Eye openness 0 (closed) .. 1 (open) .. 1.3 (wide). */
    public float eyeLOpen = 1f, eyeROpen = 1f;
    /** Closed-eye "smile" curve 0..1. */
    public float eyeSmile;
    /** Gaze -1..1. */
    public float eyeBallX, eyeBallY;
    /** Brow height -1..1 and form -1 (angry) .. 1 (worried/raised). */
    public float browY, browForm, browInner;
    /** Mouth openness 0..1, form -1 (frown) .. 1 (smile), width 0 (round "o") .. 1 (wide "i"). */
    public float mouthOpen, mouthForm, mouthWide = 0.5f;
    /** Blush 0..1. */
    public float cheek;
    /** Hair sway (spring outputs), roughly -1..1. */
    public float hairFront, hairSide, hairBack;
    /** Sweat / sparkle / thinking dots overlays 0..1. */
    public float sweat, sparkle, thinkDots;
    /** Listening rings (mic level) 0..1. */
    public float listenLevel;

    public void copyFrom(RigParams o) {
        angleX = o.angleX;
        angleY = o.angleY;
        angleZ = o.angleZ;
        bodyX = o.bodyX;
        bodyZ = o.bodyZ;
        breath = o.breath;
        eyeLOpen = o.eyeLOpen;
        eyeROpen = o.eyeROpen;
        eyeSmile = o.eyeSmile;
        eyeBallX = o.eyeBallX;
        eyeBallY = o.eyeBallY;
        browY = o.browY;
        browForm = o.browForm;
        browInner = o.browInner;
        mouthOpen = o.mouthOpen;
        mouthForm = o.mouthForm;
        mouthWide = o.mouthWide;
        cheek = o.cheek;
        hairFront = o.hairFront;
        hairSide = o.hairSide;
        hairBack = o.hairBack;
        sweat = o.sweat;
        sparkle = o.sparkle;
        thinkDots = o.thinkDots;
        listenLevel = o.listenLevel;
    }
}
