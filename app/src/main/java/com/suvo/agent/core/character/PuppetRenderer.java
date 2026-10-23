package com.suvo.agent.core.character;

/**
 * Draws an original anime-style bust from {@link Appearance} and {@link RigParams} in a 1000×1200
 * model space. Pseudo-3D: features shift with head yaw/pitch (parallax), the far eye narrows,
 * hair layers move against the face, and hair locks sway with spring physics.
 * All artwork is procedural (no bitmaps), created for this project.
 */
public final class PuppetRenderer {

    public static final float WIDTH = 1000f;
    public static final float HEIGHT = 1200f;

    private static final int LINE = 0xFF2A1F2D;
    private static final int WHITE = 0xFFFFFFFF;

    private final Appearance a;
    private final boolean girl;

    public PuppetRenderer(Appearance appearance) {
        this.a = appearance;
        this.girl = !"boy".equals(appearance.base);
    }

    public Appearance appearance() {
        return a;
    }

    public void draw(Gfx g, RigParams p) {
        float breath = p.breath;
        g.save();
        // Body sway around the bottom centre.
        g.rotate(p.bodyZ * 3f, 500, 1200);
        g.translate(p.bodyX * 14f, 0);

        // Head transform (shared by back hair, head and front hair).
        float headX = 500 + p.angleX * 8f;
        float headY = 470 + p.angleY * 8f - breath * 5f;
        float roll = p.angleZ * 14f;

        g.save();
        g.translate(headX, headY);
        g.rotate(roll, 0, 290);
        drawBackHair(g, p);
        g.restore();

        drawBody(g, p, breath);

        g.save();
        g.translate(headX, headY);
        g.rotate(roll, 0, 290);
        drawHead(g, p);
        drawFrontHair(g, p);
        // Anime convention: brows show through the bangs so expressions stay readable.
        drawBrows(g, p, p.angleX * 36f, p.angleY * 26f);
        drawAccessory(g, p);
        g.restore();

        drawOverlays(g, p, headX, headY);
        g.restore();
    }

    // ------------------------------------------------------------------------------------------
    // Hair (back)

    private int hairDark() {
        return Appearance.shade(a.hair, 0.62f);
    }

    private int hairLight() {
        return Appearance.tint(a.hair, 0.28f);
    }

    private void fillHair(Gfx g, Gfx.Path path, float top, float bottom) {
        if (g.lowQuality()) g.fill(path, a.hair);
        else g.fillLinear(path, 0, top, 0, bottom, hairLight(), Appearance.shade(a.hair, 0.82f));
    }

