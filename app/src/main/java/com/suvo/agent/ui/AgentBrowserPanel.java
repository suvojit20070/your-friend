package com.suvo.agent.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.suvo.agent.R;
import com.suvo.agent.connectors.WebSession;

/**
 * Your Friend — Original project by Suvo.
 *
 * The agent's browser inside the chat: when the agent uses a logged-in website (web connectors),
 * the real page appears in a glass panel above the messages, so the user sees every step and can
 * step in (log in again, solve a captcha).
 * <ul>
 *   <li>drag the title bar to move the panel anywhere over the chat;</li>
 *   <li>drag the corner grip to resize it (width and height);</li>
 *   <li>the size button switches between a small and a large preset and puts it back in place;</li>
 *   <li>close hides it (the next website step shows it again, where the user left it).</li>
 * </ul>
 */
final class AgentBrowserPanel implements WebSession.Host {

    private final Activity a;
    private final View panel;
    private final FrameLayout slot;
    private final TextView title;
    private boolean large;
    /** Size chosen with the grip (px); 0 = use the preset. */
    private int userW, userH;

    AgentBrowserPanel(Activity a) {
        this.a = a;
        panel = a.findViewById(R.id.chat_browser);
        slot = a.findViewById(R.id.chat_browser_slot);
        title = a.findViewById(R.id.chat_browser_title);
        panel.setBackground(new GlassDrawable(a, GlassDrawable.Preset.POPOVER));
        panel.setTag(R.id.theme_skip, Boolean.TRUE);
        slot.setClipToOutline(true);
        slot.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(a, 16));
            }
        });
        ImageButton size = a.findViewById(R.id.chat_browser_size);
        size.setOnClickListener(v -> {
            large = !large;
            userW = userH = 0;
            panel.animate().translationX(0f).translationY(0f).setDuration(180).start();
            applySize();
        });
        a.findViewById(R.id.chat_browser_close).setOnClickListener(v -> WebSession.userClosed());
        View header = ((ViewGroup) panel).getChildAt(0);
        header.setOnTouchListener(new Mover());
        View grip = a.findViewById(R.id.chat_browser_grip);
        if (grip != null) grip.setOnTouchListener(new Resizer());
    }

    private int parentW() {
        View p = (View) panel.getParent();
        return p == null || p.getWidth() == 0 ? a.getResources().getDisplayMetrics().widthPixels : p.getWidth();
    }

    private int parentH() {
        View p = (View) panel.getParent();
        return p == null || p.getHeight() == 0 ? Ui.dp(a, 600) : p.getHeight();
    }

    private void applySize() {
        ViewGroup.LayoutParams plp = panel.getLayoutParams();
        ViewGroup.LayoutParams slp = slot.getLayoutParams();
        if (userW > 0 && userH > 0) {
            plp.width = userW;
            slp.height = userH;
        } else {
            plp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            slp.height = Math.round(parentH() * (large ? 0.72f : 0.42f));
        }
        panel.setLayoutParams(plp);
        slot.setLayoutParams(slp);
        panel.post(this::keepInside);
    }

    /** Keeps the moved panel fully over the chat after a size or screen change. */
    private void keepInside() {
        float minX = -panel.getLeft(), maxX = parentW() - panel.getRight();
        float minY = -panel.getTop(), maxY = parentH() - panel.getBottom();
        panel.setTranslationX(clamp(panel.getTranslationX(), Math.min(minX, 0), Math.max(maxX, 0)));
        panel.setTranslationY(clamp(panel.getTranslationY(), Math.min(minY, 0), Math.max(maxY, 0)));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Title bar: drag to move. */
    private final class Mover implements View.OnTouchListener {
        private float downX, downY, startTx, startTy;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startTx = panel.getTranslationX();
                    startTy = panel.getTranslationY();
                    panel.animate().cancel();
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float tx = startTx + e.getRawX() - downX, ty = startTy + e.getRawY() - downY;
                    float minX = -panel.getLeft(), maxX = parentW() - panel.getRight();
                    float minY = -panel.getTop(), maxY = parentH() - panel.getBottom();
                    panel.setTranslationX(clamp(tx, Math.min(minX, 0), Math.max(maxX, 0)));
                    panel.setTranslationY(clamp(ty, Math.min(minY, 0), Math.max(maxY, 0)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Corner grip: drag to resize (width grows to the right, height downwards). */
    private final class Resizer implements View.OnTouchListener {
        private float downX, downY;
        private int startW, startH;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startW = panel.getWidth();
                    startH = slot.getHeight();
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int minW = Ui.dp(a, 200), minH = Ui.dp(a, 140);
                    float left = panel.getLeft() + panel.getTranslationX();
                    float top = panel.getTop() + panel.getTranslationY();
                    int maxW = Math.max(minW, Math.round(parentW() - left - Ui.dp(a, 4)));
                    int header = panel.getHeight() - slot.getHeight();
                    int maxH = Math.max(minH, Math.round(parentH() - top - header - Ui.dp(a, 8)));
                    userW = Math.round(clamp(startW + e.getRawX() - downX, minW, maxW));
                    userH = Math.round(clamp(startH + e.getRawY() - downY, minH, maxH));
                    ViewGroup.LayoutParams plp = panel.getLayoutParams();
                    ViewGroup.LayoutParams slp = slot.getLayoutParams();
                    plp.width = userW;
                    slp.height = userH;
                    panel.setLayoutParams(plp);
                    slot.setLayoutParams(slp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        }
    }

    void start() {
        WebSession.setHost(this);
    }

    void stop() {
        WebSession.clearHost(this);
    }

    @Override
    public ViewGroup browserSlot() {
        return slot;
    }

    @Override
    public void showBrowser(String text) {
        title.setText(text);
        if (panel.getVisibility() != View.VISIBLE) {
            panel.setVisibility(View.VISIBLE);
            panel.post(this::applySize);
            panel.setAlpha(0f);
            panel.animate().alpha(1f).setDuration(200).start();
        }
    }

    @Override
    public void hideBrowser() {
        panel.setVisibility(View.GONE);
    }
}
