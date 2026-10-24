package com.suvo.agent.character;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.suvo.agent.core.character.Appearance;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.character.PuppetRenderer;
import com.suvo.agent.character.vrm.VrmModel;
import com.suvo.agent.character.vrm.VrmView;
import com.suvo.agent.data.Settings;

import java.io.File;

/**
 * The animated companion. Renders the procedural 2D rig every frame while visible — or, when the user
 * chose the 3D style in Settings and imported a VRM model, the 3D character (the same animator
 * drives both, so blinking, lip sync, looking toward taps and expressions work in either). Reacts
 * to touch (tap: reaction by region; long-press then drag: move inside the parent, snapping to an
 * edge). If the 3D model cannot be shown, it falls back to 2D and {@link #lastVrmError()} says why.
 */
public class CharacterView extends FrameLayout implements Choreographer.FrameCallback {

    private static volatile String lastVrmError = "";

    /** Why the 3D character could not be shown last time ("" when it worked). */
    public static String lastVrmError() {
        return lastVrmError;
    }

    private VrmView vrm;
    private boolean vrmReady;
    private String vrmKey = "";
    private boolean allow3d = true;

    /** The 2D editor preview always shows the 2D rig it edits. */
    public void setAllow3d(boolean allow) {
        allow3d = allow;
        refreshStyle();
    }

    public interface Listener {
        /** Tapped a region of the character (head/face/body). */
        default void onCharacterTapped(CharacterAnimator.TouchRegion region) {
        }

        /** Finished dragging; position is the view's translation relative to its laid-out spot. */
        default void onCharacterMoved(float translationX, float translationY) {
        }

        default void onCharacterLongPressed() {
        }
    }

    private final AndroidGfx gfx = new AndroidGfx();
    private CharacterAnimator animator = new CharacterAnimator(System.nanoTime());
    private PuppetRenderer renderer;
    private CharacterSpec spec;
    private Listener listener;
    private boolean running;
    private long lastFrameNanos;
    private int frameSkip;
    private int frameCounter;
    private boolean draggable = true;
    private boolean snapToEdge = true;

