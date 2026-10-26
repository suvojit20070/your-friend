package com.suvo.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Real Material 3 components (Material Components for Android): {@link MaterialSwitch} and
 * {@link Slider}, coloured with the app's accent (which can be the user's own colour, so the tint is
 * applied in code rather than only from the theme).
 */
public final class Widgets {

    private Widgets() {
    }

    private static final int[][] CHECK_STATES = {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};

    /** A Material 3 switch in the app's colours. */
    public static MaterialSwitch newSwitch(Context c) {
        return styleSwitch(new MaterialSwitch(c));
    }

    public static MaterialSwitch styleSwitch(MaterialSwitch s) {
        Context c = s.getContext();
        // On = primary track with an onPrimary thumb; off = tonal surface track, muted thumb.
        Palette p = Palette.of(c);
        int accent = p.primary;
        int onAccent = p.onPrimary;
        int trackOff = p.surfaceHighest;
        int outline = Palette.alpha(p.onSurfaceVariant, 0.7f);
        int disabled = Palette.alpha(p.onSurface, 0.12f);
        s.setTrackTintList(new ColorStateList(CHECK_STATES, new int[]{disabled, accent, trackOff}));
        s.setThumbTintList(new ColorStateList(CHECK_STATES, new int[]{outline, onAccent, outline}));
        s.setTrackDecorationTintList(new ColorStateList(CHECK_STATES, new int[]{disabled, Color.TRANSPARENT, outline}));
        s.setThumbIconTintList(ColorStateList.valueOf(accent));
        return s;
    }

    /**
     * A Material 3 slider for whole numbers {@code min..max}. Values outside the range are clamped,
     * because Slider refuses a value that is not inside the range or on a step.
     */
    public static Slider newSlider(Context c, int min, int max, int value) {
        Slider s = new Slider(c);
        int lo = Math.min(min, max), hi = Math.max(min, max);
        if (hi == lo) hi = lo + 1;
        s.setValueFrom(lo);
        s.setValueTo(hi);
        s.setStepSize(1f);
        s.setValue(Math.max(lo, Math.min(hi, value)));
        s.setLabelBehavior(LabelFormatter.LABEL_GONE);
        return styleSlider(s);
    }

    public static Slider styleSlider(Slider s) {
        Context c = s.getContext();
        int accent = Ui.accent(c);
        s.setTrackActiveTintList(ColorStateList.valueOf(accent));
        s.setTrackInactiveTintList(ColorStateList.valueOf(Palette.of(c).surfaceHighest));
        s.setThumbTintList(ColorStateList.valueOf(accent));
        s.setHaloTintList(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000));
        // Steps are 1; the tick marks would just be noise on long ranges.
        s.setTickActiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        s.setTickInactiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        return s;
    }

    /** Integer value of a whole-number slider. */
    public static int value(Slider s) {
        return Math.round(s.getValue());
    }

    /** Dropdown with the app's input look and a rounded popup. */
    public static android.widget.Spinner styleSpinner(android.widget.Spinner sp) {
        Context c = sp.getContext();
        sp.setBackgroundResource(R.drawable.spinner_bg);
        sp.setPopupBackgroundDrawable(new GlassDrawable(c, GlassDrawable.Preset.POPOVER));
        sp.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 40), Ui.dp(c, 6));
        sp.setMinimumHeight(Ui.dp(c, 52));
        return sp;
    }
}
