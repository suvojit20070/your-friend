package com.suvo.agent.ui;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.os.Build;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

/**
 * Your Friend — Original project by Suvo.
 *
 * What shows while the app starts (before its first screen is drawn) in the app's own theme:
 * <ul>
 *   <li>Android 12+: the app tells Android its light / dark mode (System, Dark, Light, Dim), so the
 *       launch screen and every system-drawn part follow the app's choice, not only the phone's.</li>
 *   <li>Android 13+: the launch screen's colour is the page colour of the chosen accent (one theme
 *       per accent), pure black for Dim.</li>
 * </ul>
 * Older Android versions draw the launch screen before any app code runs: it follows the phone.
 */
public final class Splash {

    private static final String APPLIED_MODE = "splash_mode_applied";
    private static final String APPLIED_THEME = "splash_theme_applied";

    private Splash() {
    }

    /** The app-wide part (night mode). Any context. */
    public static void apply(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return;
        Settings s = Settings.get(ctx);
        String theme = s.str(Settings.THEME, "dark");
        int mode = "system".equals(theme) ? UiModeManager.MODE_NIGHT_AUTO
                : "light".equals(theme) ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES;
        if (s.integer(APPLIED_MODE, -1) == mode) return;
        UiModeManager um = ctx.getSystemService(UiModeManager.class);
        if (um == null) return;
        try {
            um.setApplicationNightMode(mode);
            s.put(APPLIED_MODE, mode);
        } catch (RuntimeException ignored) {
        }
    }

    /** The launch screen theme for the chosen accent (Android 13+). Called from every screen; cheap. */
    public static void apply(Activity a) {
        apply((Context) a);
        if (Build.VERSION.SDK_INT < 33) return;
        Settings s = Settings.get(a);
        int res = themeFor(s.str(Settings.THEME, "dark"), s.str(Settings.ACCENT, "dynamic"));
        if (s.integer(APPLIED_THEME, 0) == res) return;
        try {
            a.getSplashScreen().setSplashScreenTheme(res);
            s.put(APPLIED_THEME, res);
        } catch (RuntimeException ignored) {
        }
    }

    private static final int[] SEED_THEMES = {R.style.Theme_YourFriend_Splash_S0, R.style.Theme_YourFriend_Splash_S1,
            R.style.Theme_YourFriend_Splash_S2, R.style.Theme_YourFriend_Splash_S3, R.style.Theme_YourFriend_Splash_S4,
            R.style.Theme_YourFriend_Splash_S5, R.style.Theme_YourFriend_Splash_S6, R.style.Theme_YourFriend_Splash_S7,
            R.style.Theme_YourFriend_Splash_S8, R.style.Theme_YourFriend_Splash_S9, R.style.Theme_YourFriend_Splash_S10,
            R.style.Theme_YourFriend_Splash_S11};

    static int themeFor(String theme, String accent) {
        if ("dim".equals(theme)) return R.style.Theme_YourFriend_Splash_Dim;
        for (int i = 0; i < Palette.SEEDS.length && i < SEED_THEMES.length; i++) {
            if (Palette.SEEDS[i].equalsIgnoreCase(accent)) return SEED_THEMES[i];
        }
        return R.style.Theme_YourFriend_Splash_Dynamic;
    }
}
