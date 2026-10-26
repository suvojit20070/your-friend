package com.suvo.agent.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.suvo.agent.R;

import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Motion for the liquid-glass UI, all with real spring physics instead of fixed-duration tweens:
 * <ul>
 *   <li>{@link #attachAll}: every tappable view gets a press effect — small buttons swell to 1.15×
 *   and spring back (damping 0.55, stiffness 400) with a soft radial glow bursting from the touch
 *   point; rows and cards dip slightly and spring back with the same glow. Replaces the flat ripple.</li>
 *   <li>{@link #pill}: floating glass toast that springs up above the composer and fades away.</li>
 *   <li>{@link #menu}: popup menu that morphs out of the tapped button (scale 0.35→1 from the
 *   anchor, with overshoot) over a blurred screen.</li>
 *   <li>{@link #sheet}: frosted bottom sheet sliding up on a decelerating curve over a dimmed,
 *   blurred screen; drag it down to close.</li>
 *   <li>{@link #expand}/{@link #collapse}: accordion height animation with spring settle.</li>
 * </ul>
 */
public final class Fx {

    private Fx() {
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Runs {@code r} on the main thread after {@code ms} (safe after a dialog or sheet has closed). */
    public static void later(Runnable r, long ms) {
        MAIN.postDelayed(r, ms);
    }

    // =============================================================================== springs

    /** A damped spring driving one float value, stepped on the display's frame clock. */
    public static final class Spring implements Choreographer.FrameCallback {
        public interface Setter {
            void set(float v);
        }

        private final Setter setter;
        private final float stiffness;
        private final float damping;
        private float value;
        private float velocity;
        private float target;
        private long last;
        private boolean running;
        private Runnable onEnd;

        public Spring(float start, float stiffness, float dampingRatio, Setter setter) {
            this.value = start;
            this.target = start;
            this.stiffness = stiffness;
            this.damping = dampingRatio;
            this.setter = setter;
        }

        public void animateTo(float t) {
            animateTo(t, velocity);
        }

        public void animateTo(float t, float v) {
            target = t;
            velocity = v;
            if (!running) {
                running = true;
                last = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        public void snapTo(float v) {
            cancel();
            value = v;
            target = v;
            velocity = 0;
            setter.set(v);
        }

        public void onEnd(Runnable r) {
            onEnd = r;
        }

        public void cancel() {
            if (running) Choreographer.getInstance().removeFrameCallback(this);
            running = false;
        }

        public float value() {
            return value;
        }

        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = last == 0 ? 1f / 60f : Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            float c = 2f * damping * (float) Math.sqrt(stiffness);
            int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float h = dt / steps;
            for (int i = 0; i < steps; i++) {
                float a = -stiffness * (value - target) - c * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            if (Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.01f) {
                value = target;
                velocity = 0;
                running = false;
                setter.set(value);
                if (onEnd != null) onEnd.run();
                return;
            }
            setter.set(value);
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // =========================================================================== press effect

    /**
     * Lets a popping button grow past its parents' edges instead of being cut off: ancestors stop
     * clipping their children (up to the screen root). Glass surfaces draw their own rounded
     * shape, so they do not need outline clipping either.
     */
    /**
     * Lets a pressed small view grow past its own row without being cut, but only within its
     * nearest one or two parents. Never touches the content root, nor a container that holds a
     * scrolling list (unclipping that lets the list draw over the top bar).
     */
    static void unclipParents(View v) {
        unclipParents(v, 2);
    }

    static void unclipParents(View v, int levels) {
        ViewParent p = v.getParent();
        for (int depth = 0; p instanceof ViewGroup && depth < levels; depth++) {
            ViewGroup g = (ViewGroup) p;
            if (g instanceof AbsListView || g instanceof ScrollView || g.getId() == android.R.id.content
                    || !(g.getParent() instanceof ViewGroup) || holdsScroller(g)) break;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            if (g.getClipToOutline() && (g.getBackground() instanceof GlassDrawable || g.getBackground() instanceof android.graphics.drawable.LayerDrawable)) {
                g.setClipToOutline(false);
            }
            p = g.getParent();
        }
    }

    private static boolean holdsScroller(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof AbsListView || c instanceof ScrollView || c instanceof android.widget.HorizontalScrollView) return true;
        }
        ViewParent up = g.getParent();
        // A screen's root column (top bar + content) must keep clipping too.
        return up instanceof ViewGroup && ((ViewGroup) up).getId() == android.R.id.content;
    }

    private static int glowColor(Context c) {
        // A soft white light (white 15% under the finger in the spec), faintly warmed by the primary.
        int acc = Ui.accent(c);
        int r = (Color.red(acc) + 255 * 4) / 5, g = (Color.green(acc) + 255 * 4) / 5, b = (Color.blue(acc) + 255 * 4) / 5;
        return Color.rgb(r, g, b);
    }

    /** Gives one view the press that fits it (liquid, squish or row). */
    public static void press(View v) {
        if (v == null || v.getTag(R.id.fx_press) != null) return;
        LiquidPress.attach(v, styleFor(v));
    }

    /** Adds the press to every tappable view under root (lists and text inputs excluded). */
    public static void attachAll(View root) {
        if (root == null) return;
        walk(root);
    }

    /**
     * Which press a view gets: glass and buttons are liquid (corner buttons swell most), small
     * icons squish, tonal tiles squish a little, other tappable surfaces (rows, cards) get the
     * 8% row press.
     */
    static LiquidPress.Style styleFor(View v) {
        float d = v.getResources().getDisplayMetrics().density;
        Object tag = v.getTag();
        Drawable bg = v.getBackground();
        if ("corner".equals(tag)) return LiquidPress.CORNER;
        if (bg instanceof GlassDrawable) {
            switch (((GlassDrawable) bg).preset) {
                case BUTTON:
                    return LiquidPress.CORNER;
                case COMPOSER:
                    return LiquidPress.COMPOSER;
                case TEXTFIELD:
                    return LiquidPress.FIELD;
                case POPOVER:
                    return LiquidPress.POPOVER;
                default:
                    return LiquidPress.CAPSULE;
            }
        }
        if (v instanceof android.widget.Button) return LiquidPress.CAPSULE;
        boolean small = v.getLayoutParams() != null && v.getLayoutParams().width > 0 && v.getLayoutParams().width <= 72 * d;
        if (v instanceof ImageView) return small && v.getLayoutParams().width <= 40 * d ? LiquidPress.ICON : LiquidPress.CIRCLE;
        if (bg instanceof TonalDrawable) return LiquidPress.TEXT;
        return LiquidPress.ROW;
    }

    private static void walk(View v) {
        if (v instanceof AdapterView || v instanceof EditText || v instanceof CompoundButton
                || v instanceof android.view.TextureView || v instanceof android.widget.SeekBar
                || v instanceof com.suvo.agent.character.CharacterView || v instanceof LiquidSlider) {
            return;
        }
        if (v.isClickable() && v.getTag(R.id.fx_press) == null && !(v instanceof ScrollView) && !(v instanceof AbsListView)) {
            LiquidPress.attach(v, styleFor(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ================================================================================== pill

    private static View currentPill;

    /** Floating glass toast above {@code anchor} (or near the bottom when anchor is null). */
    public static void pill(Activity a, CharSequence text, View anchor) {
        if (a == null || a.isFinishing() || a.getWindow() == null) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (currentPill != null && currentPill.getParent() instanceof ViewGroup) {
            ((ViewGroup) currentPill.getParent()).removeView(currentPill);
        }
        TextView t = new TextView(a, null, 0, R.style.YF_Body);
        t.setText(text);
        t.setTextColor(a.getColor(R.color.text_primary));
        t.setTextSize(14.5f);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(4);
        int ph = Ui.dp(a, 20), pv = Ui.dp(a, 11);
        t.setPadding(ph, pv, ph, pv);
        View content = a.findViewById(android.R.id.content);
        BackdropDrawable.apply(t, content, GlassDrawable.Preset.PILL);
        t.setElevation(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int bottom;
        if (anchor != null && anchor.isShown()) {
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            bottom = decor.getHeight() - loc[1] + Ui.dp(a, 12);
        } else {
            bottom = Ui.dp(a, 120);
        }
        lp.bottomMargin = bottom;
        lp.leftMargin = Ui.dp(a, 24);
        lp.rightMargin = Ui.dp(a, 24);
        decor.addView(t, lp);
        currentPill = t;
        // If the screen closes right away (e.g. "Saved" then finish()), fall back to a system toast.
        final long shownAt = android.os.SystemClock.uptimeMillis();
        final Context app = a.getApplicationContext();
        t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (android.os.SystemClock.uptimeMillis() - shownAt < 700 && v.getAlpha() > 0.05f && a.isFinishing()) {
                    android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        float dy = Ui.dp(a, 26);
        t.setAlpha(0f);
        t.setTranslationY(dy);
        Spring s = new Spring(0f, 420f, 0.6f, v -> {
            t.setTranslationY(dy * (1f - v));
            float sc = 0.82f + 0.18f * v;
            t.setScaleX(sc);
            t.setScaleY(sc);
            t.setAlpha(Math.max(0f, Math.min(1f, v * 1.4f)));
        });
        s.animateTo(1f);
        long stay = Math.min(4500, 1600 + text.length() * 35L);
        t.postDelayed(() -> t.animate().alpha(0f).translationY(Ui.dp(a, 10)).scaleX(0.94f).scaleY(0.94f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
                    if (currentPill == t) currentPill = null;
                }).start(), stay);
    }

    // ================================================================================== menu

    public interface Pick {
        void onPick(int index);
    }

    /** One entry of a {@link #menu}. */
    public static final class Item {
        public final CharSequence label;
        public final int icon;
        public final boolean checked;

        public Item(CharSequence label, int icon, boolean checked) {
            this.label = label;
            this.icon = icon;
            this.checked = checked;
        }
    }

    /** Glass popover that morphs out of {@code anchor} (in the screen, blurring the page). */
    public static Overlay menu(Activity a, View anchor, CharSequence title, List<Item> items, Pick pick) {
        return GlassPopover.menu(a, anchor, title, items, pick);
    }

    // ================================================================================= sheet

    /** Glass bottom sheet with {@code content} (in the screen, blurring the page); dismiss animates out. */
    public static GlassSheet sheet(Activity a, CharSequence title, View content) {
        GlassSheet s = new GlassSheet(a, title, content);
        s.show();
        return s;
    }

    /** A grid tile (icon + label) for sheets. */
    public static View tile(Activity a, int icon, CharSequence label, View.OnClickListener l) {
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(new TonalDrawable(Palette.alpha(Palette.of(a).surfaceHighest, 0.85f), Ui.dp(a, 20)));
        tile.setClipToOutline(true);
        tile.setPadding(Ui.dp(a, 6), Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 14));
        ImageView iv = new ImageView(a);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.accent(a)));
        tile.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26)));
        TextView t = new TextView(a, null, 0, R.style.YF_Caption);
        t.setText(label);
        t.setTextColor(a.getColor(R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setPadding(0, Ui.dp(a, 8), 0, 0);
        tile.addView(t);
        tile.setClickable(true);
        tile.setOnClickListener(l);
        return tile;
    }

    // ============================================================================= accordion

    /** Smoothly opens {@code v} (height + fade + slight slide), letting parents resize with it. */
    public static void expand(View v) {
        Object old = v.getTag(R.id.fx_height_anim);
        if (old instanceof Spring) ((Spring) old).cancel();
        ViewParent p = v.getParent();
        int width = p instanceof View ? ((View) p).getWidth() - ((View) p).getPaddingLeft() - ((View) p).getPaddingRight() : 0;
        v.measure(width > 0 ? View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = v.getMeasuredHeight();
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = 1;
        v.setLayoutParams(lp);
        v.setVisibility(View.VISIBLE);
        final float slide = -Ui.dp(v.getContext(), 8);
        Spring s = new Spring(0f, 300f, 0.82f, f -> {
            lp.height = Math.max(1, Math.round(target * f));
            v.setAlpha(Math.max(0f, Math.min(1f, f)));
            v.setTranslationY(slide * (1f - Math.min(1f, f)));
            v.setLayoutParams(lp);
        });
        s.onEnd(() -> {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            v.setLayoutParams(lp);
            v.setTranslationY(0);
            v.setAlpha(1f);
        });
        v.setTag(R.id.fx_height_anim, s);
        s.animateTo(1f);
    }

    /** Smoothly closes {@code v}, then sets it GONE. */
    public static void collapse(View v) {
        Object old = v.getTag(R.id.fx_height_anim);
        if (old instanceof Spring) ((Spring) old).cancel();
        final int from = v.getHeight();
        if (from <= 0 || v.getVisibility() != View.VISIBLE) {
            v.setVisibility(View.GONE);
            return;
        }
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        ValueAnimator an = ValueAnimator.ofFloat(1f, 0f);
        an.setDuration(260);
        an.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        an.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue();
            lp.height = Math.max(1, Math.round(from * f));
            v.setAlpha(f);
            v.setLayoutParams(lp);
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setVisibility(View.GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                v.setAlpha(1f);
            }
        });
        v.setTag(R.id.fx_height_anim, an);
        an.start();
    }

    /** Rotates a chevron with a little spring (0° closed, 90° open). */
    public static void chevron(View v, boolean open) {
        v.animate().rotation(open ? 90f : 0f).setDuration(320).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }
}