    private void drawBackHair(Gfx g, RigParams p) {
        float bx = -p.angleX * 22f;
        float by = -p.angleY * 10f;
        float sway = p.hairBack;
        g.save();
        g.translate(bx, by);
        Gfx.Path path = g.path();
        switch (a.hairStyle) {
            case "long": {
                float s = sway * 60f;
                path.moveTo(-170, -230)
                        .cubicTo(-290, -150, -300, 40, -292, 240)
                        .cubicTo(-288, 420, -300 + s * 0.6f, 560, -318 + s, 700)
                        .lineTo(-250 + s, 650).lineTo(-215 + s, 720).lineTo(-160 + s * 0.9f, 640)
                        .lineTo(-90 + s * 0.8f, 700).lineTo(-40 + s * 0.7f, 600)
                        .lineTo(40 + s * 0.7f, 600).lineTo(90 + s * 0.8f, 700).lineTo(160 + s * 0.9f, 640)
                        .lineTo(215 + s, 720).lineTo(250 + s, 650).lineTo(318 + s, 700)
                        .cubicTo(300 + s * 0.6f, 560, 288, 420, 292, 240)
                        .cubicTo(300, 40, 290, -150, 170, -230)
                        .cubicTo(100, -300, -100, -300, -170, -230).close();
                fillHair(g, path, -300, 720);
                g.stroke(path, hairDark(), 3f);
                break;
            }
            case "twintails": {
                // Cap behind the head.
                path.moveTo(-220, -100).cubicTo(-250, -260, 250, -260, 220, -100).cubicTo(240, 60, -240, 60, -220, -100).close();
                fillHair(g, path, -280, 60);
                for (int side = -1; side <= 1; side += 2) {
                    float s = sway * 70f + p.hairSide * 30f * side;
                    Gfx.Path t = g.path();
                    t.moveTo(side * 215, -210)
                            .cubicTo(side * 360, -230, side * 430, -40, side * 405 + s * 0.5f, 220)
                            .cubicTo(side * 390 + s * 0.8f, 420, side * 330 + s, 560, side * 300 + s, 650)
                            .lineTo(side * 280 + s, 560)
                            .cubicTo(side * 270 + s * 0.6f, 600, side * 250 + s * 0.6f, 580, side * 240 + s * 0.5f, 540)
                            .cubicTo(side * 300 + s * 0.4f, 300, side * 300, 60, side * 205, -120).close();
                    fillHair(g, t, -230, 650);
                    g.stroke(t, hairDark(), 3f);
                }
                break;
            }
            case "bob": {
                float s = sway * 25f;
                path.moveTo(-180, -230).cubicTo(-300, -120, -290, 120, -262 + s, 250)
                        .lineTo(-180 + s, 238).lineTo(-120, 250).lineTo(120, 250).lineTo(180 + s, 238).lineTo(262 + s, 250)
                        .cubicTo(290, 120, 300, -120, 180, -230).cubicTo(100, -300, -100, -300, -180, -230).close();
                fillHair(g, path, -300, 260);
                g.stroke(path, hairDark(), 3f);
                break;
            }
            case "spiky": {
                path.moveTo(-200, -205).lineTo(-262, -170).lineTo(-236, -110).lineTo(-266, -55).lineTo(-232, -10)
                        .lineTo(-238, 50).lineTo(-206, 40)
                        .lineTo(206, 40).lineTo(238, 50).lineTo(232, -10).lineTo(266, -55).lineTo(236, -110)
                        .lineTo(262, -170).lineTo(200, -205).cubicTo(120, -300, -120, -300, -200, -205).close();
                fillHair(g, path, -300, 60);
                g.stroke(path, hairDark(), 3f);
                break;
            }
            default: { // short
                path.moveTo(-205, -200).cubicTo(-268, -110, -262, 40, -236, 120).lineTo(236, 120)
                        .cubicTo(262, 40, 268, -110, 205, -200).cubicTo(120, -300, -120, -300, -205, -200).close();
                fillHair(g, path, -300, 120);
                g.stroke(path, hairDark(), 3f);
            }
        }
        g.restore();
    }

    // ------------------------------------------------------------------------------------------
    // Body

