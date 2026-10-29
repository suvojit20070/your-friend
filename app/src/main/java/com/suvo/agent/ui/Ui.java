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
        ThemeMap.apply(root);
        Palette pal = Palette.of(ctx);
        applyTint(root, pal.primary, pal);
        Fx.attachAll(root);
    }

    private static void applyTint(View v, int c, Palette pal) {
        if (v.getTag(R.id.theme_tint) == pal && !(v instanceof ViewGroup)) return;
        if (v instanceof com.google.android.material.materialswitch.MaterialSwitch) {
            Widgets.styleSwitch((com.google.android.material.materialswitch.MaterialSwitch) v);
        } else if (v instanceof LiquidSlider) {
            // Drawn from the palette already.
        } else if (v instanceof EditText && v.getBackground() != null && v.getTag(R.id.fx_press) == null
                && !(v.getBackground() instanceof GlassDrawable)) {
            // Every boxed text field becomes a glass field (blurs the page when it floats over it).
            Activity act = Overlay.activityOf(v.getContext());
            glassField((EditText) v, act == null ? null : Overlay.contentRoot(act));
        } else if ("accent".equals(v.getTag()) && v.getTag(R.id.theme_tint) != pal) {
            if (!(v instanceof ViewGroup)) v.setTag(R.id.theme_tint, pal);
            if (v instanceof android.widget.ImageButton && v.getBackground() != null) {
                v.setBackgroundTintList(ColorStateList.valueOf(c));
                ((android.widget.ImageButton) v).setImageTintList(ColorStateList.valueOf(pal.onPrimary));
            } else if (v instanceof CompoundButton) {
                ((CompoundButton) v).setButtonTintList(ColorStateList.valueOf(c));
            } else if (v instanceof ProgressBar) {
                ((ProgressBar) v).setIndeterminateTintList(ColorStateList.valueOf(c));
                ((ProgressBar) v).setProgressTintList(ColorStateList.valueOf(c));
            } else {
                v.setBackgroundTintList(ColorStateList.valueOf(c));
                if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(pal.onPrimary);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTint(g.getChildAt(i), c, pal);
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

    /** A glass dialog builder (same calls as AlertDialog.Builder). */
    public static GlassDialog.Builder dialog(Context ctx) {
        return new GlassDialog.Builder(ctx);
    }

    /**
     * Shows it inside the screen's overlay host: a 48dp glass panel that blurs the page, 42% scrim,
     * equal-width liquid capsule buttons; plain lists open as a glass sheet.
     */
    public static Overlay show(GlassDialog.Builder b) {
        return b.show();
    }

    public interface TextCallback {
        void onText(String text);
    }

    public static void prompt(Activity a, int titleRes, String initial, boolean multiline, TextCallback cb) {
        EditText e = new EditText(a, null, 0, R.style.YF_Input);
        glassField(e, Overlay.contentRoot(a));
        e.setText(initial == null ? "" : initial);
        e.setSelection(e.getText().length());
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setMaxLines(8); // longer text scrolls inside the field; the buttons stay on screen
            e.setVerticalScrollBarEnabled(false);
        }
        FrameLayout box = new FrameLayout(a);
        box.setPadding(0, dp(a, 4), 0, 0);
        box.addView(e);
        show(dialog(a).setTitle(titleRes).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onText(e.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null));
    }

    /**
     * Glass text field: 36dp squircle, min height 52dp, primary-container hue tint, thin rim, liquid
     * press while unfocused. {@code source} is the page it may blur (null = tonal glass).
     */
    public static void glassField(EditText e, View source) {
        if (source != null && !BackdropDrawable.isSelfOrAncestor(source, e)) BackdropDrawable.apply(e, source, GlassDrawable.Preset.TEXTFIELD);
        else e.setBackground(new GlassDrawable(e.getContext(), GlassDrawable.Preset.TEXTFIELD));
        int h = dp(e.getContext(), 18), v = dp(e.getContext(), 14);
        e.setPadding(h, v, h, v);
        e.setMinHeight(dp(e.getContext(), 52));
        e.setMinimumHeight(dp(e.getContext(), 52));
        e.setTextColor(Palette.of(e.getContext()).onSurface);
        e.setHintTextColor(Palette.alpha(Palette.of(e.getContext()).onSurfaceVariant, 0.72f));
        e.setTag(R.id.fx_press, Boolean.TRUE);
        if (e.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) e.getLayoutParams()).topMargin = dp(e.getContext(), 8);
        }
        int cls = e.getInputType() & android.text.InputType.TYPE_MASK_VARIATION;
        if (cls == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD || cls == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
            secretToggle(e);
        }
    }

    /** Secret field: an eye at the end shows / hides the value. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private static void secretToggle(EditText e) {
        Context c = e.getContext();
        Drawable eye = c.getDrawable(R.drawable.ic_eye);
        if (eye == null) return;
        eye = eye.mutate();
        eye.setTint(Palette.alpha(Palette.of(c).onSurfaceVariant, 0.8f));
        int sz = dp(c, 22);
        eye.setBounds(0, 0, sz, sz);
        e.setCompoundDrawablesRelative(null, null, eye, null);
        e.setCompoundDrawablePadding(dp(c, 8));
        final int hidden = e.getInputType();
        final int shown = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        e.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP && ev.getX() > v.getWidth() - v.getPaddingEnd() - sz - dp(c, 8)) {
                int sel = e.getSelectionEnd();
                boolean isHidden = e.getInputType() == hidden;
                e.setInputType(isHidden ? shown : hidden);
                e.setSelection(Math.max(0, Math.min(sel, e.length())));
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                return true;
            }
            return false;
        });
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
