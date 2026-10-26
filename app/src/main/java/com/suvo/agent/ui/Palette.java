package com.suvo.agent.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import com.suvo.agent.data.Settings;

/**
 * Your Friend — Original project by Suvo.
 *
 * One seed colour → the whole tonal colour scheme (the "Zafiro" look): a warm paper-like
 * background, tonal surface containers, a muted primary, and soft containers used at low alpha.
 * The roles are a tonal-spot template measured from Material's scheme for the default amber seed,
 * rotated to the seed's hue — so every preset gets the same calm lightness and colourfulness, only
 * the hue changes (like Material's TonalSpot style, whose chroma does not follow the seed either).
 *
 * "dynamic" uses the wallpaper colour on Android 12+ and amber before that.
 */
public final class Palette {

    /** Preset seeds (pastel / mid-tone) — the names are string resources {@code accent_<name>}. */
    public static final String[] SEED_NAMES = {"rose", "blossom", "lavender", "blue", "sky", "cyan", "mint", "green", "lime",
            "lemon", "amber", "apricot"};
    public static final String[] SEEDS = {"#FEB4A7", "#FFB3C0", "#FCAAFF", "#B9C3FF", "#62D3FF", "#44D9F1", "#52DBC9",
            "#78DD77", "#9FD75C", "#C1D02D", "#FABD00", "#FFB86E"};
    public static final String DEFAULT_SEED = "#FABD00";

    // {hue offset from the seed's hue, HSL saturation, HSL lightness} for each role.
    private static final float[][] LIGHT = {
            {-17.7f, 1.000f, 0.975f}, // background
            {-12.4f, 0.714f, 0.945f}, // surfaceContainerLow
            {-12.4f, 0.385f, 0.898f}, // surfaceContainerHigh
            {-9.4f, 0.312f, 0.875f},  // surfaceContainerHighest
            {-1.9f, 0.832f, 0.257f},  // primary
            {0f, 0f, 1f},             // onPrimary
            {-5.2f, 1.000f, 0.810f},  // primaryContainer
            {-6.4f, 0.722f, 0.845f},  // secondaryContainer
            {54.1f, 0.210f, 0.327f},  // tertiary
            {-5.4f, 0.240f, 0.098f},  // onSurface
            {-6.4f, 0.149f, 0.263f},  // onSurfaceVariant
            {-8.9f, 0.230f, 0.761f},  // outlineVariant
    };
    private static final float[][] DARK = {
            {-5.4f, 0.353f, 0.067f},
            {-8.4f, 0.255f, 0.100f},
            {-6.8f, 0.175f, 0.157f},
            {-9.4f, 0.149f, 0.198f},
            {-4.9f, 0.750f, 0.671f},
            {-1.6f, 1.000f, 0.124f},
            {-1.2f, 1.000f, 0.178f},
            {-5.8f, 0.328f, 0.245f},
            {52.7f, 0.295f, 0.727f},
            {-11.4f, 0.365f, 0.876f},
            {-8.9f, 0.230f, 0.761f},
            {-6.4f, 0.149f, 0.263f},
    };

    public final boolean dark;
    public final int background, surfaceLow, surfaceHigh, surfaceHighest, primary, onPrimary, primaryContainer,
            secondaryContainer, tertiary, onSurface, onSurfaceVariant, outlineVariant;

    private static Palette cached;
    private static String cachedKey = "";

    private Palette(float seedHue, boolean dark) {
        this.dark = dark;
        float[][] t = dark ? DARK : LIGHT;
        int[] c = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            float h = ((seedHue + t[i][0]) % 360f + 360f) % 360f;
            c[i] = Color.HSVToColor(hslToHsv(h, t[i][1], t[i][2]));
        }
        background = c[0];
        surfaceLow = c[1];
        surfaceHigh = c[2];
        surfaceHighest = c[3];
        primary = c[4];
        onPrimary = c[5];
        primaryContainer = c[6];
        secondaryContainer = c[7];
        tertiary = c[8];
        onSurface = c[9];
        onSurfaceVariant = c[10];
        outlineVariant = c[11];
    }

    /** The palette for the current seed setting and light/dark mode of {@code ctx}. */
    public static Palette of(Context ctx) {
        boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String seed = Settings.get(ctx).str(Settings.ACCENT, "dynamic");
        String key = seed + dark;
        synchronized (Palette.class) {
            if (cached != null && key.equals(cachedKey)) return cached;
            int seedColor = seedColor(ctx, seed);
            float[] hsv = new float[3];
            Color.colorToHSV(seedColor, hsv);
            cached = new Palette(hsv[0], dark);
            cachedKey = key;
            return cached;
        }
    }

    static int seedColor(Context ctx, String seed) {
        if ("dynamic".equals(seed) || seed == null || seed.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    return ctx.getColor(android.R.color.system_accent1_200);
                } catch (RuntimeException ignored) {
                }
            }
            return Color.parseColor(DEFAULT_SEED);
        }
        try {
            return Color.parseColor(seed);
        } catch (IllegalArgumentException e) {
            return Color.parseColor(DEFAULT_SEED);
        }
    }

    /** {@code color} with alpha {@code a} (0..1). */
    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24);
    }

    private static float[] hslToHsv(float h, float s, float l) {
        float v = l + s * Math.min(l, 1 - l);
        float sv = v <= 0 ? 0 : 2 * (1 - l / v);
        return new float[]{h, sv, v};
    }

    // ------------------------------------------------------------------ role helpers for widgets

    /** Glass surface for floating chrome: white 22% (light) / #AFAFAF 15% (dark). */
    public int glassButton() {
        return dark ? alpha(0xAFAFAF, 0.15f) : alpha(Color.WHITE, 0.22f);
    }

    public int pressed() {
        return alpha(onSurface, 0.08f);
    }

    public int userBubble() {
        return alpha(primary, 0.18f);
    }

    public int scrim() {
        return alpha(Color.BLACK, 0.42f);
    }
}