    private void drawBody(Gfx g, RigParams p, float breath) {
        float lift = breath * 6f;
        g.save();
        g.scale(1f + breath * 0.008f, 1f + breath * 0.012f, 500, 1200);
        int skinShade = Appearance.shade(a.skin, 0.86f);
        // Neck
        Gfx.Path neck = g.path();
        neck.moveTo(448, 680).lineTo(552, 680).lineTo(560, 820 - lift).lineTo(440, 820 - lift).close();
        g.fill(neck, a.skin);
        Gfx.Path chinShadow = g.path();
        chinShadow.moveTo(446, 700).quadTo(500, 770, 554, 700).lineTo(554, 680).lineTo(446, 680).close();
        g.fill(chinShadow, Appearance.alpha(skinShade, 0.9f));

        // Torso silhouette
        Gfx.Path torso = g.path();
        torso.moveTo(430, 790 - lift)
                .cubicTo(380, 810 - lift, 300, 820 - lift, 230, 870 - lift)
                .cubicTo(150, 925 - lift, 125, 1040, 120, 1200)
                .lineTo(880, 1200)
                .cubicTo(875, 1040, 850, 925 - lift, 770, 870 - lift)
                .cubicTo(700, 820 - lift, 620, 810 - lift, 570, 790 - lift).close();
        String outfit = a.outfit;
        int bodyColor = outfit.equals("hoodie") || outfit.equals("jacket") ? a.outfitColor : 0xFFF7F7FB;
        if (g.lowQuality()) g.fill(torso, bodyColor);
        else g.fillLinear(torso, 120, 0, 880, 0, Appearance.shade(bodyColor, 0.86f), Appearance.shade(bodyColor, 0.94f));
        g.stroke(torso, Appearance.shade(bodyColor, 0.6f), 3f);

        switch (outfit) {
            case "sailor": {
                Gfx.Path collar = g.path();
                collar.moveTo(430, 790 - lift).lineTo(500, 975).lineTo(570, 790 - lift)
                        .cubicTo(650, 805 - lift, 740, 840 - lift, 770, 880 - lift)
                        .lineTo(500, 1060)
                        .lineTo(230, 880 - lift)
                        .cubicTo(260, 840 - lift, 350, 805 - lift, 430, 790 - lift).close();
                g.fill(collar, a.outfitColor);
                Gfx.Path stripe = g.path();
                stripe.moveTo(262, 885 - lift).lineTo(500, 1035).lineTo(738, 885 - lift);
                g.stroke(stripe, WHITE, 6f);
                g.stroke(collar, Appearance.shade(a.outfitColor, 0.6f), 3f);
                drawBow(g, 500, 1000, 1f);
                break;
            }
            case "blouse": {
                Gfx.Path c = g.path();
                c.moveTo(440, 792 - lift).lineTo(500, 880).lineTo(560, 792 - lift).lineTo(590, 830).lineTo(500, 900).lineTo(410, 830).close();
                g.fill(c, WHITE);
                g.stroke(c, 0xFFBFC3D6, 3f);
                drawBow(g, 500, 905, 0.6f);
                break;
            }
            case "jacket": {
                Gfx.Path shirt = g.path();
                shirt.moveTo(440, 792 - lift).lineTo(560, 792 - lift).lineTo(540, 1200).lineTo(460, 1200).close();
                g.fill(shirt, WHITE);
                Gfx.Path lapelL = g.path();
                lapelL.moveTo(440, 792 - lift).lineTo(470, 1000).lineTo(400, 900).close();
                Gfx.Path lapelR = g.path();
                lapelR.moveTo(560, 792 - lift).lineTo(530, 1000).lineTo(600, 900).close();
                g.fill(lapelL, Appearance.shade(a.outfitColor, 0.8f));
                g.fill(lapelR, Appearance.shade(a.outfitColor, 0.8f));
                Gfx.Path tie = g.path();
                tie.moveTo(490, 820).lineTo(510, 820).lineTo(520, 980).lineTo(500, 1010).lineTo(480, 980).close();
                g.fill(tie, a.accent);
                break;
            }
            default: { // hoodie
                Gfx.Path hood = g.path();
                hood.moveTo(400, 800 - lift).cubicTo(330, 820, 300, 900, 360, 930).cubicTo(420, 960, 580, 960, 640, 930)
                        .cubicTo(700, 900, 670, 820, 600, 800 - lift).cubicTo(580, 860, 420, 860, 400, 800 - lift).close();
                g.fill(hood, Appearance.shade(a.outfitColor, 0.9f));
                g.stroke(hood, Appearance.shade(a.outfitColor, 0.55f), 3f);
                Gfx.Path zip = g.path();
                zip.moveTo(500, 940).lineTo(500, 1200);
                g.stroke(zip, Appearance.shade(a.outfitColor, 0.55f), 5f);
                for (int side = -1; side <= 1; side += 2) {
                    Gfx.Path str = g.path();
                    str.moveTo(500 + side * 36, 935).quadTo(500 + side * 44, 990, 500 + side * 38, 1050);
                    g.stroke(str, a.accent, 6f);
                    Gfx.Path tip = g.path().oval(500 + side * 38, 1058, 7, 12);
                    g.fill(tip, Appearance.shade(a.accent, 0.8f));
                }
                Gfx.Path pocket = g.path();
                pocket.moveTo(330, 1130).quadTo(500, 1100, 670, 1130);
                g.stroke(pocket, Appearance.shade(a.outfitColor, 0.6f), 4f);
            }
        }
        g.restore();
    }

    private void drawBow(Gfx g, float cx, float cy, float s) {
        int c = a.accent;
        int d = Appearance.shade(c, 0.7f);
        Gfx.Path left = g.path();
        left.moveTo(cx, cy).cubicTo(cx - 40 * s, cy - 45 * s, cx - 95 * s, cy - 30 * s, cx - 90 * s, cy + 5 * s)
                .cubicTo(cx - 88 * s, cy + 40 * s, cx - 40 * s, cy + 30 * s, cx, cy).close();
        Gfx.Path right = g.path();
        right.moveTo(cx, cy).cubicTo(cx + 40 * s, cy - 45 * s, cx + 95 * s, cy - 30 * s, cx + 90 * s, cy + 5 * s)
                .cubicTo(cx + 88 * s, cy + 40 * s, cx + 40 * s, cy + 30 * s, cx, cy).close();
        Gfx.Path tails = g.path();
        tails.moveTo(cx - 10 * s, cy + 10 * s).lineTo(cx - 45 * s, cy + 90 * s).lineTo(cx - 20 * s, cy + 82 * s)
                .lineTo(cx, cy + 20 * s).lineTo(cx + 20 * s, cy + 82 * s).lineTo(cx + 45 * s, cy + 90 * s)
                .lineTo(cx + 10 * s, cy + 10 * s).close();
        g.fill(tails, d);
        g.fill(left, c);
        g.fill(right, c);
        g.stroke(left, d, 2.5f);
        g.stroke(right, d, 2.5f);
        g.fill(g.path().oval(cx, cy, 14 * s, 13 * s), d);
    }

