package com.suvo.agent.ui;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import com.suvo.agent.data.Settings;

import java.util.Locale;

/**
 * In-app UI language (English, Hinglish, Bengali, Hindi, Japanese) and theme override. Hinglish uses
 * the BCP-47 tag hi-Latn (resources in values-b+hi+Latn). This only changes UI text; the AI can
 * still understand and answer in any language.
 */
public final class LocaleHelper {

    public static final String[] TAGS = {"", "en", "hi-Latn", "bn", "hi", "ja"};

    private LocaleHelper() {
    }

    public static Context wrap(Context base) {
        Settings s = Settings.get(base);
        String tag = s.str(Settings.UI_LANGUAGE, "");
        String theme = s.str(Settings.THEME, "dark");
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        boolean changed = false;
        if (!tag.isEmpty()) {
            Locale l = Locale.forLanguageTag(tag);
            cfg.setLocales(new LocaleList(l));
            changed = true;
        }
        if (!"system".equals(theme)) {
            int night = "light".equals(theme) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES; // dark and dim
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            changed = true;
        }
        return changed ? base.createConfigurationContext(cfg) : base;
    }

    /**
     * After the theme or language changed: the application's own context (used by floating
     * windows, notifications, services) gets the new mode too, without restarting the process.
     * Its configuration is rebuilt from the system's, so "System" really follows the phone again.
     */
    @SuppressWarnings("deprecation")
    public static void applyToApp(Context app) {
        Settings s = Settings.get(app);
        Configuration cfg = new Configuration(android.content.res.Resources.getSystem().getConfiguration());
        String tag = s.str(Settings.UI_LANGUAGE, "");
        if (!tag.isEmpty()) cfg.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        String theme = s.str(Settings.THEME, "dark");
        if (!"system".equals(theme)) {
            int night = "light".equals(theme) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES;
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        }
        try {
            app.getResources().updateConfiguration(cfg, app.getResources().getDisplayMetrics());
        } catch (RuntimeException ignored) {
            // the screens are right anyway; the rest follows at the next start
        }
        Splash.apply(app);
    }

    /** Stores the choice and, on Android 13+, also registers it as the system per-app language. */
    public static void setLanguage(Context ctx, String tag) {
        Settings.get(ctx).put(Settings.UI_LANGUAGE, tag);
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager lm = ctx.getSystemService(LocaleManager.class);
            if (lm != null) lm.setApplicationLocales(tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
        }
    }
}
