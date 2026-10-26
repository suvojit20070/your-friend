package com.suvo.agent.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.suvo.agent.R;
import com.suvo.agent.data.Settings;

/** Small UI helpers shared by all screens. */
public final class Ui {

    private Ui() {
    }

    public static void edgeToEdge(Window w) {
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        // The warm tonal page colour of the current seed, painted on the window itself.
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Palette.of(w.getContext()).background));
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
    }

    /** The primary colour of the tonal palette made from the chosen seed (or the wallpaper on Android 12+). */
    public static int accent(Context ctx) {
        return Palette.of(ctx).primary;
    }

    /**
     * Applies a non-default accent to views tagged "accent" (buttons, switches, progress bars) and
     * gives every tappable view the spring + glow press effect.
     */
    public static void tintAccent(Context ctx, View root) {
        if (root == null) return;
        int c = accent(ctx);
        applyTint(root, c);
        Fx.attachAll(root);
    }

    private static void applyTint(View v, int c) {
        if (v instanceof com.google.android.material.materialswitch.MaterialSwitch) {
            Widgets.styleSwitch((com.google.android.material.materialswitch.MaterialSwitch) v);
        } else if (v instanceof com.google.android.material.slider.Slider) {
            Widgets.styleSlider((com.google.android.material.slider.Slider) v);
        } else if ("accent".equals(v.getTag())) {
            if (v instanceof android.widget.ImageButton && v.getBackground() != null) {
                v.setBackgroundTintList(ColorStateList.valueOf(c));
                ((android.widget.ImageButton) v).setImageTintList(ColorStateList.valueOf(Palette.of(v.getContext()).onPrimary));
            } else if (v instanceof CompoundButton) {
                ((CompoundButton) v).setButtonTintList(ColorStateList.valueOf(c));
            } else if (v instanceof ProgressBar) {
                ((ProgressBar) v).setIndeterminateTintList(ColorStateList.valueOf(c));
                ((ProgressBar) v).setProgressTintList(ColorStateList.valueOf(c));
            } else {
                v.setBackgroundTintList(ColorStateList.valueOf(c));
                if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(Palette.of(v.getContext()).onPrimary);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTint(g.getChildAt(i), c);
        }
    }

    public static void toast(Context ctx, int res) {
        toast(ctx, ctx.getString(res));
    }

    /**
     * Short message. Inside a screen it is a floating glass pill above the composer (or near the
     * bottom); from a service it falls back to a system toast.
     */
    public static void toast(Context ctx, CharSequence text) {
        Activity a = ctx instanceof Activity ? (Activity) ctx : null;
        if (a != null && !a.isFinishing() && a.getWindow() != null && a.getWindow().getDecorView().isAttachedToWindow()) {
            View anchor = a.getWindow().getDecorView().findViewWithTag("composer");
            Fx.pill(a, text, anchor);
            return;
        }
        Toast.makeText(ctx.getApplicationContext(), text, text.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    public static AlertDialog.Builder dialog(Context ctx) {
        return new AlertDialog.Builder(ctx, R.style.Theme_YourFriend_Dialog);
    }

    /**
     * Shows a dialog as a 48dp glass squircle: the panel blurs what is behind it (Android 12+), a
     * 42% scrim, a title in 24sp, and equal-width capsule buttons (the positive one is the single
     * solid primary). It settles in with a fade and a 1.04 → 1 scale instead of popping up.
     */
    public static AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        Window w = d.getWindow();
        Context ctx = d.getContext();
        Palette pal = Palette.of(ctx);
        if (w != null) {
            GlassDrawable glass = new GlassDrawable(ctx, GlassDrawable.Preset.DIALOG);
            int side = dp(ctx, 24);
            w.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(glass, side, side, side, side));
            w.setDimAmount(0.42f);
            if (Build.VERSION.SDK_INT >= 31) {
                WindowManager wm = ctx.getSystemService(WindowManager.class);
                if (wm != null && wm.isCrossWindowBlurEnabled()) {
                    w.setBackgroundBlurRadius(Math.round(glass.blurPx()));
                    glass.setLive(true);
                }
            }
            w.setWindowAnimations(0);
        }
        d.show();
        if (w != null) {
            int maxW = dp(ctx, 360 + 48);
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screenW, maxW), ViewGroup.LayoutParams.WRAP_CONTENT);
            View content = w.getDecorView();
            content.setAlpha(0f);
            content.setScaleX(1.04f);
            content.setScaleY(1.04f);
            content.animate().alpha(1f).setDuration(120).setInterpolator(new android.view.animation.LinearInterpolator()).start();
            content.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).start();
        }
        tintAccent(ctx, w == null ? null : w.getDecorView());
        styleDialogButtons(d, pal);
        return d;
    }

    private static void styleDialogButtons(AlertDialog d, Palette pal) {
        Context ctx = d.getContext();
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        ViewGroup bar = pos == null ? null : (ViewGroup) pos.getParent();
        if (bar == null) return;
        if (bar instanceof android.widget.LinearLayout) ((android.widget.LinearLayout) bar).setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 20));
        for (int i = 0; i < bar.getChildCount(); i++) {
            View v = bar.getChildAt(i);
            if (!(v instanceof Button)) {
                v.setVisibility(View.GONE); // the spacer between neutral and the others
                continue;
            }
            Button btn = (Button) v;
            if (btn.getVisibility() != View.VISIBLE || btn.getText() == null || btn.getText().length() == 0) continue;
            boolean primary = btn == pos;
            btn.setAllCaps(false);
            btn.setTextSize(16f);
            btn.setTextColor(primary ? pal.onPrimary : pal.onSurface);
            btn.setBackground(new TonalDrawable(primary ? pal.primary : pal.surfaceHighest, -1).pressable(pal.pressed()));
            btn.setStateListAnimator(null);
            btn.setMinHeight(dp(ctx, 56));
            btn.setMinimumHeight(dp(ctx, 56));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, dp(ctx, 56), 1f);
            lp.setMargins(dp(ctx, 6), 0, dp(ctx, 6), 0);
            btn.setLayoutParams(lp);
        }
    }

    public interface TextCallback {
        void onText(String text);
    }

    public static void prompt(Activity a, int titleRes, String initial, boolean multiline, TextCallback cb) {
        EditText e = new EditText(a, null, 0, R.style.YF_Input);
        e.setText(initial == null ? "" : initial);
        e.setSelection(e.getText().length());
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
        }
        FrameLayout box = new FrameLayout(a);
        int pad = dp(a, 20);
        box.setPadding(pad, dp(a, 8), pad, 0);
        box.addView(e);
        show(dialog(a).setTitle(titleRes).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onText(e.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null));
    }

    public static void confirm(Activity a, int titleRes, CharSequence message, Runnable onYes) {
        show(dialog(a).setTitle(titleRes).setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> onYes.run())
                .setNegativeButton(android.R.string.cancel, null));
    }

    public static void copy(Context ctx, CharSequence text) {
        ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Your Friend", text));
            toast(ctx, R.string.copied);
        }
    }

    public static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    public static CharSequence ago(long time) {
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
    }

    public static void setTint(Drawable d, int color) {
        if (d != null) d.setTint(color);
    }
}