    // ------------------------------------------------------------------------------------------
    // Head

    private void drawHead(Gfx g, RigParams p) {
        float ax = p.angleX;
        float ay = p.angleY;
        float sR = 1f - Math.max(0f, ax) * 0.10f + Math.max(0f, -ax) * 0.04f;
        float sL = 1f - Math.max(0f, -ax) * 0.10f + Math.max(0f, ax) * 0.04f;
        float chinX = ax * 20f;
        float faceX = ax * 10f;

        // Ears
        int earColor = Appearance.shade(a.skin, 0.93f);
        g.fill(g.path().oval(-202 * sL + faceX - ax * 14, 50 + ay * 10, 26, 44), earColor);
        g.fill(g.path().oval(202 * sR + faceX - ax * 14, 50 + ay * 10, 26, 44), earColor);

        Gfx.Path face = g.path();
        face.moveTo(-205 * sL + faceX, -40)
                .cubicTo(-212 * sL + faceX, 90, -172 * sL + faceX, 190, -96 * sL + chinX * 0.5f, 255)
                .quadTo(-40 * sL + chinX, 290, chinX, 293)
                .quadTo(40 * sR + chinX, 290, 96 * sR + chinX * 0.5f, 255)
                .cubicTo(172 * sR + faceX, 190, 212 * sR + faceX, 90, 205 * sR + faceX, -40)
                .cubicTo(205 * sR + faceX, -205, 112 + faceX, -268, faceX, -268)
                .cubicTo(-112 + faceX, -268, -205 * sL + faceX, -205, -205 * sL + faceX, -40).close();
        if (g.lowQuality()) g.fill(face, a.skin);
        else g.fillLinear(face, 0, -260, 0, 300, Appearance.tint(a.skin, 0.25f), Appearance.shade(a.skin, 0.97f));
        g.stroke(face, Appearance.alpha(Appearance.shade(a.skin, 0.72f), 0.85f), 3f);

        // Soft shadow cast by the bangs.
        if (!g.lowQuality()) {
            g.save();
            g.clip(face);
            Gfx.Path sh = g.path();
            sh.moveTo(-260, -120).lineTo(260, -120).lineTo(260, -40 + ay * 10).quadTo(0, -10 + ay * 10, -260, -40 + ay * 10).close();
            g.fill(sh, Appearance.alpha(Appearance.shade(a.skin, 0.8f), 0.45f));
            g.restore();
        }

        float fx = ax * 36f;
        float fy = ay * 26f;
        drawBlush(g, p, fx, fy);
        drawEyes(g, p, fx, fy);
        // Nose
        Gfx.Path nose = g.path();
        nose.moveTo(fx * 1.15f - 3 + ax * 6, 128 + fy).lineTo(fx * 1.15f + 4 + ax * 10, 144 + fy);
        g.stroke(nose, Appearance.alpha(Appearance.shade(a.skin, 0.62f), 0.8f), 4.5f);
        drawMouth(g, p, fx * 1.1f, 212 + fy);
    }

    private void drawBlush(Gfx g, RigParams p, float fx, float fy) {
        if (p.cheek < 0.02f) return;
        int c = Appearance.alpha(0xFFFF6F91, 0.12f + 0.38f * p.cheek);
        for (int side = -1; side <= 1; side += 2) {
            float cx = side * 138 + fx * 0.8f;
            float cy = 148 + fy;
            if (g.lowQuality()) g.fill(g.path().oval(cx, cy, 50, 21), c);
            else g.fillRadial(g.path().oval(cx, cy, 54, 24), cx, cy, 54, c, Appearance.alpha(c, 0f));
            if (p.cheek > 0.7f && !g.lowQuality()) {
                for (int i = -1; i <= 1; i++) {
                    Gfx.Path l = g.path();
                    l.moveTo(cx + i * 16 - 6, cy + 8).lineTo(cx + i * 16 + 6, cy - 8);
                    g.stroke(l, Appearance.alpha(0xFFE04A6E, (p.cheek - 0.7f) * 2.5f), 3f);
                }
            }
        }
    }

