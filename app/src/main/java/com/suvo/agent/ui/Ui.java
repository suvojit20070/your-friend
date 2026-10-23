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
import android.widget.SeekBar;
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
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
    }

    /** The accent colour: Material You dynamic colour (Android 12+) or the user's choice. */
    public static int accent(Context ctx) {
        String a = Settings.get(ctx).str(Settings.ACCENT, "dynamic");
        if (!"dynamic".equals(a)) {
            try {
                return Color.parseColor(a);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ctx.getColor(R.color.accent);
    }

    /** Applies a non-default accent to views tagged "accent" (buttons, switches, progress bars). */
    public static void tintAccent(Context ctx, View root) {
        if (root == null) return;
        int c = accent(ctx);
        applyTint(root, c);
    }

    private static void applyTint(View v, int c) {
        if (v instanceof android.widget.Switch) {
            Widgets.styleSwitch((android.widget.Switch) v);
        } else if (v instanceof SeekBar) {
            Widgets.styleSeekBar((SeekBar) v);
        } else if ("accent".equals(v.getTag())) {
            if (v instanceof CompoundButton) {
                ((CompoundButton) v).setButtonTintList(ColorStateList.valueOf(c));
            } else if (v instanceof ProgressBar) {
                ((ProgressBar) v).setIndeterminateTintList(ColorStateList.valueOf(c));
                ((ProgressBar) v).setProgressTintList(ColorStateList.valueOf(c));
            } else {
                v.setBackgroundTintList(ColorStateList.valueOf(c));
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTint(g.getChildAt(i), c);
        }
    }

    public static void toast(Context ctx, int res) {
        Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context ctx, CharSequence text) {
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
    }

    public static AlertDialog.Builder dialog(Context ctx) {
        return new AlertDialog.Builder(ctx, R.style.Theme_YourFriend_Dialog);
    }

    /** Shows a dialog with background blur where the device supports cross-window blur. */
    public static AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(R.drawable.glass_dialog);
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(48);
            }
        }
        d.show();
        tintAccent(d.getContext(), w == null ? null : w.getDecorView());
        int c = accent(d.getContext());
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button btn = d.getButton(which);
            if (btn != null) {
                btn.setTextColor(which == AlertDialog.BUTTON_POSITIVE ? c : d.getContext().getColor(R.color.text_secondary));
                btn.setAllCaps(false);
            }
        }
        return d;
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