    // Touch state
    private float downX, downY, startTx, startTy;
    private boolean dragging;
    private boolean longPressFired;
    private final Runnable longPress = () -> {
        longPressFired = true;
        if (draggable) {
            dragging = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);
        }
        if (listener != null) listener.onCharacterLongPressed();
    };

    public CharacterView(Context context) {
        this(context, null);
    }

    public CharacterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClipChildren(false);
        setClickable(true);
        setContentDescription(context.getString(com.suvo.agent.R.string.character_content_description));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDraggable(boolean d) {
        draggable = d;
    }

    public void setSnapToEdge(boolean s) {
        snapToEdge = s;
    }

    public CharacterAnimator animator() {
        return animator;
    }

    public CharacterSpec spec() {
        return spec;
    }

    public void setCharacter(CharacterSpec s) {
        spec = s;
        Appearance a = new Appearance();
        a.base = s.base;
        a.hairStyle = s.hairStyle;
        a.eyeShape = s.eyeShape;
        a.outfit = s.outfit;
        a.accessory = s.accessory;
        a.hair = s.hairColor;
        a.eyes = s.eyeColor;
        a.skin = s.skinColor;
        a.outfitColor = s.outfitColor;
        a.accent = s.accentColor;
        renderer = new PuppetRenderer(a);
        animator.idleIntensity = s.idleIntensity;
        animator.blinkRate = s.blinkRate;
        animator.expressiveness = s.expressiveness;
        animator.setExpression(CharacterAnimator.parseExpression(s.defaultExpression));
        refreshStyle();
        invalidate();
    }

    /**
     * Where the 3D character comes from: the model the user imported in Settings, otherwise the
     * one bundled with the app. Null when neither exists (then the built-in 2D rig is drawn).
     */
    public static String modelSource(Context ctx) {
        Settings st = Settings.get(ctx);
        String path = st.str(Settings.VRM_PATH, "");
        if (!path.isEmpty() && new File(path).isFile()) return path;
        String asset = VrmModel.bundledAsset(ctx);
        return asset == null ? null : VrmModel.ASSET_PREFIX + asset;
    }

    /** Applies the current 3D model and camera from Settings (call again after they change). */
    public void refreshStyle() {
        Settings st = Settings.get(getContext());
        String framing = st.str(Settings.VRM_FRAMING, "full");
        String source = allow3d ? modelSource(getContext()) : null;
        String key = "";
        if (source != null) {
            File f = new File(source);
            key = source + ":" + (f.isFile() ? f.lastModified() : 0) + ":" + framing;
        }
        if (key.equals(vrmKey)) return;
        vrmKey = key;
        if (vrm != null) {
            vrm.setActive(false);
            removeView(vrm);
            vrm = null;
        }
        vrmReady = false;
        if (key.isEmpty()) {
            invalidate();
            return;
        }
        vrm = new VrmView(getContext());
        vrm.setFps(frameSkip == 0 ? 60 : 30);
        addView(vrm, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        vrm.setModel(source, framing, new VrmView.Callback() {
            @Override
            public void onLoaded(VrmModel model) {
                vrmReady = true;
                lastVrmError = "";
                invalidate();
            }

            @Override
            public void onError(String message) {
                lastVrmError = message;
                vrmReady = false;
                if (vrm != null) {
                    vrm.setActive(false);
                    removeView(vrm);
                    vrm = null;
                }
                invalidate();
            }
        });
        vrm.setActive(running);
    }

    public boolean isShowing3d() {
        return vrm != null && vrmReady;
    }

    /** quality: "low" (30 fps, flat fills), "medium" (30 fps), "high" (60 fps). */
    public void setQuality(String quality, boolean reducedMotion) {
        gfx.low = "low".equals(quality);
        frameSkip = "high".equals(quality) ? 0 : 1;
        animator.reducedMotion = reducedMotion;
        if (vrm != null) vrm.setFps(frameSkip == 0 ? 60 : 30);
    }

    public void setActivity(CharacterAnimator.Activity a) {
        animator.setActivity(a);
    }

    public void setMouth(float open, float form) {
        animator.setMouthInput(open, form);
    }

    /** Sit on something (floating mode, 3D only). */
    public void setSitting(boolean s) {
        if (vrm != null) vrm.setSitting(s);
    }

    /** Hair/cloth force while the window is dragged (screen axes). */
    public void setWind(float x, float y) {
        if (vrm != null) vrm.setWind(x, y);
    }

    /** {foot, seat} as fractions of this view's height; seat &lt; 0 when the character cannot sit. */
    public float[] anchors() {
        float[] a = vrm != null && vrmReady ? vrm.anchors() : null;
        return a != null ? a : new float[]{0.98f, -1f};
    }

    /** Turns toward a point given in screen coordinates (taps anywhere in the app). */
    public void lookAtScreen(float rawX, float rawY) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float cx = loc[0] + getWidth() / 2f, cy = loc[1] + getHeight() * 0.18f;
        float nx = (rawX - cx) / (dm.widthPixels * 0.5f);
        float ny = (rawY - cy) / (dm.heightPixels * 0.5f);
        animator.lookAt(Math.max(-1f, Math.min(1f, nx)), Math.max(-1f, Math.min(1f, ny)), 2.2f);
    }

    /** Mouth shapes from the audio (a, i, u, e, o). */
    public void setVisemes(float a, float i, float u, float e, float o) {
        animator.setVisemes(a, i, u, e, o);
    }

    public void react(CharacterAnimator.Expression e, float seconds) {
        animator.react(e, seconds);
    }

    /** Look toward a point in this view's coordinates. */
    public void lookAtPoint(float x, float y) {
        float nx = (x - getWidth() / 2f) / Math.max(1f, getWidth() / 2f);
        float ny = (y - getHeight() * 0.4f) / Math.max(1f, getHeight() / 2f);
        animator.lookAt(nx, ny, 1.5f);
    }

    private final Settings.Listener styleListener = key -> {
        if (Settings.CHARACTER_MODE.equals(key) || Settings.VRM_PATH.equals(key) || Settings.VRM_FRAMING.equals(key)) {
            post(this::refreshStyle);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Settings.get(getContext()).addListener(styleListener);
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        Settings.get(getContext()).removeListener(styleListener);
        super.onDetachedFromWindow();
    }

    /** Touches always go to the character itself, never to the 3D surface inside it. */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (isShown()) start();
        else stop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (isShown()) start();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
        if (vrm != null) vrm.setActive(true);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        if (vrm != null) vrm.setActive(false);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNanos != 0) {
            float dt = (frameTimeNanos - lastFrameNanos) / 1e9f;
            animator.update(dt);
        }
        lastFrameNanos = frameTimeNanos;
        if (vrm != null) {
            vrm.setParams(animator.params());
            if (!vrmReady && (frameSkip == 0 || (frameCounter++ % (frameSkip + 1)) == 0)) invalidate();
        } else if (frameSkip == 0 || (frameCounter++ % (frameSkip + 1)) == 0) invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // With a 3D model nothing 2D is drawn — not even while it loads. The 2D rig only appears
        // when no model exists or the model could not be shown.
        if (renderer == null || vrm != null) return;
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(w / PuppetRenderer.WIDTH, h / PuppetRenderer.HEIGHT);
        canvas.save();
        canvas.translate(getPaddingLeft() + (w - PuppetRenderer.WIDTH * scale) / 2f, getPaddingTop() + (h - PuppetRenderer.HEIGHT * scale));
        canvas.scale(scale, scale);
        gfx.attach(canvas);
        renderer.draw(gfx, animator.params());
        canvas.restore();
    }

    private float[] toModel(float x, float y) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(w / PuppetRenderer.WIDTH, h / PuppetRenderer.HEIGHT);
        float ox = getPaddingLeft() + (w - PuppetRenderer.WIDTH * scale) / 2f;
        float oy = getPaddingTop() + (h - PuppetRenderer.HEIGHT * scale);
        return new float[]{(x - ox) / scale, (y - oy) / scale};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startTx = getTranslationX();
                startTy = getTranslationY();
                dragging = false;
                longPressFired = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                lookAtPoint(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (!dragging && Math.hypot(dx, dy) > slop) removeCallbacks(longPress);
                if (dragging) {
                    View parent = (View) getParent();
                    float minX = -getLeft();
                    float maxX = parent == null ? 0 : parent.getWidth() - getRight();
                    float minY = -getTop();
                    float maxY = parent == null ? 0 : parent.getHeight() - getBottom();
                    setTranslationX(Math.max(minX, Math.min(maxX, startTx + dx)));
                    setTranslationY(Math.max(minY, Math.min(maxY, startTy + dy)));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    if (snapToEdge && getParent() instanceof View) {
                        View parent = (View) getParent();
                        float centerX = getLeft() + getTranslationX() + getWidth() / 2f;
                        float target = centerX < parent.getWidth() / 2f ? -getLeft() : parent.getWidth() - getRight();
                        // Snap only when close to an edge, otherwise keep where the user dropped it.
                        if (Math.abs(target - getTranslationX()) < parent.getWidth() * 0.25f) {
                            animate().translationX(target).setDuration(220).start();
                        }
                    }
                    if (listener != null) listener.onCharacterMoved(getTranslationX(), getTranslationY());
                } else if (!longPressFired) {
                    CharacterAnimator.TouchRegion region;
                    String r3 = vrm != null && vrmReady ? vrm.regionAt(e.getX(), e.getY()) : null;
                    if (r3 != null) {
                        region = CharacterAnimator.TouchRegion.valueOf(r3);
                    } else {
                        float[] m = toModel(e.getX(), e.getY());
                        region = PuppetRenderer.regionAt(m[0], m[1]);
                    }
                    animator.touch(region);
                    performClick();
                    if (listener != null) listener.onCharacterTapped(region);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
