package com.suvo.agent.ui;

import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Your Friend — Original project by Suvo.
 *
 * Zafiro-style message box. While the message is one line it is one row:
 * <pre>[start buttons]  text…  [end buttons]</pre>
 * As soon as it needs a second line the text takes the full width above, and the buttons stay
 * where they are, in a row under it; back to one row when it fits again.
 *
 * The buttons never move (they sit at the bottom in both shapes) and nothing is re-parented
 * after setup: only the text box's margins animate (200 ms). So nothing blinks or jumps, and
 * the keyboard and cursor are not disturbed.
 */
final class ExpandingComposer {

    private final EditText input;
    private final LinearLayout startBox, endBox;
    private final int gap;
    private boolean expanded;
    /** Text width on one line (to know when the text fits there again). */
    private int oneLineWidth;
    private ValueAnimator anim;
    /** Current margins: start, end, bottom. */
    private float ms, me, mb;

    private ExpandingComposer(EditText input, LinearLayout startBox, LinearLayout endBox, int gap) {
        this.input = input;
        this.startBox = startBox;
        this.endBox = endBox;
        this.gap = gap;
    }

    /**
     * Rebuilds {@code composer}'s content as: buttons pinned bottom-start / bottom-end, the input
     * between them. Call once, before the input has focus.
     */
    static ExpandingComposer install(LinearLayout composer, EditText input, View[] start, View[] end) {
        int gap = Ui.dp(composer.getContext(), 6);
        FrameLayout frame = new FrameLayout(composer.getContext());
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        LinearLayout startBox = row(composer, start);
        LinearLayout endBox = row(composer, end);
        composer.removeAllViews();
        if (input.getParent() instanceof ViewGroup) ((ViewGroup) input.getParent()).removeView(input);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int h = 0;
        for (View v : start) h = Math.max(h, size(v.getLayoutParams() == null ? 0 : v.getLayoutParams().height));
        for (View v : end) h = Math.max(h, size(v.getLayoutParams() == null ? 0 : v.getLayoutParams().height));
        input.setMinHeight(h);
        input.setMinimumHeight(h);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        ExpandingComposer c = new ExpandingComposer(input, startBox, endBox, gap);
        c.ms = width(startBox);
        c.me = width(endBox);
        c.mb = 0;
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        frame.addView(input, ip);
        frame.addView(startBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        frame.addView(endBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        c.apply();
        input.addTextChangedListener(new ChatActivity.SimpleWatcher(() -> input.post(c::update)));
        return c;
    }

    /** The buttons of one side in a row, each keeping its size and own margins. */
    private static LinearLayout row(LinearLayout composer, View[] views) {
        LinearLayout box = new LinearLayout(composer.getContext());
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        for (View v : views) {
            ViewGroup.LayoutParams old = v.getLayoutParams();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(old != null ? old.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    old != null ? old.height : ViewGroup.LayoutParams.WRAP_CONTENT);
            if (old instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) old;
                lp.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin);
                lp.setMarginStart(m.getMarginStart());
                lp.setMarginEnd(m.getMarginEnd());
            }
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            box.addView(v, lp);
        }
        return box;
    }

    private static int size(int px) {
        return Math.max(0, px);
    }

    /** Width a row of buttons takes (sizes + own margins), known before layout. */
    private static int width(LinearLayout box) {
        int w = 0;
        for (int i = 0; i < box.getChildCount(); i++) {
            ViewGroup.LayoutParams p = box.getChildAt(i).getLayoutParams();
            w += Math.max(0, p.width);
            if (p instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) p;
                w += Math.max(m.getMarginStart(), m.leftMargin) + Math.max(m.getMarginEnd(), m.rightMargin);
            }
        }
        return w;
    }

    private int rowHeight() {
        int h = 0;
        for (LinearLayout b : new LinearLayout[]{startBox, endBox}) {
            for (int i = 0; i < b.getChildCount(); i++) h = Math.max(h, Math.max(0, b.getChildAt(i).getLayoutParams().height));
        }
        return h;
    }

    /** Back to one row (e.g. after sending). */
    void collapse() {
        if (expanded) animateTo(false);
    }

    private void update() {
        String t = input.getText().toString();
        boolean want;
        if (!expanded) {
            want = t.indexOf('\n') >= 0 || input.getLineCount() > 1;
        } else {
            // Back to one row only when it clearly fits there again (no flip-flopping at the edge).
            want = !t.isEmpty() && (t.indexOf('\n') >= 0 || input.getPaint().measureText(t) > oneLineWidth * 0.9f);
        }
        if (want != expanded) animateTo(want);
    }

    private void animateTo(boolean open) {
        if (open) oneLineWidth = Math.max(1, input.getWidth() - input.getTotalPaddingLeft() - input.getTotalPaddingRight());
        expanded = open;
        float s0 = ms, e0 = me, b0 = mb;
        float s1 = open ? 0 : width(startBox), e1 = open ? 0 : width(endBox), b1 = open ? rowHeight() + gap : 0;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(200);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            ms = s0 + (s1 - s0) * f;
            me = e0 + (e1 - e0) * f;
            mb = b0 + (b1 - b0) * f;
            apply();
        });
        anim.start();
    }

    private void apply() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) input.getLayoutParams();
        lp.setMarginStart(Math.round(ms));
        lp.setMarginEnd(Math.round(me));
        lp.leftMargin = Math.round(input.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? me : ms);
        lp.rightMargin = Math.round(input.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? ms : me);
        lp.bottomMargin = Math.round(mb);
        input.setLayoutParams(lp);
    }
}
