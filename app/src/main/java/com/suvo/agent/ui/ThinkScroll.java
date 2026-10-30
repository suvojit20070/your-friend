package com.suvo.agent.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * Your Friend — Original project by Suvo.
 *
 * The scrolling part of the chat's thinking card: as tall as its text up to a fixed maximum
 * (about 200 dp), then it scrolls inside, so a long reasoning never takes over the conversation.
 * While a finger is on it and it can still scroll, the chat behind it does not move.
 */
public final class ThinkScroll extends ScrollView {

    private int maxHeight;

    public ThinkScroll(Context c) {
        this(c, null);
    }

    public ThinkScroll(Context c, AttributeSet a) {
        super(c, a);
        maxHeight = Math.round(200 * c.getResources().getDisplayMetrics().density);
    }

    public void setMaxHeight(int px) {
        maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int limit = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(maxHeight, size);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1))) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1))) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.onTouchEvent(ev);
    }

    /** At (or near) the end: new streamed text keeps it pinned to the bottom. */
    public boolean atBottom() {
        return !canScrollVertically(1) || getScrollY() + getHeight() >= getChildAt(0).getHeight() - 24;
    }
}
