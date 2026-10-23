package com.suvo.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.Switch;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Material 3 look for the framework Switch and SeekBar, drawn in code so they follow the user's
 * accent colour and need no extra library:
 * <ul>
 *   <li>Switch: 52×32dp pill track; off = outlined track with a small grey thumb, on = accent track
 *   with a larger thumb (the Material 3 switch).</li>
 *   <li>Slider: thick rounded track with the active part in accent and a slim vertical handle
 *   (the Material 3 expressive slider).</li>
 * </ul>
 */
public final class Widgets {

    private Widgets() {
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Switch styleSwitch(Switch s) {
        Context c = s.getContext();
        int accent = Ui.accent(c);
        int onAccent = c.getColor(R.color.text_on_accent);
        int trackOff = c.getColor(R.color.switch_track_off);
        int outline = c.getColor(R.color.switch_thumb_off);
        int h = dp(c, 32);
        int w = dp(c, 52);
        int stroke = dp(c, 2);

        GradientDrawable trackOn = new GradientDrawable();
        trackOn.setShape(GradientDrawable.RECTANGLE);
        trackOn.setCornerRadius(h / 2f);
        trackOn.setColor(accent);
        trackOn.setSize(w, h);
        GradientDrawable trackOffD = new GradientDrawable();
        trackOffD.setShape(GradientDrawable.RECTANGLE);
        trackOffD.setCornerRadius(h / 2f);
        trackOffD.setColor(trackOff);
        trackOffD.setStroke(stroke, outline);
        trackOffD.setSize(w, h);
        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, trackOn);
        track.addState(new int[]{}, trackOffD);

        // The thumb is drawn across the full track height, so circles are made with insets.
        int thumbBox = w / 2;
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, circle(onAccent, dp(c, 24), thumbBox, h));
        thumb.addState(new int[]{android.R.attr.state_pressed}, circle(outline, dp(c, 28), thumbBox, h));
        thumb.addState(new int[]{}, circle(outline, dp(c, 16), thumbBox, h));

        s.setThumbTintList(null);
        s.setTrackTintList(null);
        s.setThumbDrawable(thumb);
        s.setTrackDrawable(track);
        s.setShowText(false);
        s.setSwitchMinWidth(w);
        s.setBackground(null);
        return s;
    }

    private static Drawable circle(int color, int size, int boxW, int boxH) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        int ix = Math.max(0, (boxW - size) / 2);
        int iy = Math.max(0, (boxH - size) / 2);
        InsetDrawable inset = new InsetDrawable(d, ix, iy, ix, iy);
        return new SizedDrawable(inset, boxW, boxH);
    }

    public static SeekBar styleSeekBar(SeekBar sb) {
        Context c = sb.getContext();
        int accent = Ui.accent(c);
        int trackH = dp(c, 14);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.surface_3));
        bg.setCornerRadius(trackH / 2f);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(accent);
        fg.setCornerRadius(trackH / 2f);
        ClipDrawable progress = new ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{bg, progress});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            layers.setLayerHeight(i, trackH);
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }

        GradientDrawable handle = new GradientDrawable();
        handle.setColor(accent);
        handle.setCornerRadius(dp(c, 3));
        handle.setSize(dp(c, 5), dp(c, 36));

        sb.setProgressTintList(null);
        sb.setProgressBackgroundTintList(null);
        sb.setThumbTintList(ColorStateList.valueOf(accent));
        sb.setProgressDrawable(layers);
        sb.setThumb(handle);
        sb.setThumbOffset(dp(c, 2));
        sb.setSplitTrack(true);
        sb.setMinimumHeight(dp(c, 44));
        sb.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        sb.setBackground(null);
        return sb;
    }

    /** Dropdown with the app's input look and a rounded popup. */
    public static android.widget.Spinner styleSpinner(android.widget.Spinner sp) {
        Context c = sp.getContext();
        sp.setBackgroundResource(R.drawable.spinner_bg);
        sp.setPopupBackgroundResource(R.drawable.group_card);
        sp.setPadding(dp(c, 4), dp(c, 6), dp(c, 40), dp(c, 6));
        sp.setMinimumHeight(dp(c, 52));
        return sp;
    }

    /** Wraps a drawable with a fixed intrinsic size (Switch measures its thumb by intrinsic size). */
    static final class SizedDrawable extends android.graphics.drawable.DrawableWrapper {
        private final int w;
        private final int h;

        SizedDrawable(Drawable d, int w, int h) {
            super(d);
            this.w = w;
            this.h = h;
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }
    }
}
