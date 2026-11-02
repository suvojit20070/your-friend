package com.suvo.agent.ui;

import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Your Friend — Original project by Suvo.
 *
 * Spring press for small chat buttons: the view swells, follows the finger a little (stretching
 * toward it) and springs back on release. Used by the buttons the AI puts in a chat answer.
 */
public final class PressEffect {
    
    public static final float SCALE = 1.30f;
    public static final float FOLLOW = 0.35f;
    public static final float MAX_SHIFT_DP = 8f;
    public static final float PRESS_DAMPING = 0.7f;
    public static final float PRESS_STIFFNESS = 800f;
    public static final float RELEASE_DAMPING = 0.4f;
    public static final float RELEASE_STIFFNESS = 400f;
    public static final float RELEASE_DISTANCE_DP = 800f;
    public static final float STRETCH = 0.28f;
    public static final float SQUASH = 0.10f;
    
    private PressEffect() {}
    
    public static final class Options {
        public float scale = SCALE;
        public float follow = FOLLOW;
        public float maxShiftDp = MAX_SHIFT_DP;
        public float pressDamping = PRESS_DAMPING;
        public float pressStiffness = PRESS_STIFFNESS;
        public float releaseDamping = RELEASE_DAMPING;
        public float releaseStiffness = RELEASE_STIFFNESS;
        public float releaseDistanceDp = RELEASE_DISTANCE_DP;
        public float stretch = STRETCH;
        public float squash = SQUASH;
        
        public Options() {}
        
        public Options scale(float value) {
            scale = value;
            return this;
        }
        
        public Options follow(float value) {
            follow = value;
            return this;
        }
        
        public Options maxShift(float value) {
            maxShiftDp = value;
            return this;
        }
        
        public Options pressSpring(float damping, float stiffness) {
            pressDamping = damping;
            pressStiffness = stiffness;
            return this;
        }
        
        public Options releaseSpring(float damping, float stiffness) {
            releaseDamping = damping;
            releaseStiffness = stiffness;
            return this;
        }
        
        public Options releaseDistance(float value) {
            releaseDistanceDp = value;
            return this;
        }
        
        public Options stretch(float value) {
            stretch = value;
            return this;
        }
        
        public Options squash(float value) {
            squash = value;
            return this;
        }
    }
    
    public static void setEffect(View view) {
        setEffect(new Options(), view);
    }
    
    public static void setEffect(Options options, View view) {
        if (view == null) return;
        if (options == null) options = new Options();
        view.setOnTouchListener(new Effect(view, options));
    }
    
    public static void setEffect(View view, float scale) {
        setEffect(new Options().scale(scale), view);
    }
    
    public static void setEffect(View view, float scale, float follow) {
        setEffect(new Options().scale(scale).follow(follow), view);
    }
    
    public static void removeEffect(View view) {
        if (view == null) return;
        view.setOnTouchListener(null);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
    }
    
    private static final class Effect implements View.OnTouchListener {
        private final View view;
        private final SpringAnimation sx, sy, tx, ty;
        private final float scale, follow, maxShift, slop, stretch, squash;
        private final float pressDamping, pressStiffness, releaseDamping, releaseStiffness;
        private float baseCx, baseCy;
        private boolean pressed;
        
        Effect(View v, Options o) {
            float d = v.getResources().getDisplayMetrics().density;
            
            view = v;
            scale = o.scale;
            follow = o.follow;
            maxShift = o.maxShiftDp * d;
            slop = o.releaseDistanceDp * d;
            stretch = o.stretch;
            squash = o.squash;
            pressDamping = o.pressDamping;
            pressStiffness = o.pressStiffness;
            releaseDamping = o.releaseDamping;
            releaseStiffness = o.releaseStiffness;
            
            sx = spring(v, DynamicAnimation.SCALE_X, 1f, pressDamping, pressStiffness);
            sy = spring(v, DynamicAnimation.SCALE_Y, 1f, pressDamping, pressStiffness);
            tx = spring(v, DynamicAnimation.TRANSLATION_X, 0f, pressDamping, pressStiffness);
            ty = spring(v, DynamicAnimation.TRANSLATION_Y, 0f, pressDamping, pressStiffness);
        }
        
        private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty property, float rest, float damping, float stiffness) {
            SpringForce force = new SpringForce(rest);
            force.setDampingRatio(damping);
            force.setStiffness(stiffness);
            SpringAnimation animation = new SpringAnimation(v, property);
            animation.setSpring(force);
            return animation;
        }
        
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            boolean consume = !v.isClickable() && !v.isLongClickable();
            
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    pressed = true;
                    
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    baseCx = loc[0] + v.getWidth() * v.getScaleX() / 2f - v.getTranslationX();
                    baseCy = loc[1] + v.getHeight() * v.getScaleY() / 2f - v.getTranslationY();
                    
                    setSpring(pressDamping, pressStiffness);
                    sx.animateToFinalPosition(scale);
                    sy.animateToFinalPosition(scale);
                    follow(e);
                    return consume;
                }
                
                case MotionEvent.ACTION_MOVE:
                if (!pressed) return consume;
                
                if (!isInside(v, e)) {
                    pressed = false;
                    release();
                } else {
                    follow(e);
                }
                return consume;
                
                case MotionEvent.ACTION_UP:
                pressed = false;
                release();
                return consume;
                
                case MotionEvent.ACTION_CANCEL:
                pressed = false;
                release();
                return consume;
            }
            
            return consume;
        }
        
        private boolean isInside(View v, MotionEvent e) {
            return e.getX() >= -slop && e.getX() <= v.getWidth() + slop && e.getY() >= -slop && e.getY() <= v.getHeight() + slop;
        }
        
        private void follow(MotionEvent e) {
            float dx = (e.getRawX() - baseCx) * follow;
            float dy = (e.getRawY() - baseCy) * follow;
            float nx = maxShift > 0f ? Math.min(1f, Math.abs(dx) / maxShift) : 0f;
            float ny = maxShift > 0f ? Math.min(1f, Math.abs(dy) / maxShift) : 0f;
            float stretchX = scale + nx * stretch - ny * squash;
            float stretchY = scale + ny * stretch - nx * squash;
            
            tx.animateToFinalPosition(clamp(dx));
            ty.animateToFinalPosition(clamp(dy));
            sx.animateToFinalPosition(stretchX);
            sy.animateToFinalPosition(stretchY);
        }
        
        private void release() {
            setSpring(releaseDamping, releaseStiffness);
            sx.animateToFinalPosition(1f);
            sy.animateToFinalPosition(1f);
            tx.animateToFinalPosition(0f);
            ty.animateToFinalPosition(0f);
        }
        
        private float clamp(float value) {
            return Math.max(-maxShift, Math.min(maxShift, value));
        }
        
        private void setSpring(float damping, float stiffness) {
            SpringAnimation[] animations = {sx, sy, tx, ty};
            for (SpringAnimation animation : animations) {
                animation.getSpring().setDampingRatio(damping);
                animation.getSpring().setStiffness(stiffness);
            }
        }
    }
}
