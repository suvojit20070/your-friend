package com.suvo.agent.ui.widgets;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.loadingindicator.LoadingIndicator;

import android.view.animation.OvershootInterpolator;

import com.suvo.agent.R;

/* powered by Suvo @nice_osei */

public class FasterPullToRefreshLayout extends RelativeLayout {
	
	public interface OnRefreshListener { void onRefresh(); }
	
	private static final int DEFAULT_TRIGGER_DP = 96;
	private static final int DEFAULT_CONTENT_MAX_DP = 200;
	private static final float DEFAULT_CONTENT_TIGHTNESS = 0.5f;
	private static final float DEFAULT_INDICATOR_TIGHTNESS = 0.1f;
	private static final int DEFAULT_INDICATOR_GROUP_DP = 24;
	
	private static final int RELEASE_STOP_DURATION = 350;
	private static final int FADE_OUT_DURATION_MS = 220;
	private static final long HARDWARE_LAYER_DELAY_MS = 100;
	
	private int triggerDistancePx;
	
	private int contentDownMaxDistancePx;
	private float contentDownTightness;
	private float indicatorDownTightness;
	private int indicatorGroupMarginPx;
	
	private View content;
	private LoadingIndicator loadingIndicator;
	private View customIndicatorContainer;
	private MaterialCardView customCard;
	private AppCompatImageView customVector;
	private ValueAnimator rotationAnimator;
	
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private float initialY;
	private int touchSlop;
	private boolean isRefreshing;
	private float mCurrentDragOffset;
	private float indicatorScale = 1.0f;
	
	private OnRefreshListener onRefreshListener;
	private final DecelerateInterpolator mDecelerate = new DecelerateInterpolator(2f);
	private final OvershootInterpolator mOvershoot = new OvershootInterpolator(1.5f);
	
	private float lastOffsetForRotation = 0f;
	private float currentRotation = 0f;
	private ValueAnimator rotationDecayAnimator;
	private ValueAnimator resetAnimator;
	
	public FasterPullToRefreshLayout(@NonNull Context context) {
		this(context, null);
	}
	