    private void drawEyes(Gfx g, RigParams p, float fx, float fy) {
        boolean sharp = "sharp".equals(a.eyeShape);
        float w = sharp ? 112 : 118;
        float h = sharp ? 78 : 104;
        float ax = p.angleX;
        float scaleR = 1f - Math.max(0f, ax) * 0.3f;
        float scaleL = 1f - Math.max(0f, -ax) * 0.3f;
        drawEye(g, p, -112 + fx, 48 + fy, w * scaleL, h, -1, p.eyeLOpen, sharp);
        drawEye(g, p, 112 + fx, 48 + fy, w * scaleR, h, 1, p.eyeROpen, sharp);
    }

    /** side: -1 = viewer's left eye, 1 = viewer's right eye (outer corner points away from centre). */
    private void drawEye(Gfx g, RigParams p, float cx, float cy, float w, float h, int side, float open, boolean sharp) {
        float smile = p.eyeSmile;
        float o = Math.max(0f, Math.min(1.3f, open));
        float hw = w / 2f;
        int lineColor = Appearance.mix(LINE, hairDark(), 0.35f);
        if (o < 0.12f || smile > 0.82f) {
            Gfx.Path c = g.path();
            if (smile > 0.5f) {
                c.moveTo(cx - hw * 0.85f, cy + 8).quadTo(cx, cy - 30 * smile, cx + hw * 0.85f, cy + 8);
            } else {
                c.moveTo(cx - hw * 0.9f, cy + 4).quadTo(cx, cy + 22, cx + hw * 0.9f, cy + 4);
            }
            g.stroke(c, lineColor, 8f);
            if (girl) {
                Gfx.Path lash = g.path();
                lash.moveTo(cx + side * hw * 0.85f, cy + 6).lineTo(cx + side * (hw + 12), cy - 6);
                g.stroke(lash, lineColor, 5f);
            }
            return;
        }
        float lidTop = -h / 2f + (1f - Math.min(1f, o)) * h * 0.92f - Math.max(0f, o - 1f) * h * 0.25f;
        float outerLift = sharp ? -14f : -6f;
        float bottom = h / 2f - smile * h * 0.45f;
        float inX = cx - side * hw;
        float outX = cx + side * hw;
        Gfx.Path white = g.path();
        white.moveTo(inX, cy + 6)
                .cubicTo(cx - side * hw * 0.55f, cy + lidTop, cx + side * hw * 0.45f, cy + lidTop - (sharp ? 6 : 2), outX, cy + outerLift)
                .cubicTo(cx + side * hw * 0.75f, cy + bottom, cx - side * hw * 0.6f, cy + bottom, inX, cy + 6).close();
        if (g.lowQuality()) g.fill(white, WHITE);
        else g.fillLinear(white, 0, cy + lidTop, 0, cy + bottom, 0xFFDDE3F3, WHITE);

        g.save();
        g.clip(white);
        float irx = w * (sharp ? 0.34f : 0.37f);
        float iry = h * (sharp ? 0.55f : 0.5f);
        float ix = cx + p.eyeBallX * w * 0.22f;
        float iy = cy + p.eyeBallY * h * 0.18f + h * 0.06f;
        Gfx.Path iris = g.path().oval(ix, iy, irx, iry);
        int dark = Appearance.shade(a.eyes, 0.42f);
        int light = Appearance.tint(a.eyes, 0.35f);
        if (g.lowQuality()) g.fill(iris, a.eyes);
        else g.fillLinear(iris, 0, iy - iry, 0, iy + iry, dark, light);
        g.stroke(iris, Appearance.shade(a.eyes, 0.35f), 3f);
        g.fill(g.path().oval(ix, iy + iry * 0.05f, irx * 0.42f, iry * 0.52f), Appearance.shade(a.eyes, 0.22f));
        if (!g.lowQuality()) {
            // Lower glow
            g.fill(g.path().oval(ix, iy + iry * 0.55f, irx * 0.6f, iry * 0.25f), Appearance.alpha(Appearance.tint(a.eyes, 0.6f), 0.55f));
        }
        g.fill(g.path().oval(ix - irx * 0.38f, iy - iry * 0.42f, w * 0.1f, w * 0.1f), WHITE);
        g.fill(g.path().oval(ix + irx * 0.32f, iy + iry * 0.32f, w * 0.045f, w * 0.045f), Appearance.alpha(WHITE, 0.9f));
        // Upper lid shadow over the eyeball
        g.fill(g.path().moveTo(cx - hw - 10, cy + lidTop - 20).lineTo(cx + hw + 10, cy + lidTop - 20)
                .lineTo(cx + hw + 10, cy + lidTop + 14).quadTo(cx, cy + lidTop + 24, cx - hw - 10, cy + lidTop + 14).close(),
                Appearance.alpha(Appearance.shade(a.eyes, 0.3f), 0.28f));
        g.restore();

        // Upper lid line (filled crescent, thicker toward the outer corner) and lashes.
        float t = girl ? 11f : 8f;
        Gfx.Path lid = g.path();
        lid.moveTo(inX - side * 2, cy + 7)
                .cubicTo(cx - side * hw * 0.55f, cy + lidTop, cx + side * hw * 0.45f, cy + lidTop - (sharp ? 6 : 2), outX + side * 4, cy + outerLift)
                .lineTo(outX + side * (girl ? 16 : 8), cy + outerLift - t * 0.9f)
                .cubicTo(cx + side * hw * 0.45f, cy + lidTop - t - 4, cx - side * hw * 0.55f, cy + lidTop - t * 0.7f, inX - side * 4, cy + 1).close();
        g.fill(lid, lineColor);
        if (girl) {
            Gfx.Path lash = g.path();
            lash.moveTo(outX + side * 6, cy + outerLift - 4).lineTo(outX + side * 22, cy + outerLift + 6);
            g.stroke(lash, lineColor, 5f);
        }
        // Lower lid hint
        Gfx.Path low = g.path();
        low.moveTo(cx + side * hw * 0.15f, cy + bottom - 2).quadTo(cx + side * hw * 0.6f, cy + bottom + 2, outX - side * 4, cy + outerLift + h * 0.25f);
        g.stroke(low, Appearance.alpha(lineColor, 0.6f), 3.5f);
    }

