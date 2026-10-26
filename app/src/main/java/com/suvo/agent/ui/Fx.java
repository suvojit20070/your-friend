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

    /** Foreground drawable: reacts to the pressed state of its view with a scale spring and a glow. */
    static final class PressFx extends Drawable {
        private final View view;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glow;
        private Spring scale;
        private boolean pressed;
        private float hx = -1, hy = -1;
        private float glowRadius;
        private float glowAlpha;
        private ValueAnimator glowAnim;

        PressFx(View v, int glowColor) {
            view = v;
            glow = glowColor;
        }

        private boolean small() {
            float d = view.getResources().getDisplayMetrics().density;
            return view.getWidth() <= 72 * d && view.getHeight() <= 72 * d;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void setHotspot(float x, float y) {
            hx = x;
            hy = y;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean p = false, enabled = false;
            for (int s : state) {
                if (s == android.R.attr.state_pressed) p = true;
                if (s == android.R.attr.state_enabled) enabled = true;
            }
            if (!enabled) p = false;
            if (p == pressed) return false;
            pressed = p;
            // Corner glass buttons swell (liquid glass); every other control squishes:
            // 0.92 for buttons, 0.985 for large surfaces. Springs: damping 0.5, stiffness 200 / 300.
            boolean corner = "corner".equals(view.getTag());
            boolean small = small();
            if (scale == null) {
                scale = new Spring(1f, corner ? 300f : 200f, 0.5f, v -> {
                    view.setScaleX(v);
                    view.setScaleY(v);
                });
            }
            if (p && corner) unclipParents(view);
            if (p) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                scale.animateTo(corner ? 1.15f : small ? 0.92f : 0.985f);
                burst(small || corner);
            } else {
                scale.animateTo(1f);
            }
            return false;
        }

        private void burst(boolean small) {
            if (glowAnim != null) glowAnim.cancel();
            Rect b = getBounds();
            float cx = small || hx < 0 ? b.exactCenterX() : hx;
            float cy = small || hy < 0 ? b.exactCenterY() : hy;
            final float fx = cx, fy = cy;
            float max = small ? Math.min(b.width(), b.height()) * 0.5f
                    : (float) Math.hypot(Math.max(fx, b.width() - fx), Math.max(fy, b.height() - fy));
            glowAnim = ValueAnimator.ofFloat(0f, 1f);
            glowAnim.setDuration(small ? 520 : 620);
            glowAnim.setInterpolator(new DecelerateInterpolator(1.8f));
            glowAnim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                glowRadius = Math.max(1f, max * (0.25f + 0.75f * t));
                glowAlpha = (1f - t) * (small ? 0.30f : 0.15f);
                hx = fx;
                hy = fy;
                invalidateSelf();
            });
            glowAnim.start();
        }

        @Override
        public void draw(Canvas c) {
            if (glowAlpha <= 0.004f || glowRadius <= 1f) return;
            int a = Math.round(255 * glowAlpha);
            int center = (glow & 0x00FFFFFF) | (a << 24);
            paint.setShader(new RadialGradient(hx, hy, glowRadius, new int[]{center, (glow & 0x00FFFFFF) | ((a / 3) << 24), 0},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, glowRadius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

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
        ViewParent p = v.getParent();
        for (int depth = 0; p instanceof ViewGroup && depth < 2; depth++) {
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

    /** Gives one view the spring + glow press effect. */
    public static void press(View v) {
        if (v == null || v.getTag(R.id.fx_press) != null) return;
        v.setTag(R.id.fx_press, Boolean.TRUE);
        v.setForeground(new PressFx(v, glowColor(v.getContext())));
    }

    /** Adds the press effect to every tappable view under root (lists and text inputs excluded). */
    public static void attachAll(View root) {
        if (root == null) return;
        walk(root, glowColor(root.getContext()));
    }

    private static void walk(View v, int glow) {
        if (v instanceof AdapterView || v instanceof EditText || v instanceof CompoundButton
                || v instanceof android.view.TextureView || v instanceof android.widget.SeekBar
                || v instanceof com.suvo.agent.character.CharacterView || v.getClass().getName().contains("Slider")) {
            return;
        }
        if (v.isClickable() && v.getTag(R.id.fx_press) == null && !(v instanceof ScrollView) && !(v instanceof AbsListView)) {
            v.setTag(R.id.fx_press, Boolean.TRUE);
            v.setForeground(new PressFx(v, glow));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), glow);
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

    /** Glass popup menu that grows out of {@code anchor} with a springy overshoot. */
    public static Dialog menu(Activity a, View anchor, CharSequence title, List<Item> items, Pick pick) {
        Dialog d = new Dialog(a, R.style.Theme_YourFriend_Dialog);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GlassDrawable glass = new GlassDrawable(a, GlassDrawable.Preset.POPOVER);
        // The screen behind is blurred by the window (Android 12+), so the panel can be light glass.
        if (Build.VERSION.SDK_INT >= 31) glass.setLive(true);
        box.setBackground(glass);
        box.setClipToOutline(true);
        box.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        Palette pal = Palette.of(a);
        box.setElevation(0f);
        if (title != null) {
            TextView t = new TextView(a, null, 0, R.style.YF_Caption);
            t.setText(title);
            t.setPadding(Ui.dp(a, 18), Ui.dp(a, 8), Ui.dp(a, 18), Ui.dp(a, 6));
            box.addView(t);
        }
        int accent = Ui.accent(a);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 44));
            row.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
            // Field-16 highlight inset 4 x 2 dp: pressed 8%, checked 12%.
            int base = it.checked ? Palette.alpha(pal.dark ? Color.WHITE : Color.BLACK, 0.12f) : 0;
            row.setBackground(new TonalDrawable(base, Ui.dp(a, 16)).pressable(pal.pressed()));
            row.setTag(R.id.fx_press, "row");
            if (it.icon != 0) {
                ImageView iv = new ImageView(a);
                iv.setImageResource(it.icon);
                iv.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20));
                ip.setMarginEnd(Ui.dp(a, 14));
                row.addView(iv, ip);
            }
            TextView t = new TextView(a, null, 0, R.style.YF_Body);
            t.setText(it.label);
            t.setTextColor(pal.onSurface);
            t.setTextSize(15f);
            t.setMaxLines(2);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (it.checked) {
                ImageView ck = new ImageView(a);
                ck.setImageResource(R.drawable.ic_check);
                ck.setImageTintList(ColorStateList.valueOf(accent));
                row.addView(ck, new LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)));
            }
            final int idx = i;
            row.setClickable(true);
            row.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                d.dismiss();
                // Not v.postDelayed: the row is detached once the menu closes, and a detached view
                // never runs its queued callbacks.
                MAIN.postDelayed(() -> pick.onPick(idx), 60);
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.setMargins(Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 2));
            box.addView(row, rl);
        }
        attachAll(box);
        FrameLayout wrap = new FrameLayout(a);
        int m = Ui.dp(a, 12);
        wrap.setPadding(m, m, m, m);
        wrap.setClipToPadding(false);
        wrap.addView(box, new FrameLayout.LayoutParams(Ui.dp(a, 272), ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int menuW = Ui.dp(a, 272) + 2 * m;
        int x = loc[0] + anchor.getWidth() / 2 - menuW / 2;
        x = Math.max(0, Math.min(screenW - menuW, x));
        int y = loc[1] + anchor.getHeight() - m + Ui.dp(a, 4);
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.TOP | Gravity.START);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.x = x;
            lp.y = y;
            lp.windowAnimations = 0;
            lp.dimAmount = 0.18f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                lp.setBlurBehindRadius(Ui.dp(a, 18));
            }
            w.setAttributes(lp);
        }
        final float pivotX = loc[0] + anchor.getWidth() / 2f - x - m;
        box.setPivotX(Math.max(0, Math.min(Ui.dp(a, 272), pivotX)));
        box.setPivotY(0);
        box.setAlpha(0f);
        box.setScaleX(0.35f);
        box.setScaleY(0.35f);
        // Condenses out of the button: 240 ms FastOutSlowIn, rows fade in after the first quarter.
        for (int i = 0; i < box.getChildCount(); i++) box.getChildAt(i).setAlpha(0f);
        d.setOnShowListener(di -> {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(240);
            va.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
            va.addUpdateListener(an -> {
                float v = (float) an.getAnimatedValue();
                float sc = 0.35f + 0.65f * v;
                box.setScaleX(sc);
                box.setScaleY(sc);
                box.setAlpha(Math.min(1f, v * 2.5f));
                float rowsA = Math.max(0f, (v - 0.25f) / 0.75f);
                for (int i = 0; i < box.getChildCount(); i++) box.getChildAt(i).setAlpha(rowsA);
            });
            va.start();
        });
        d.show();
        return d;
    }

    // ================================================================================= sheet

    /** Frosted bottom sheet with {@code content}; returns the dialog (dismiss animates out). */
    public static Dialog sheet(Activity a, CharSequence title, View content) {
        final Dialog[] holder = new Dialog[1];
        Dialog d = new Dialog(a, R.style.Theme_YourFriend_Dialog) {
            private boolean closing;

            @Override
            public void dismiss() {
                if (closing) return;
                closing = true;
                View panel = (View) getWindow().getDecorView().getTag(R.id.fx_sheet_panel);
                if (panel == null) {
                    super.dismiss();
                    return;
                }
                panel.animate().translationY(panel.getHeight() + Ui.dp(a, 24)).setDuration(240)
                        .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                        .withEndAction(super::dismiss).start();
            }
        };
        holder[0] = d;
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GlassDrawable bg = new GlassDrawable(a, GlassDrawable.Preset.SHEET).topOnly();
        if (Build.VERSION.SDK_INT >= 31) bg.setLive(true); // the window blurs the screen behind
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setPadding(Ui.dp(a, 18), Ui.dp(a, 10), Ui.dp(a, 18), Ui.dp(a, 28));
        View handle = new View(a);
        handle.setBackgroundResource(R.drawable.sheet_handle);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(a, 14);
        panel.addView(handle, hp);
        if (title != null) {
            // Sheet title: 18sp SemiBold in primary.
            TextView t = new TextView(a, null, 0, R.style.YF_Title);
            t.setText(title);
            t.setTextSize(18f);
            t.setTextColor(Ui.accent(a));
            t.setPadding(Ui.dp(a, 6), 0, 0, Ui.dp(a, 12));
            panel.addView(t);
        }
        panel.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        attachAll(panel);
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.getDecorView().setTag(R.id.fx_sheet_panel, panel);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.windowAnimations = 0;
            lp.dimAmount = 0.42f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                lp.setBlurBehindRadius(Ui.dp(a, 14));
            }
            w.setAttributes(lp);
            // Content goes edge to edge at the bottom; keep it above the navigation bar.
            panel.setOnApplyWindowInsetsListener((v, insets) -> {
                int nav = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                        : insets.getSystemWindowInsetBottom();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), Ui.dp(a, 28) + nav);
                return insets;
            });
        }
        // Drag down on the sheet to close.
        final float[] start = new float[2];
        panel.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawY();
                    start[1] = v.getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setTranslationY(Math.max(0, start[1] + e.getRawY() - start[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getTranslationY() > v.getHeight() * 0.25f) d.dismiss();
                    else v.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
                    return true;
                default:
                    return false;
            }
        });
        panel.setVisibility(View.INVISIBLE);
        d.setOnShowListener(di -> panel.post(() -> {
            panel.setTranslationY(panel.getHeight() + Ui.dp(a, 24));
            panel.setVisibility(View.VISIBLE);
            panel.animate().translationY(0).setDuration(420).setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start();
        }));
        d.show();
        Ui.tintAccent(a, panel);
        return d;
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