	public FasterPullToRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}
	
	public FasterPullToRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context, attrs);
	}
	
	private void init(Context ctx, AttributeSet attrs) {
		touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		float density = getResources().getDisplayMetrics().density;
		
		triggerDistancePx = (int) (DEFAULT_TRIGGER_DP * density + 0.5f);  
		contentDownMaxDistancePx = (int) (DEFAULT_CONTENT_MAX_DP * density + 0.5f);  
		indicatorGroupMarginPx = (int) (DEFAULT_INDICATOR_GROUP_DP * density + 0.5f);  
		contentDownTightness = DEFAULT_CONTENT_TIGHTNESS;  
		indicatorDownTightness = DEFAULT_INDICATOR_TIGHTNESS;  
		
		if (attrs != null) {  
			TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.PullToRefreshLayout);  
			
			triggerDistancePx = a.getDimensionPixelSize(R.styleable.PullToRefreshLayout_ptr_triggerDistance, triggerDistancePx);  
			contentDownMaxDistancePx = a.getDimensionPixelSize(R.styleable.PullToRefreshLayout_ptr_contentDownMaxDistance, contentDownMaxDistancePx);  
			contentDownTightness = a.getFloat(R.styleable.PullToRefreshLayout_ptr_contentDownTightness, contentDownTightness);  
			indicatorDownTightness = a.getFloat(R.styleable.PullToRefreshLayout_ptr_indicatorDownTightness, indicatorDownTightness);  
			indicatorGroupMarginPx = a.getDimensionPixelSize(R.styleable.PullToRefreshLayout_ptr_indicatorGroupMargin, indicatorGroupMarginPx);  
			
			a.recycle();  
		}  
		
		setClipToPadding(false);  
		setClipChildren(false);
		
	}
	
	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		
		View indicatorWrapper = LayoutInflater.from(getContext()).inflate(R.layout.pull_indicator, this, false);  
		ViewGroup realContainer = (ViewGroup) indicatorWrapper;  
		loadingIndicator = realContainer.findViewById(R.id.loading_indicator);  
		
		View customWrapper = LayoutInflater.from(getContext()).inflate(R.layout.custom_pull_indicator, this, false);  
		customIndicatorContainer = customWrapper;  
		customCard = customWrapper.findViewById(R.id.custom_card);  
		customVector = customWrapper.findViewById(R.id.custom_vector);  
		
		LayoutParams lpReal = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);  
		lpReal.addRule(CENTER_HORIZONTAL);  
		lpReal.addRule(ALIGN_PARENT_TOP);  
		lpReal.topMargin = indicatorGroupMarginPx;  
		realContainer.setLayoutParams(lpReal);  
		
		LayoutParams lpCustom = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);  
		lpCustom.addRule(CENTER_HORIZONTAL);  
		lpCustom.addRule(ALIGN_PARENT_TOP);  
		lpCustom.topMargin = indicatorGroupMarginPx;  
		customWrapper.setLayoutParams(lpCustom);  
		
		addView(customWrapper);  
		addView(realContainer);  
		
		applyAppColors();
		
		customIndicatorContainer.getViewTreeObserver().addOnGlobalLayoutListener(() -> {  
			customIndicatorContainer.setPivotX(customIndicatorContainer.getWidth() / 2f);  
			customIndicatorContainer.setPivotY(customIndicatorContainer.getHeight() / 2f);  
		});  
		
		if (loadingIndicator != null) {  
			loadingIndicator.setAlpha(0f);  
			loadingIndicator.setVisibility(View.GONE);  
		}  
		if (customIndicatorContainer != null) {  
			customIndicatorContainer.setVisibility(View.GONE);  
			customIndicatorContainer.setAlpha(0f);  
		}  
		
		for (int i = 0; i < getChildCount(); i++) {  
			View v = getChildAt(i);  
			if (v != realContainer && v != customWrapper) {  
				content = v;  
				break;  
			}  
		}  
		
		if(content != null) {  
			content.bringToFront();  
			customWrapper.bringToFront();  
			realContainer.bringToFront();  
		}
		
	}
	
	@Override
	public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isPtrEnabled) return false;
        
		if (isRefreshing || canChildScrollUp()) return false;
		switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
			initialY = ev.getY();
			mCurrentDragOffset = 0;
			lastOffsetForRotation = 0f;
			if (resetAnimator != null && resetAnimator.isRunning()) {
				resetAnimator.cancel();
			}
			break;
			case MotionEvent.ACTION_MOVE:
			float dy = ev.getY() - initialY;
			if (dy > touchSlop) return true;
			break;
		}
		return false;
	}
	
	private boolean isPtrEnabled = true;
	
	public void setPtrEnabled(boolean enabled) {
		this.isPtrEnabled = enabled;
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (!isPtrEnabled || isRefreshing) return false;
		
		switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_MOVE: {
				float dy = ev.getY() - initialY;
				float drag = Math.max(0, dy);
				
				float calculatedOffset = drag * contentDownTightness;  
				
				if (calculatedOffset > contentDownMaxDistancePx) {  
					float extra = calculatedOffset - contentDownMaxDistancePx;  
					float dampedExtra = (float) (contentDownMaxDistancePx * (1 - Math.exp(-extra * 0.3f / contentDownMaxDistancePx)));  
					mCurrentDragOffset = contentDownMaxDistancePx + dampedExtra;  
				} else {  
					mCurrentDragOffset = calculatedOffset;  
				}  
				
				if (customIndicatorContainer.getVisibility() != View.VISIBLE) {  
					customIndicatorContainer.setScaleX(0.4f);  
					customIndicatorContainer.setScaleY(0.4f);  
					customIndicatorContainer.animate()  
					.scaleX(1f)  
					.scaleY(1f)  
					.setDuration(250)  
					.setInterpolator(new OvershootInterpolator(1.8f))  
					.start();  
				}  
				
				indicatorScale = 1f;  
				
				if (customVector != null) {  
					float maxRotationFactor = 1.5f;  
					float minRotationFactor = 1.4f;  
					
					float proportion = Math.min(mCurrentDragOffset / triggerDistancePx, 1f);  
					
					float factor = maxRotationFactor + (minRotationFactor - maxRotationFactor) * proportion;  
					
					float delta = mCurrentDragOffset - lastOffsetForRotation;  
					currentRotation += delta * factor;  
					lastOffsetForRotation = mCurrentDragOffset;  
					
					customVector.setRotation(currentRotation);  
				}  
				
				if (content != null) {  
					content.setTranslationY(mCurrentDragOffset);  
				}  
				
				if (customIndicatorContainer != null) {  
					customIndicatorContainer.setVisibility(View.VISIBLE);  
					float alpha = Math.min(1f, drag / (triggerDistancePx * 0.5f));  
					customIndicatorContainer.setAlpha(alpha);  
					
					customIndicatorContainer.setScaleX(indicatorScale);  
					customIndicatorContainer.setScaleY(indicatorScale);  
					
					float indicatorMove = drag * indicatorDownTightness;  
					float indicatorCap = triggerDistancePx;  
					
					customIndicatorContainer.setTranslationY(Math.min(indicatorMove, indicatorCap));  
				}  
				
				return true;  
			}  
			
			case MotionEvent.ACTION_UP:  
			case MotionEvent.ACTION_CANCEL: {  
				startRotationDecay();  
				
				if (mCurrentDragOffset >= triggerDistancePx) {  
					animateReleaseStop();  
				} else {  
					animateResetToStart();  
				}  
				return true;  
			}  
		}  
		return super.onTouchEvent(ev);
		
	}
	
	private void startRotationDecay() {
		if (customVector == null) return;
		if (rotationDecayAnimator != null && rotationDecayAnimator.isRunning()) rotationDecayAnimator.cancel();
		
		rotationDecayAnimator = ValueAnimator.ofFloat(currentRotation, currentRotation * 0.18f);  
		rotationDecayAnimator.setDuration(520);  
		rotationDecayAnimator.setInterpolator(new DecelerateInterpolator());  
		rotationDecayAnimator.addUpdateListener(a -> {  
			float v = (float) a.getAnimatedValue();  
			currentRotation = v;  
			customVector.setRotation(v);  
		});  
		rotationDecayAnimator.start();
		
	}
	
	private void activateRealIndicator() {
		if (loadingIndicator == null || customIndicatorContainer == null) return;
		
		customIndicatorContainer.setVisibility(View.GONE);  
		customIndicatorContainer.setAlpha(0f);  
		customIndicatorContainer.setLayerType(View.LAYER_TYPE_NONE, null);  
		
		loadingIndicator.setVisibility(View.VISIBLE);  
		loadingIndicator.setAlpha(1f);  
		loadingIndicator.setScaleX(1f);  
		loadingIndicator.setScaleY(1f);  
		loadingIndicator.setLayerType(View.LAYER_TYPE_HARDWARE, null);  
		
		if (rotationAnimator != null && rotationAnimator.isRunning()) rotationAnimator.cancel();  
		rotationAnimator = ValueAnimator.ofFloat(0f, 360f);  
		rotationAnimator.setDuration(1200);  
		rotationAnimator.setInterpolator(new DecelerateInterpolator(0.9f));  
		rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);  
		rotationAnimator.addUpdateListener(a -> loadingIndicator.setRotation((float) a.getAnimatedValue()));  
		rotationAnimator.start();
		
	}
	
	private void animateReleaseStop() {
		isRefreshing = true;
		
		if (content != null) {  
			content.animate()
			.translationY(0f)
			.setDuration(260)
			.setInterpolator(new OvershootInterpolator(1.6f))
			.start();
		}  
		
		if (customIndicatorContainer != null) {  
			customIndicatorContainer.setVisibility(View.VISIBLE);  
			customIndicatorContainer.setAlpha(1f);  
			
			customIndicatorContainer.animate()  
			.translationY(-12f)  
			.setDuration(120)  
			.withEndAction(() -> customIndicatorContainer.animate()  
			.translationY(0f)  
			.setDuration(140)  
			.withEndAction(this::activateRealIndicator)  
			.start())  
			.start();  
		} else {  
			activateRealIndicator();  
		}  
		
		if (onRefreshListener != null) onRefreshListener.onRefresh();
	}
	
	public void setRefreshing(boolean refreshing) {
        
		if (!refreshing && isRefreshing) {
			isRefreshing = false;
			fadeOutIndicatorInPlace();
			animateContentBack();
			
		} else if (refreshing && !isRefreshing) {  
			isRefreshing = true;  
			
			if (customIndicatorContainer != null) {  
				customIndicatorContainer.setVisibility(View.GONE);  
				customIndicatorContainer.setAlpha(0f);  
				customIndicatorContainer.setScaleX(0.4f);  
				customIndicatorContainer.setScaleY(0.4f);  
				customIndicatorContainer.setTranslationY(0f);  
			}  
			
			if (loadingIndicator != null) {  
				loadingIndicator.setVisibility(View.VISIBLE);  
				loadingIndicator.setAlpha(0f);  
				loadingIndicator.setScaleX(0.4f);  
				loadingIndicator.setScaleY(0.4f);  
				
				loadingIndicator.animate()  
				.alpha(1f)  
				.scaleX(1f)  
				.scaleY(1f)  
				.setDuration(350)  
				.setInterpolator(new OvershootInterpolator(1.8f))  
				.withEndAction(() -> {  
					activateRealIndicator();  
				})  
				.start();  
			} else {  
				activateRealIndicator();  
			}  
			
			if (content != null) {  
				content.animate()  
				.translationY(0f)  
				.setDuration(280)  
				.setInterpolator(mOvershoot)  
				.start();  
			}  
		}
		
	}
	
	private void fadeOutIndicatorInPlace() {
		if (loadingIndicator == null) return;
		
		loadingIndicator.setLayerType(View.LAYER_TYPE_HARDWARE, null);  
		loadingIndicator.animate()  
		.alpha(0f)  
		.scaleX(1f)  
		.scaleY(1f)  
		.setDuration(FADE_OUT_DURATION_MS)  
		.setInterpolator(new DecelerateInterpolator(1.4f))  
		.withEndAction(() -> mHandler.postDelayed(() -> {  
			if (loadingIndicator != null) {  
				loadingIndicator.setVisibility(View.GONE);  
				loadingIndicator.setAlpha(0f);  
				loadingIndicator.setLayerType(View.LAYER_TYPE_NONE, null);  
			}  
			if (customIndicatorContainer != null) {  
				customIndicatorContainer.setVisibility(View.GONE);  
				customIndicatorContainer.setAlpha(0f);  
				customIndicatorContainer.setTranslationY(0f);  
			}  
			currentRotation = 0f;  
		}, HARDWARE_LAYER_DELAY_MS))  
		.start();
		
	}
	
	private void animateContentBack() {
		if (content == null) return;
		
		float startTranslation = content.getTranslationY();  
		if (startTranslation == 0f) return;  
		
		content.animate()
		.translationY(0f)
		.setDuration(260)
		.setInterpolator(new OvershootInterpolator(1.6f))
		.start();
		
	}
	
	private void resetToStart() {
		mCurrentDragOffset = 0;
		currentRotation = 0f;
		if (customIndicatorContainer != null) {
			customIndicatorContainer.setScaleX(0.4f);
			customIndicatorContainer.setScaleY(0.4f);
			customIndicatorContainer.setAlpha(0f);
			customIndicatorContainer.setTranslationY(0f);
			customIndicatorContainer.setVisibility(View.GONE);
		}
		
		if (content != null) {  
			content.setTranslationY(0f);  
			content.setScaleY(1f);  
		}
		
	}
	
	private void animateResetToStart() {
		if (resetAnimator != null && resetAnimator.isRunning()) {
			resetAnimator.cancel();
		}
		
		final float initialContentTranslation = (content != null) ? content.getTranslationY() : 0f;  
		final float initialScale = (customIndicatorContainer != null) ? customIndicatorContainer.getScaleX() : 0.4f;  
		final float initialAlpha = (customIndicatorContainer != null) ? customIndicatorContainer.getAlpha() : 0f;  
		final float initialIndicatorTranslationY = (customIndicatorContainer != null) ? customIndicatorContainer.getTranslationY() : 0f;  
		
		final float targetScale = 0.0f;  
		
		resetAnimator = ValueAnimator.ofFloat(1f, 0f);  
		resetAnimator.setDuration(RELEASE_STOP_DURATION);  
		resetAnimator.setInterpolator(mDecelerate);  
		
		resetAnimator.addUpdateListener(animation -> {  
			float t = (float) animation.getAnimatedValue();  
			
			if (content != null) {  
				content.setTranslationY(initialContentTranslation * t);  
			}  
			
			if (customIndicatorContainer != null) {  
				customIndicatorContainer.setAlpha(initialAlpha * t);  
				
				float currentScale = initialScale * t;  
				customIndicatorContainer.setScaleX(currentScale);  
				customIndicatorContainer.setScaleY(currentScale);  
				
				customIndicatorContainer.setTranslationY(initialIndicatorTranslationY * t);  
			}  
		});  
		
		resetAnimator.addListener(new AnimatorListenerAdapter() {  
			@Override  
			public void onAnimationEnd(android.animation.Animator animation) {  
				resetToStart();  
			}  
		});  
		
		resetAnimator.start();
		
	}
	
	/**
	 * Your Friend: the app's colours come from its own palette (seed accent, light / dark / dim),
	 * not from the Material theme attributes, so the indicator is tinted with them here.
	 */
	private void applyAppColors() {
		try {
			com.suvo.agent.ui.Palette p = com.suvo.agent.ui.Palette.of(getContext());
			if (customCard != null) customCard.setCardBackgroundColor(p.primaryContainer);
			if (customVector != null) customVector.setColorFilter(p.primary, android.graphics.PorterDuff.Mode.SRC_IN);
			if (loadingIndicator != null) {
				loadingIndicator.setIndicatorColor(new int[]{p.onPrimaryContainer});
				loadingIndicator.setContainerColor(p.primaryContainer);
			}
		} catch (RuntimeException ignored) {
		}
	}
	
	/** Your Friend: under a floating top bar, the indicator starts below it. */
	public void setIndicatorTopOffset(int px) {
		int top = px + indicatorGroupMarginPx;
		for (View v : new View[]{customIndicatorContainer, loadingIndicator == null ? null : (View) loadingIndicator.getParent()}) {
			if (v == null || !(v.getLayoutParams() instanceof LayoutParams)) continue;
			LayoutParams lp = (LayoutParams) v.getLayoutParams();
			if (lp.topMargin == top) continue;
			lp.topMargin = top;
			v.setLayoutParams(lp);
		}
	}
	
	/** Your Friend: for content that scrolls inside (a web page in a frame), the screen says whether it can scroll up. */
	public interface ChildScrollCheck { boolean canScrollUp(); }
	
	private ChildScrollCheck scrollCheck;
	
	public void setChildScrollCheck(ChildScrollCheck check) {
		this.scrollCheck = check;
	}
	
	private boolean canChildScrollUp() {
		if (scrollCheck != null) return scrollCheck.canScrollUp();
		return content != null && content.canScrollVertically(-1);
	}
	
	private int dpToPx(int dp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
	}
	
	public void setOnRefreshListener(OnRefreshListener listener) {
		this.onRefreshListener = listener;
	}
	
	public boolean isRefreshing() {
		return isRefreshing;
	}
}