    private void drawBrows(Gfx g, RigParams p, float fx, float fy) {
        int c = Appearance.alpha(Appearance.mix(Appearance.shade(a.hair, 0.45f), LINE, 0.4f), 0.92f);
        for (int side = -1; side <= 1; side += 2) {
            float cx = side * 112 + fx * 0.9f;
            float cy = -42 + fy - p.browY * 18f;
            float inner = -p.browForm * 18f - p.browInner * 14f;
            float outer = p.browForm * 8f;
            Gfx.Path b = g.path();
            b.moveTo(cx - side * 46, cy + inner + 6).quadTo(cx, cy - 10, cx + side * 50, cy + outer + 4);
            g.stroke(b, c, girl ? 6.5f : 9f);
        }
    }

    private void drawMouth(Gfx g, RigParams p, float cx, float cy) {
        float open = p.mouthOpen;
        float form = p.mouthForm;
        float w = 42 + p.mouthWide * 34 - open * 8;
        float h = open * 52;
        int line = 0xFF7A3340;
        if (h < 3.5f) {
            Gfx.Path m = g.path();
            m.moveTo(cx - w / 2, cy - form * 4).quadTo(cx, cy + form * 16, cx + w / 2, cy - form * 4);
            g.stroke(m, line, 5f);
            return;
        }
        Gfx.Path m = g.path();
        m.moveTo(cx - w / 2, cy - form * 3)
                .quadTo(cx, cy - h * 0.12f + form * 5, cx + w / 2, cy - form * 3)
                .quadTo(cx + w * 0.3f, cy + h + form * 6, cx, cy + h + form * 4)
                .quadTo(cx - w * 0.3f, cy + h + form * 6, cx - w / 2, cy - form * 3).close();
        g.fill(m, 0xFF5C1F2C);
        g.save();
        g.clip(m);
        g.fill(g.path().oval(cx, cy + h * 0.95f, w * 0.34f, h * 0.38f), 0xFFE5798C);
        if (h > 16) g.fill(g.path().oval(cx, cy - 2, w * 0.36f, 7), Appearance.alpha(WHITE, 0.95f));
        g.restore();
        g.stroke(m, line, 3f);
    }

    // ------------------------------------------------------------------------------------------
    // Hair (front)

