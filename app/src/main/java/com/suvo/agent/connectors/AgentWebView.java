package com.suvo.agent.connectors;

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
    /** The agent's choice for this tab (null = the site / setting decides): true desktop, false mobile. */
    public Boolean modeOverride;
    /** The network recorder runs at document start (else it is added when each page starts). */
    public boolean hookedAtStart;

    /** A JavaScript alert / confirm / prompt / leave-page question the page is waiting on (or null). */
    public volatile PageDialog dialog;
    /** Files the agent armed for the page's next file picker (and until when they are valid). */
    public volatile android.net.Uri[] uploadFiles;
    public volatile long uploadUntil;
    /** Set when the page's file picker was answered with the armed files. */
    public volatile boolean uploadUsed;

    /** What happened in the page that the agent should know (file pickers, dialogs, downloads, new tabs, permissions). */
    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();

    public void event(String what) {
        synchronized (events) {
            events.add(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + " " + what);
            while (events.size() > 40) events.removeFirst();
        }
    }

    /** The events since the last call (and forgets them). */
    public java.util.List<String> takeEvents() {
        synchronized (events) {
            java.util.List<String> out = new java.util.ArrayList<>(events);
            events.clear();
            return out;
        }
    }

    /** A dialog the page opened; answered by the agent (browser action dialog) or the user. */
    public static final class PageDialog {
        public final String type, message, defaultValue;
        final android.webkit.JsResult result;
        public final long at = System.currentTimeMillis();
        private boolean done;

        PageDialog(String type, String message, String defaultValue, android.webkit.JsResult result) {
            this.type = type;
            this.message = message == null ? "" : message;
            this.defaultValue = defaultValue;
            this.result = result;
        }

        /** accept (OK / leave / with text for a prompt) or dismiss (Cancel). True when it was still open. */
        public synchronized boolean answer(boolean accept, String text) {
            if (done) return false;
            done = true;
            if (!accept) result.cancel();
            else if (result instanceof android.webkit.JsPromptResult) ((android.webkit.JsPromptResult) result).confirm(text == null ? (defaultValue == null ? "" : defaultValue) : text);
            else result.confirm();
            return true;
        }

        public synchronized boolean open() {
            return !done;
        }
    }

    /**
     * The tab's context: an Activity's while that screen lives (popups need it), then the
     * application's (WebSession switches it; the page does not notice).
     */
    public final android.content.MutableContextWrapper context;

    public AgentWebView(android.content.MutableContextWrapper context, int tabId) {
        super(context);
        this.context = context;
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
