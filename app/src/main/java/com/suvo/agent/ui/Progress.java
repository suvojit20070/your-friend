package com.suvo.agent.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;

import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * Material 3 progress everywhere instead of the platform's grey bars: a rounded linear indicator
 * (downloads, levels, update) and the Expressive loading indicator (waiting). Coloured from the
 * app's accent. Created with the app's Material theme so they work in any window.
 */
public final class Progress {

    private Progress() {
    }

    private static Context themed(Context ctx) {
        return new ContextThemeWrapper(ctx, R.style.Theme_YourFriend);
    }

    /** A rounded linear bar; indeterminate = moving, else set values with setProgressCompat. */
    public static LinearProgressIndicator linear(Context ctx, boolean indeterminate) {
        Palette pal = Palette.of(ctx);
        LinearProgressIndicator p = new LinearProgressIndicator(themed(ctx));
        p.setIndeterminate(indeterminate);
        p.setMax(100);
        p.setTrackThickness(Ui.dp(ctx, 6));
        p.setTrackCornerRadius(Ui.dp(ctx, 3));
        p.setIndicatorColor(pal.primary);
        p.setTrackColor(Palette.alpha(pal.primary, 0.18f));
        return p;
    }

    /** The Expressive loading indicator (morphing shape), {@code sizeDp} square. */
    public static LoadingIndicator loading(Context ctx, int sizeDp, boolean contained) {
        Palette pal = Palette.of(ctx);
        LoadingIndicator li = new LoadingIndicator(themed(ctx));
        style(li, pal.primary, sizeDp, contained);
        return li;
    }

    /** Colours and sizes a loading indicator (also one from a layout). */
    public static void style(LoadingIndicator li, int color, int sizeDp, boolean contained) {
        Context ctx = li.getContext();
        li.setContainerWidth(Ui.dp(ctx, sizeDp));
        li.setContainerHeight(Ui.dp(ctx, sizeDp));
        li.setIndicatorSize(Math.round(Ui.dp(ctx, sizeDp) * (contained ? 0.66f : 1f)));
        li.setIndicatorColor(new int[]{color});
        li.setContainerColor(contained ? Palette.alpha(color, 0.16f) : 0);
    }
}
