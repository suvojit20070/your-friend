package com.suvo.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;

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

    /** A Material 3 switch in the palette's colours. */
    public static com.google.android.material.materialswitch.MaterialSwitch newSwitch(Context c) {
        return styleSwitch(new com.google.android.material.materialswitch.MaterialSwitch(c));
    }

    /** On = primary track, onPrimary thumb; off = tonal track with a muted thumb and outline. */
    public static com.google.android.material.materialswitch.MaterialSwitch styleSwitch(com.google.android.material.materialswitch.MaterialSwitch s) {
        Palette p = Palette.of(s.getContext());
        // Setting the tints rebuilds the thumb drawable, which requests a new layout; the screen
        // re-themes after every layout, so doing it again each time kept the screen re-laying out
        // every frame (laggy switches and lists). Style each switch once per palette.
        if (s.getTag(R.id.theme_tint) == p) return s;
        s.setTag(R.id.theme_tint, p);
        int off = p.surfaceHighest;
        int outline = Palette.alpha(p.onSurfaceVariant, 0.75f);
        int disabled = Palette.alpha(p.onSurface, 0.12f);
        s.setTrackTintList(new ColorStateList(CHECK_STATES, new int[]{disabled, p.primary, off}));
        s.setThumbTintList(new ColorStateList(CHECK_STATES, new int[]{Palette.alpha(p.onSurface, 0.38f), p.onPrimary, outline}));
        s.setTrackDecorationTintList(new ColorStateList(CHECK_STATES, new int[]{disabled, android.graphics.Color.TRANSPARENT, outline}));
        s.setThumbIconTintList(ColorStateList.valueOf(p.primary));
        return s;
    }

    /** A liquid slider for whole numbers {@code min..max} (values outside are clamped). */
    public static LiquidSlider newSlider(Context c, int min, int max, int value) {
        LiquidSlider s = new LiquidSlider(c);
        int lo = Math.min(min, max), hi = Math.max(min, max);
        if (hi == lo) hi = lo + 1;
        s.setValueFrom(lo);
        s.setValueTo(hi);
        s.setStepSize(1f);
        s.setValue(Math.max(lo, Math.min(hi, value)));
        return s;
    }

    /** Integer value of a whole-number slider. */
    public static int value(LiquidSlider s) {
        return Math.round(s.getValue());
    }

    /**
     * Dropdown as a glass field with a chevron; tapping opens a glass sheet of the options (the
     * design never uses system popup windows, which cannot blur the page).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static android.widget.Spinner styleSpinner(android.widget.Spinner sp) {
        Context c = sp.getContext();
        android.graphics.drawable.Drawable chev = c.getDrawable(R.drawable.ic_expand);
        GlassDrawable field = new GlassDrawable(c, GlassDrawable.Preset.TEXTFIELD);
        if (chev != null) {
            chev = chev.mutate();
            chev.setTint(Palette.of(c).onSurfaceVariant);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{field, chev});
            int sz = Ui.dp(c, 20);
            ld.setLayerSize(1, sz, sz);
            ld.setLayerGravity(1, android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            ld.setLayerInsetEnd(1, Ui.dp(c, 16));
            sp.setBackground(ld);
        } else {
            sp.setBackground(field);
        }
        sp.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 44), Ui.dp(c, 6));
        sp.setMinimumHeight(Ui.dp(c, 52));
        sp.setOnTouchListener((v, e) -> {
            if (e.getAction() != android.view.MotionEvent.ACTION_UP) return true;
            android.app.Activity a = Overlay.activityOf(c);
            android.widget.SpinnerAdapter ad = sp.getAdapter();
            if (a == null || ad == null) return false;
            CharSequence[] labels = new CharSequence[ad.getCount()];
            for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(ad.getItem(i));
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            GlassSheet.options(a, sp.getPrompt(), labels, sp.getSelectedItemPosition(), null).setOnPick(sp::setSelection).show();
            return true;
        });
        return sp;
    }
}