    private void drawFrontHair(Gfx g, RigParams p) {
        float hx = p.angleX * 24f;
        float hy = p.angleY * 14f;
        float sway = p.hairFront;
        float side = p.hairSide;
        g.save();
        g.translate(hx, hy);

        // Side locks
        if (!"spiky".equals(a.hairStyle) && !"short".equals(a.hairStyle)) {
            float len = "bob".equals(a.hairStyle) ? 230 : 360;
            for (int s = -1; s <= 1; s += 2) {
                float sw = side * 28f * s + side * 10f;
                Gfx.Path lock = g.path();
                lock.moveTo(s * 232, -150)
                        .cubicTo(s * 262, 20, s * 250, len * 0.55f, s * 214 + sw, len)
                        .cubicTo(s * 196 + sw * 0.5f, len * 0.6f, s * 190, 60, s * 176, -110).close();
                fillHair(g, lock, -150, len);
                g.stroke(lock, hairDark(), 3f);
            }
        } else {
            for (int s = -1; s <= 1; s += 2) {
                Gfx.Path burn = g.path();
                burn.moveTo(s * 222, -120).lineTo(s * 230, 70 + side * 6).lineTo(s * 200, 30).lineTo(s * 190, -100).close();
                fillHair(g, burn, -120, 70);
                g.stroke(burn, hairDark(), 3f);
            }
        }

        // Bangs
        Gfx.Path bangs = g.path();
        boolean boyish = "spiky".equals(a.hairStyle) || "short".equals(a.hairStyle);
        float[] tipsX;
        float[] tipsY;
        if (boyish) {
            tipsX = new float[]{-215, -150, -78, 2, 76, 150, 218};
            tipsY = new float[]{-30, 18, -5, 30, -2, 14, -36};
        } else {
            tipsX = new float[]{-222, -150, -80, -8, 64, 140, 222};
            tipsY = new float[]{-10, 32, 6, 44, 10, 30, -14};
        }
        bangs.moveTo(-246, -40).cubicTo(-270, -230, -140, -322, 0, -322).cubicTo(140, -322, 270, -230, 246, -40);
        for (int i = tipsX.length - 1; i >= 0; i--) {
            float swayX = sway * 14f * (tipsY[i] + 110) / 110f + (boyish ? -10f : 0f);
            float tx = tipsX[i] + swayX;
            float ty = tipsY[i];
            float prevX = i == tipsX.length - 1 ? 246 : (tipsX[i] + tipsX[i + 1]) / 2f;
            float valleyX = i == 0 ? -246 : (tipsX[i] + tipsX[i - 1]) / 2f;
            float valleyY = -92 + (i % 2 == 0 ? 6 : -8);
            bangs.quadTo((prevX + tx) / 2f + 8, ty - 40, tx, ty);
            bangs.quadTo((tx + valleyX) / 2f - 6, ty - 46, valleyX, i == 0 ? -40 : valleyY);
        }
        bangs.close();
        fillHair(g, bangs, -322, 44);
        g.stroke(bangs, hairDark(), 3f);

        // Strand lines
        if (!g.lowQuality()) {
            for (int i = 1; i < tipsX.length - 1; i++) {
                Gfx.Path s = g.path();
                s.moveTo(tipsX[i] * 0.55f, -210).quadTo(tipsX[i] * 0.85f, -120, tipsX[i] + sway * 10f, tipsY[i] - 26);
                g.stroke(s, Appearance.alpha(hairDark(), 0.55f), 2.5f);
            }
            // Hair shine: short diagonal streaks along the crown's curvature ("angel ring").
            int hl = Appearance.alpha(Appearance.tint(a.hair, 0.7f), 0.42f);
            for (float x = -150; x <= 150; x += 37.5f) {
                float yc = -228 + (x / 170f) * (x / 170f) * 52f;
                Gfx.Path streak = g.path();
                streak.moveTo(x - 6, yc - 14).lineTo(x + 8, yc + 14);
                g.stroke(streak, hl, 9f);
            }
        }

        // Ahoge (cowlick)
        Gfx.Path ahoge = g.path();
        float aw = sway * 20f;
        ahoge.moveTo(-10, -316).cubicTo(-30 + aw, -400, 60 + aw, -420, 40 + aw * 1.4f, -360).cubicTo(30 + aw, -390, -5, -370, 12, -318).close();
        g.fill(ahoge, a.hair);
        g.stroke(ahoge, hairDark(), 2.5f);
        g.restore();
    }

