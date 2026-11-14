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

    /**
     * Preset seeds — the names are string resources {@code accent_<name>}. The first twelve are the
     * app's pastel / mid-tone set; then the Material colour palette (500 tones). After changing the
     * list, run tools/theme/gen_splash.py (launch-screen colours) and add the Splash themes.
     */
    public static final String[] SEED_NAMES = {"rose", "blossom", "lavender", "blue", "sky", "cyan", "mint", "green", "lime",
            "lemon", "amber", "apricot",
            "md_red", "md_pink", "md_purple", "md_deep_purple", "md_indigo", "md_blue", "md_light_blue", "md_cyan", "md_teal",
            "md_green", "md_light_green", "md_lime", "md_yellow", "md_amber", "md_orange", "md_deep_orange", "md_brown",
            "md_blue_grey"};
    public static final String[] SEEDS = {"#FEB4A7", "#FFB3C0", "#FCAAFF", "#B9C3FF", "#62D3FF", "#44D9F1", "#52DBC9",
            "#78DD77", "#9FD75C", "#C1D02D", "#FABD00", "#FFB86E",
            "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4", "#009688",
            "#4CAF50", "#8BC34A", "#CDDC39", "#FFEB3B", "#FFC107", "#FF9800", "#FF5722", "#795548",
            "#607D8B"};
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
            {-3.9f, 1.000f, 0.075f},  // onPrimaryContainer #261A00
            {-12.4f, 0.560f, 0.922f}, // surfaceContainer #F5ECE1
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
            {-5.2f, 1.000f, 0.810f},
            {-8.6f, 0.220f, 0.116f},
    };

    public final boolean dark;
    public final int background, surfaceLow, surfaceHigh, surfaceHighest, primary, onPrimary, primaryContainer,
            secondaryContainer, tertiary, onSurface, onSurfaceVariant, outlineVariant, onPrimaryContainer, surfaceContainer, error;

    /** One palette per (seed, mode): screens with different modes never push each other out. */
    private static final java.util.HashMap<String, Palette> CACHE = new java.util.HashMap<>();

    /** Dim: the dark scheme on pure black (#000000), with darker surfaces. */
    public final boolean dim;

    /**
     * Terminal: black and white like a real terminal, the accent only for highlights; flat (no
     * cards or rounded blocks — see {@link TonalDrawable#flat}) and monospace everywhere.
     */
    public final boolean terminal;

    private Palette(float seedHue, float chroma, boolean dark, boolean dim, boolean terminal) {
        this.terminal = terminal;
        this.dark = dark || terminal;
        this.dim = (dim && dark) || terminal;
        dark = this.dark;
        float[][] t = dark ? DARK : LIGHT;
        int[] c = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            float h = ((seedHue + t[i][0]) % 360f + 360f) % 360f;
            // Greyish seeds (brown, blue grey) tone every role down; onPrimary stays as it is.
            float sat = i == 5 ? t[i][1] : t[i][1] * chroma;
            c[i] = Color.HSVToColor(hslToHsv(h, sat, t[i][2]));
        }
        if (this.dim) {
            // Same hues, pulled down toward black: the page itself is true black.
            c[0] = 0xFF000000;
            c[1] = shade(seedHue, t[1], 0.052f);
            c[2] = shade(seedHue, t[2], 0.098f);
            c[3] = shade(seedHue, t[3], 0.135f);
            c[13] = shade(seedHue, t[13], 0.068f);
        }
        if (terminal) {
            // Black page, white / grey text, accent highlights; surfaces stay black (flat).
            int accent = c[4];
            c[0] = 0xFF000000;
            c[1] = 0xFF000000;
            c[2] = 0xFF141414;
            c[3] = 0xFF1E1E1E;
            c[5] = 0xFF000000;
            c[6] = 0xFF1A1A1A;
            c[7] = 0xFF161616;
            c[8] = accent;
            c[9] = 0xFFE6E6E6;
            c[10] = 0xFF9E9E9E;
            c[11] = 0xFF3A3A3A;
            c[12] = accent;
            c[13] = 0xFF000000;
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
        onPrimaryContainer = c[12];
        surfaceContainer = c[13];
        error = dark ? 0xFFFFB4AB : 0xFFBA1A1A;
    }

    private static int shade(float seedHue, float[] role, float lightness) {
        float h = ((seedHue + role[0]) % 360f + 360f) % 360f;
        return Color.HSVToColor(hslToHsv(h, role[1], lightness));
    }

    /**
     * The palette for the current seed and theme. The app's own theme setting decides light / dark
     * (not the context's configuration), so a view built from the application context — the
     * floating browser, the companion — gets exactly the colours of the screens.
     */
    public static Palette of(Context ctx) {
        Settings st = Settings.get(ctx);
        String theme = st.str(Settings.THEME, "dark");
        boolean dark;
        if ("system".equals(theme)) {
            // The phone's own dark / light setting, read from the system itself: the app's contexts
            // can still carry the mode chosen before (the app process keeps running after a change).
            dark = (android.content.res.Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } else {
            dark = !"light".equals(theme);
        }
        boolean dim = "dim".equals(theme);
        boolean terminal = "terminal".equals(theme);
        String seed = st.str(Settings.ACCENT, "dynamic");
        int seedColor = seedColor(ctx, seed);
        boolean preset = !"dynamic".equals(seed) && seed != null && !seed.isEmpty();
        String key = seedColor + "/" + dark + "/" + dim + "/" + terminal + "/" + preset;
        TonalDrawable.flat = terminal;
        synchronized (CACHE) {
            Palette p = CACHE.get(key);
            if (p == null) {
                float[] hsv = new float[3];
                Color.colorToHSV(seedColor, hsv);
                if (CACHE.size() > 8) CACHE.clear();
                p = new Palette(hsv[0], preset ? chromaFor(seedColor) : 1f, dark, dim, terminal);
                CACHE.put(key, p);
            }
            return p;
        }
    }

    /** True when the app is in the Terminal theme (flat, monospace). */
    public static boolean terminal(Context ctx) {
        return "terminal".equals(Settings.get(ctx).str(Settings.THEME, "dark"));
    }

    /**
     * How colourful the scheme is for a preset seed: 1 for every colourful seed (the scheme keeps
     * its calm template); greyish ones (Material brown, blue grey) are toned down so they look like
     * themselves instead of their hue's bright twin. Same rule as tools/theme/gen_splash.py.
     */
    static float chromaFor(int seedColor) {
        float r = Color.red(seedColor) / 255f, g = Color.green(seedColor) / 255f, b = Color.blue(seedColor) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float sat = max == min ? 0f : (max - min) / (1f - Math.abs(2f * l - 1f));
        return sat >= 0.4f ? 1f : Math.max(0.15f, sat / 0.4f);
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
