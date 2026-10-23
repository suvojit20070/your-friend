package com.suvo.agent.core.character;

/** Visual configuration of the procedural rig. */
public final class Appearance {
    public String base = "girl";        // girl | boy
    public String hairStyle = "long";   // long | twintails | bob | short | spiky
    public String eyeShape = "round";   // round | sharp
    public String outfit = "sailor";    // sailor | hoodie | blouse | jacket
    public String accessory = "ribbon"; // ribbon | clip | glasses | none
    public int hair = 0xFF3B2A4D;
    public int eyes = 0xFF7B5CFF;
    public int skin = 0xFFFFE3D3;
    public int outfitColor = 0xFF2B3A67;
    public int accent = 0xFFE5486B;

    public static int shade(int c, float f) {
        int a = (c >>> 24) & 0xff;
        int r = Math.min(255, Math.max(0, Math.round(((c >> 16) & 0xff) * f)));
        int g = Math.min(255, Math.max(0, Math.round(((c >> 8) & 0xff) * f)));
        int b = Math.min(255, Math.max(0, Math.round((c & 0xff) * f)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Mixes c toward white by t (0..1). */
    public static int tint(int c, float t) {
        int a = (c >>> 24) & 0xff;
        int r = (c >> 16) & 0xff;
        int g = (c >> 8) & 0xff;
        int b = c & 0xff;
        r = Math.round(r + (255 - r) * t);
        g = Math.round(g + (255 - g) * t);
        b = Math.round(b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int alpha(int c, float a) {
        int al = Math.round(Math.max(0f, Math.min(1f, a)) * ((c >>> 24) & 0xff));
        return (al << 24) | (c & 0xFFFFFF);
    }

    public static int mix(int c1, int c2, float t) {
        int r = Math.round(((c1 >> 16) & 0xff) * (1 - t) + ((c2 >> 16) & 0xff) * t);
        int g = Math.round(((c1 >> 8) & 0xff) * (1 - t) + ((c2 >> 8) & 0xff) * t);
        int b = Math.round((c1 & 0xff) * (1 - t) + (c2 & 0xff) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
