package com.suvo.agent.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.suvo.agent.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Dialogs, popovers and sheets live INSIDE the screen, in an overlay host above the page and the
 * top bar (z4), instead of in separate system windows. Only that way can their glass see — and
 * blur — the real page behind them. Each overlay has a scrim, closes on a scrim tap and on Back
 * (newest first), and hides the keyboard when it opens.
 *
 * Subclasses build the panel ({@link GlassDialog}, {@link GlassSheet}, {@link GlassPopover}).
 */
public abstract class Overlay implements DialogInterface {

    protected final Activity activity;
    protected final FrameLayout layer;
    protected final View scrim;
    protected boolean cancelable = true;
    protected boolean showing;
    private boolean closing;
    private DialogInterface.OnDismissListener onDismiss;

    protected Overlay(Activity a, float scrimAlpha) {
        this.activity = a;
        layer = new FrameLayout(a);
        layer.setClipChildren(false);
        scrim = new View(a);
        scrim.setBackgroundColor(Palette.alpha(Color.BLACK, scrimAlpha));
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> {
            if (cancelable) dismiss();
        });
        layer.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------------------------- host

    /** The page that overlays blur: the activity's content root (never the overlay host itself). */
    static View contentRoot(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c.getId() != R.id.overlay_host) return c;
        }
        return null;
    }

    static FrameLayout host(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        FrameLayout host = content.findViewById(R.id.overlay_host);
        if (host != null && host.getParent() == content) {
            host.bringToFront();
            return host;
        }
        host = new FrameLayout(a);
        host.setId(R.id.overlay_host);
        host.setClipChildren(false);
        host.setTranslationZ(4f * a.getResources().getDisplayMetrics().density);
        final FrameLayout h = host;
        host.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            h.setTag(R.id.overlay_insets, new int[]{left, top, right, bottom});
            for (int i = 0; i < h.getChildCount(); i++) {
                Object o = h.getChildAt(i).getTag(R.id.overlay_owner);
                if (o instanceof Overlay) ((Overlay) o).onInsets(left, top, right, bottom);
            }
            return insets;
        });
        content.addView(host, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.requestApplyInsets();
        return host;
    }

    static int[] insets(Activity a) {
        Object o = host(a).getTag(R.id.overlay_insets);
        if (o instanceof int[]) return (int[]) o;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets wi = a.getWindow().getDecorView().getRootWindowInsets();
            if (wi != null) {
                android.graphics.Insets b = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                return new int[]{b.left, b.top, b.right, b.bottom};
            }
        }
        return new int[]{0, 0, 0, 0};
    }

    /** Back: closes the newest overlay. Returns true if one was open. */
    public static boolean back(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) return false;
        FrameLayout host = content.findViewById(R.id.overlay_host);
        if (host == null) return false;
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            Object o = host.getChildAt(i).getTag(R.id.overlay_owner);
            if (o instanceof Overlay && ((Overlay) o).showing) {
                Overlay ov = (Overlay) o;
                if (ov.cancelable) ov.dismiss();
                return true;
            }
        }
        return false;
    }

    public static boolean anyOpen(Activity a) {
        ViewGroup content = a.findViewById(android.R.id.content);
        FrameLayout host = content == null ? null : content.findViewById(R.id.overlay_host);
        return host != null && host.getChildCount() > 0;
    }

    /** Finds the Activity behind a (themed) context, or null for a service / application context. */
    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------- lifecycle

    /** Called once the layer is attached, with the current window insets. */
    protected void onInsets(int left, int top, int right, int bottom) {
    }

    protected abstract void animateIn();

    /** Animates out, then calls {@code done}. */
    protected abstract void animateOut(Runnable done);

    public void show() {
        if (showing || activity.isFinishing()) return;
        showing = true;
        View focus = activity.getCurrentFocus();
        if (focus != null) {
            focus.clearFocus();
            InputMethodManager imm = activity.getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        FrameLayout host = host(activity);
        layer.setTag(R.id.overlay_owner, this);
        host.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int[] in = insets(activity);
        onInsets(in[0], in[1], in[2], in[3]);
        Ui.tintAccent(activity, layer);
        layer.post(this::animateIn);
    }

    public boolean isShowing() {
        return showing;
    }

    public void setCancelable(boolean c) {
        cancelable = c;
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener l) {
        onDismiss = l;
    }

    @Override
    public void cancel() {
        dismiss();
    }

    @Override
    public void dismiss() {
        if (!showing || closing) return;
        closing = true;
        animateOut(() -> {
            showing = false;
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            if (onDismiss != null) onDismiss.onDismiss(this);
        });
    }

    protected int dp(float v) {
        return Ui.dp(activity, v);
    }
}
