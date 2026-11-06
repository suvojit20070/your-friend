package com.suvo.agent.connectors;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;

/**
 * Your Friend — Original project by Suvo.
 *
 * The WebView of one agent browser tab. It always reports itself as shown, focused and visible,
 * so pages keep running (timers, scripts, network, rendering) while the browser is moved between
 * the floating window, the browser screen and the chat, while it is hidden, and while the screen
 * is off during a task (the task's foreground service keeps the CPU awake).
 */
public class AgentWebView extends WebView {

    /** Tab number (stable while the tab lives). */
    public final int tabId;
    /** Which account / mode opened it ("browser" or a connector name). */
    public String owner = "browser";
    public int progress = 100;
    /** Shows pages as on a computer (user agent, client hints, desktop-width layout). */
    public boolean desktop;

    public AgentWebView(Context context, int tabId) {
        super(context);
        this.tabId = tabId;
    }

    // Always "on screen" for the page.
    @Override
    public boolean isShown() {
        return true;
    }

    @Override
    public boolean hasWindowFocus() {
        return true;
    }

    @Override
    public int getVisibility() {
        return View.VISIBLE;
    }

    // The system may not switch the page to hidden.
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(View.VISIBLE);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, View.VISIBLE);
    }

    /** Moves this tab into another container without the page noticing (timers paused around it). */
    public final void changeGroup(ViewGroup group, ViewGroup.LayoutParams params) {
        pauseTimers();
        ViewParent parent = getParent();
        ViewGroup old = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        if (old != null) old.removeView(this);
        if (group != null) {
            if (params != null) group.addView(this, params);
            else group.addView(this);
        }
        resumeTimers();
    }
}
