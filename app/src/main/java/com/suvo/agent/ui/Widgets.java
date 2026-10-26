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

    /** The liquid toggle (64 x 28 capsule, glass thumb while held). */
    public static LiquidSwitch newSwitch(Context c) {
        return new LiquidSwitch(c);
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