    private void drawAccessory(Gfx g, RigParams p) {
        float hx = p.angleX * 24f;
        float hy = p.angleY * 14f;
        switch (a.accessory) {
            case "ribbon": {
                g.save();
                g.translate(hx, hy);
                g.rotate(18, 168, -232);
                drawBow(g, 168, -232, 0.85f);
                g.restore();
                if ("twintails".equals(a.hairStyle)) {
                    g.save();
                    g.translate(hx, hy);
                    g.rotate(-18, -168, -232);
                    drawBow(g, -168, -232, 0.85f);
                    g.restore();
                }
                break;
            }
            case "clip": {
                g.save();
                g.translate(hx, hy);
                for (int i = 0; i < 2; i++) {
                    Gfx.Path c = g.path();
                    c.moveTo(150 + i * 16, -180).lineTo(196 + i * 16, -132).lineTo(188 + i * 16, -124).lineTo(142 + i * 16, -172).close();
                    g.fill(c, a.accent);
                }
                g.restore();
                break;
            }
            case "glasses": {
                float fx = p.angleX * 36f;
                float fy = p.angleY * 26f;
                int c = 0xFF3A3340;
                for (int s = -1; s <= 1; s += 2) {
                    Gfx.Path lens = g.path();
                    float cx = s * 112 + fx;
                    float cy = 50 + fy;
                    lens.moveTo(cx - 78, cy - 50).lineTo(cx + 78, cy - 50).quadTo(cx + 84, cy - 50, cx + 82, cy - 40)
                            .lineTo(cx + 74, cy + 44).quadTo(cx + 70, cy + 54, cx + 60, cy + 54).lineTo(cx - 60, cy + 54)
                            .quadTo(cx - 70, cy + 54, cx - 74, cy + 44).lineTo(cx - 82, cy - 40).quadTo(cx - 84, cy - 50, cx - 78, cy - 50).close();
                    g.fill(lens, 0x22B7D6FF);
                    g.stroke(lens, c, 5f);
                }
                Gfx.Path bridge = g.path();
                bridge.moveTo(-34 + fx, 20 + fy).quadTo(fx, 6 + fy, 34 + fx, 20 + fy);
                g.stroke(bridge, c, 5f);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Overlays in model space

    private void drawOverlays(Gfx g, RigParams p, float headX, float headY) {
        if (p.thinkDots > 0.05f) {
            int c = Appearance.alpha(0xFFFFFFFF, 0.9f * p.thinkDots);
            int o = Appearance.alpha(0xFF6B6385, 0.9f * p.thinkDots);
            float[][] dots = {{headX + 250, headY - 260, 16}, {headX + 290, headY - 315, 22}, {headX + 340, headY - 375, 30}};
            for (float[] d : dots) {
                Gfx.Path c1 = g.path().oval(d[0], d[1], d[2], d[2]);
                g.fill(c1, c);
                g.stroke(c1, o, 3f);
            }
        }
        if (p.listenLevel > 0.03f) {
            int c = Appearance.alpha(0xFF6DD3FF, 0.25f + 0.6f * p.listenLevel);
            for (int i = 1; i <= 3; i++) {
                float r = 30 + i * 22 + p.listenLevel * 18;
                Gfx.Path arc = g.path();
                arc.moveTo(headX + 250 + r * 0.6f, headY - r * 0.8f + 40).quadTo(headX + 250 + r * 1.1f, headY + 40, headX + 250 + r * 0.6f, headY + r * 0.8f + 40);
                g.stroke(arc, Appearance.alpha(c, 1f - i * 0.22f), 6f);
            }
        }
        if (p.sweat > 0.05f) {
            Gfx.Path drop = g.path();
            float x = headX + 200;
            float y = headY - 120;
            drop.moveTo(x, y - 34).quadTo(x + 22, y, x + 14, y + 12).quadTo(x, y + 26, x - 14, y + 12).quadTo(x - 22, y, x, y - 34).close();
            g.fill(drop, Appearance.alpha(0xFF9AD8FF, 0.85f * p.sweat));
            g.stroke(drop, Appearance.alpha(0xFF4E9CC9, 0.85f * p.sweat), 3f);
        }
        if (p.sparkle > 0.3f && !g.lowQuality()) {
            float[][] stars = {{headX - 300, headY - 250, 22}, {headX + 310, headY - 160, 16}, {headX - 260, headY + 20, 12}};
            for (float[] s : stars) {
                Gfx.Path st = g.path();
                float x = s[0], y = s[1], r = s[2];
                st.moveTo(x, y - r).quadTo(x, y, x + r, y).quadTo(x, y, x, y + r).quadTo(x, y, x - r, y).quadTo(x, y, x, y - r).close();
                g.fill(st, Appearance.alpha(0xFFFFE27A, (p.sparkle - 0.3f) * 1.4f));
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Maps a point in model space to a touch region (used for touch reactions). */
    public static CharacterAnimator.TouchRegion regionAt(float mx, float my) {
        float dx = mx - 500;
        float dy = my - 470;
        if (dy < -130 && Math.abs(dx) < 300) return CharacterAnimator.TouchRegion.HEAD;
        if (dy < 300 && (dx * dx) / (230f * 230f) + (dy * dy) / (300f * 300f) <= 1.2f) return CharacterAnimator.TouchRegion.FACE;
        return CharacterAnimator.TouchRegion.BODY;
    }
}
