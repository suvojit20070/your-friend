package com.suvo.agent.ui;

import android.app.Activity;
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
 * step in (log in again, solve a captcha). Resize toggles a small and a large panel; close hides it
 * (the next website step shows it again).
 */
final class AgentBrowserPanel implements WebSession.Host {

    private final Activity a;
    private final View panel;
    private final FrameLayout slot;
    private final TextView title;
    private boolean large;

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
            applySize();
        });
        a.findViewById(R.id.chat_browser_close).setOnClickListener(v -> WebSession.userClosed());
    }

    private void applySize() {
        View parent = (View) panel.getParent();
        int h = parent == null || parent.getHeight() == 0 ? Ui.dp(a, 320) : parent.getHeight();
        ViewGroup.LayoutParams lp = slot.getLayoutParams();
        lp.height = Math.round(h * (large ? 0.72f : 0.42f));
        slot.setLayoutParams(lp);
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
            panel.setTranslationY(-Ui.dp(a, 12));
            panel.animate().alpha(1f).translationY(0f).setDuration(200).start();
        }
    }

    @Override
    public void hideBrowser() {
        panel.setVisibility(View.GONE);
    }
}
