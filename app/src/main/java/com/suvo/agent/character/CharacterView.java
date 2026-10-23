package com.suvo.agent.character;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.suvo.agent.core.character.Appearance;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.core.character.PuppetRenderer;

/**
 * The animated companion. Renders the procedural rig every frame while visible, reacts to touch
 * (tap: reaction by region; long-press then drag: move inside the parent, snapping to an edge), and
 * exposes activity/expression/lip-sync inputs. Respects reduced motion and the quality setting.
 */
public class CharacterView extends View implements Choreographer.FrameCallback {

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
        invalidate();
    }

    /** quality: "low" (30 fps, flat fills), "medium" (30 fps), "high" (60 fps). */
    public void setQuality(String quality, boolean reducedMotion) {
        gfx.low = "low".equals(quality);
        frameSkip = "high".equals(quality) ? 0 : 1;
        animator.reducedMotion = reducedMotion;
    }

    public void setActivity(CharacterAnimator.Activity a) {
        animator.setActivity(a);
    }

    public void setMouth(float open, float form) {
        animator.setMouthInput(open, form);
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
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
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (lastFrameNanos != 0) {
            float dt = (frameTimeNanos - lastFrameNanos) / 1e9f;
            animator.update(dt);
        }
        lastFrameNanos = frameTimeNanos;
        if (frameSkip == 0 || (frameCounter++ % (frameSkip + 1)) == 0) invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (renderer == null) return;
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
                    float[] m = toModel(e.getX(), e.getY());
                    CharacterAnimator.TouchRegion region = PuppetRenderer.regionAt(m[0], m[1]);
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
