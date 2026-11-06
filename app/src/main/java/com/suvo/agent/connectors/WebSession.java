package com.suvo.agent.connectors;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Your Friend — Original project by Suvo.
 *
 * The logged-in websites of the web connectors (Instagram, Facebook, X, Telegram Web, WhatsApp
 * Web…), used by the agent. One WebView shares its cookies with the login screen, so it is the
 * user's own session.
 *
 * The browser is shown while the agent uses it: inside the chat (a panel above the messages) when
 * the chat is open, otherwise in a small floating window (with "display over other apps"). A page
 * that is really on screen behaves like in a normal browser — sites such as Telegram Web only draw
 * their messages for a visible page — and the user sees what the agent does (and can step in, e.g.
 * for a captcha).
 *
 * The agent opens pages, reads their text, taps elements (real touches at the element's place),
 * types (as a keyboard would), presses keys, scrolls, runs JavaScript and fetches with the site's
 * cookies. All calls block the calling (agent) thread; the WebView lives on the main thread.
 */
public final class WebSession {

    private WebSession() {
    }

    public static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0.0.0 Safari/537.36";
    private static final int W = 1080, H = 2200;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final SecureRandom RND = new SecureRandom();
    private static final Map<String, Object[]> pending = new ConcurrentHashMap<>();
    /** The open tabs (main thread); {@link #view} is the current one. Application context only. */
    @SuppressLint("StaticFieldLeak")
    private static AgentWebView view;
    private static final java.util.List<AgentWebView> tabs = new java.util.ArrayList<>();
    private static int nextTabId = 1;

    /** The browser screen redraws its tab strip / address / progress. */
    public interface TabsListener {
        void onTabsChanged();
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<TabsListener> tabListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addTabsListener(TabsListener l) {
        tabListeners.addIfAbsent(l);
    }

    public static void removeTabsListener(TabsListener l) {
        tabListeners.remove(l);
    }

    private static void tabsChanged() {
        for (TabsListener l : tabListeners) main.post(l::onTabsChanged);
    }
    private static volatile CountDownLatch loaded;
    private static volatile String lastError;
    private static volatile String currentName = "";

    // ------------------------------------------------------------------ where the browser shows

    /** A screen that can show the agent's browser (the chat). Main thread. */
    public interface Host {
        /** The place for the WebView. */
        ViewGroup browserSlot();

        /** Show the browser panel ({@code title}: account and address). */
        void showBrowser(String title);

        /** The browser left this host. */
        void hideBrowser();
    }

    private static Host host;
    @SuppressLint("StaticFieldLeak")
    private static FrameLayout overlay;
    private static boolean overlayClosed;

    /** The full browser screen (BrowserActivity): while it is open the browser shows there. */
    private static Host screen;

    public static void setScreen(Host h) {
        screen = h;
        rememberUi(h);
        if (view != null) attach(currentName.isEmpty() ? "browser" : currentName);
    }

    /**
     * The browser screen went to the background (home, another app). The page stays where it is,
     * inside that screen's window: only the window is hidden, which AgentWebView ignores, so music,
     * video and scripts keep running. Taking it out of the window here is what paused it.
     */
    public static void clearScreen(Host h) {
        if (screen != h) return;
        screen = null;
    }

    /** The browser screen is gone for good (closed): only now the page leaves its window. */
    public static void releaseScreen(Host h) {
        if (screen == h) screen = null;
        if (view != null && view.getParent() == h.browserSlot()) {
            h.browserSlot().removeView(view);
            layoutDetached();
        }
    }

    /** The newest screen that showed the browser: new tabs are made with its (Activity) context. */
    private static java.lang.ref.WeakReference<Context> uiContext;

    private static void rememberUi(Host h) {
        ViewGroup slot = h == null ? null : h.browserSlot();
        if (slot != null) uiContext = new java.lang.ref.WeakReference<>(slot.getContext());
    }

    /**
     * The context a tab is made with: an Activity (WebView popups — select lists, date pickers,
     * alerts — need one), kept for the tab's whole life; switching it later would pause the page.
     */
    private static Context tabContext(Context ctx) {
        if (ctx instanceof android.app.Activity && !((android.app.Activity) ctx).isFinishing()) return ctx;
        Context ui = uiContext == null ? null : uiContext.get();
        if (ui instanceof android.app.Activity && !((android.app.Activity) ui).isDestroyed()) return ui;
        return ctx.getApplicationContext();
    }

    /** The chat became visible: the browser shows there from now on. Main thread. */
    public static void setHost(Host h) {
        host = h;
        rememberUi(h);
        overlayClosed = false;
        // The browser floats; the chat panel is only the fallback without the overlay permission.
        if (view != null && view.getParent() != null && overlay == null) attach(currentName);
    }

    /** The chat went to the background: the page stays in its window (keeps playing). Main thread. */
    public static void clearHost(Host h) {
        if (host != h) return;
        host = null;
    }

    /** The chat is gone for good: only now the page leaves its window. Main thread. */
    public static void releaseHost(Host h) {
        if (host == h) host = null;
        if (view != null && view.getParent() == h.browserSlot()) {
            h.browserSlot().removeView(view);
            h.hideBrowser();
            layoutDetached();
        }
    }

    /** The user closed the panel: the agent keeps working, unseen, until the next call shows it again. */
    public static void userClosed() {
        detach();
    }

    private static void detach() {
        if (view == null) return;
        ViewGroup p = (ViewGroup) view.getParent();
        if (p != null) p.removeView(view);
        if (host != null) host.hideBrowser();
        removeOverlay();
        layoutDetached();
    }

    private static void layoutDetached() {
        if (view == null || view.getParent() != null) return;
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /**
     * Puts the browser on screen: always as the floating window (over the chat and over other apps,
     * movable and resizable); inside the chat only when floating windows are not allowed; else off
     * screen. Main thread.
     */
    private static void attach(String title) {
        if (view == null) return;
        String label = title + (view.getUrl() == null ? "" : " · " + view.getUrl());
        Context ctx = view.getContext();
        if (screen != null) {
            ViewGroup slot = screen.browserSlot();
            if (view.getParent() != slot) {
                removeOverlay();
                if (host != null && view.getParent() == host.browserSlot()) host.hideBrowser();
                view.changeGroup(slot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            screen.showBrowser(label);
            return;
        }
        if (!overlayClosed && android.provider.Settings.canDrawOverlays(ctx)) {
            if (overlay == null) showOverlay(ctx);
            if (overlay != null) {
                if (view.getParent() != overlay.findViewById(android.R.id.content)) {
                    if (host != null && view.getParent() == host.browserSlot()) host.hideBrowser();
                    if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                    ((FrameLayout) overlay.findViewById(android.R.id.content)).addView(view,
                            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                android.widget.TextView t = overlay.findViewById(android.R.id.title);
                if (t != null) t.setText(label);
                return;
            }
        }
        if (host != null && !overlayClosed) {
            ViewGroup slot = host.browserSlot();
            if (view.getParent() != slot) {
                removeOverlay();
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                slot.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            host.showBrowser(label);
            return;
        }
        layoutDetached();
    }

    /**
     * A floating browser over other apps, with a title and a close button. Drag the title bar to
     * move it; drag the corner grip to resize it.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private static void showOverlay(Context from) {
        // The floating window belongs to the app, not to a screen (the tab may carry an Activity context).
        final Context ctx = from.getApplicationContext();
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF0202124);
            bg.setCornerRadius(20 * d);
            box.setBackground(bg);
            box.setClipToOutline(true);
            android.widget.LinearLayout bar = new android.widget.LinearLayout(ctx);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int p = Math.round(10 * d);
            bar.setPadding(Math.round(14 * d), p, p, p);
            android.widget.TextView title = new android.widget.TextView(ctx);
            title.setId(android.R.id.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(12f);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            bar.addView(title, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // Open the full browser screen (tabs, address bar, settings).
            android.widget.TextView full = new android.widget.TextView(ctx);
            full.setText("⤢");
            full.setTextColor(Color.WHITE);
            full.setTextSize(17f);
            full.setPadding(p, 0, p, 0);
            full.setContentDescription(ctx.getString(com.suvo.agent.R.string.browser_open_full));
            full.setOnClickListener(v -> {
                try {
                    ctx.startActivity(new android.content.Intent(ctx, com.suvo.agent.ui.BrowserActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            });
            bar.addView(full);
            android.widget.TextView close = new android.widget.TextView(ctx);
            close.setText("✕");
            close.setTextColor(Color.WHITE);
            close.setTextSize(16f);
            close.setPadding(p, 0, p, 0);
            close.setOnClickListener(v -> {
                overlayClosed = true;
                detach();
            });
            bar.addView(close);
            box.addView(bar);
            FrameLayout content = new FrameLayout(ctx);
            content.setId(android.R.id.content);
            box.addView(content, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            FrameLayout root = new FrameLayout(ctx);
            root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.ImageView grip = new android.widget.ImageView(ctx);
            grip.setImageResource(com.suvo.agent.R.drawable.ic_resize_grip);
            grip.setBackgroundResource(com.suvo.agent.R.drawable.bg_grip);
            int gp = Math.round(6 * d);
            grip.setPadding(gp, gp, gp, gp);
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(Math.round(30 * d), Math.round(30 * d), Gravity.BOTTOM | Gravity.END);
            glp.setMargins(0, 0, Math.round(6 * d), Math.round(6 * d));
            root.addView(grip, glp);

            int sw = ctx.getResources().getDisplayMetrics().widthPixels, sh = ctx.getResources().getDisplayMetrics().heightPixels;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(sw * 0.94f), Math.round(sh * 0.5f),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (sw - lp.width) / 2;
            lp.y = sh - lp.height - Math.round(24 * d);

            // Move: drag the title bar.
            final float[] mv = new float[4];
            bar.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        mv[0] = e.getRawX();
                        mv[1] = e.getRawY();
                        mv[2] = lp.x;
                        mv[3] = lp.y;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        lp.x = Math.round(Math.max(-lp.width * 0.6f, Math.min(sw - lp.width * 0.4f, mv[2] + e.getRawX() - mv[0])));
                        lp.y = Math.round(Math.max(0, Math.min(sh - 48 * d, mv[3] + e.getRawY() - mv[1])));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (RuntimeException ignored) {
                        }
                        return true;
                    default:
                        return true;
                }
            });
            // Resize: drag the corner grip.
            final float[] rs = new float[4];
            grip.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        rs[0] = e.getRawX();
                        rs[1] = e.getRawY();
                        rs[2] = lp.width;
                        rs[3] = lp.height;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        lp.width = Math.round(Math.max(200 * d, Math.min(sw - Math.max(0, lp.x), rs[2] + e.getRawX() - rs[0])));
                        lp.height = Math.round(Math.max(160 * d, Math.min(sh - Math.max(0, lp.y), rs[3] + e.getRawY() - rs[1])));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (RuntimeException ignored) {
                        }
                        return true;
                    default:
                        return true;
                }
            });
            wm.addView(root, lp);
            overlay = root;
        } catch (RuntimeException e) {
            overlay = null;
        }
    }

    private static void removeOverlay() {
        if (overlay == null) return;
        FrameLayout content = overlay.findViewById(android.R.id.content);
        if (content != null && view != null && view.getParent() == content) content.removeView(view);
        try {
            ((WindowManager) overlay.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (RuntimeException ignored) {
        }
        overlay = null;
    }

    // ------------------------------------------------------------------ setup

    /** The user agent for a connector: the phone's, without the WebView marker (some sites refuse it), or desktop. */
    public static String userAgent(Context ctx, Connector c) {
        if (c != null && c.desktopSite) return DESKTOP_UA;
        String ua = WebSettings.getDefaultUserAgent(ctx);
        return ua.replace("; wv)", ")").replaceAll("Version/\\d+\\.\\d+ ", "");
    }

    /** Settings shared by the login screen and the agent's browser. */
    @SuppressLint("SetJavaScriptEnabled")
    public static void configure(WebView v, Connector c) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        String ua = userAgent(v.getContext(), c);
        if (!ua.equals(s.getUserAgentString())) s.setUserAgentString(ua);
        boolean desktop = c != null && c.desktopSite;
        if (v instanceof AgentWebView) ((AgentWebView) v).desktop = desktop;
        // Pinch zoom (desktop pages are wide).
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Sites (Google too) also read the client hints, which still say "mobile" with only the user
        // agent changed: tell them the same thing.
        clientHints(s, desktop);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(v, true);
    }

    /**
     * Sets Sec-CH-UA-Mobile / navigator.userAgentData (androidx.webkit, called reflectively so an
     * old WebView or library just keeps the user agent alone).
     */
    private static void clientHints(WebSettings s, boolean desktop) {
        try {
            Class<?> feature = Class.forName("androidx.webkit.WebViewFeature");
            String name = (String) feature.getField("USER_AGENT_METADATA").get(null);
            if (!(Boolean) feature.getMethod("isFeatureSupported", String.class).invoke(null, name)) return;
            Class<?> compat = Class.forName("androidx.webkit.WebSettingsCompat");
            Class<?> meta = Class.forName("androidx.webkit.UserAgentMetadata");
            Class<?> builder = Class.forName("androidx.webkit.UserAgentMetadata$Builder");
            Object cur = compat.getMethod("getUserAgentMetadata", WebSettings.class).invoke(null, s);
            if ((Boolean) meta.getMethod("isMobile").invoke(cur) != desktop) return; // already right
            Object b = builder.getConstructor(meta).newInstance(cur);
            builder.getMethod("setMobile", boolean.class).invoke(b, !desktop);
            builder.getMethod("setPlatform", String.class).invoke(b, desktop ? "Linux" : "Android");
            if (desktop) builder.getMethod("setModel", String.class).invoke(b, "");
            compat.getMethod("setUserAgentMetadata", WebSettings.class, meta).invoke(null, s, builder.getMethod("build").invoke(b));
        } catch (Throwable ignored) {
            // Not available: the user agent alone.
        }
    }

    public static final class Bridge {
        @JavascriptInterface
        public void done(String id, String value) {
            Object[] p = pending.remove(id);
            if (p == null) return;
            //noinspection unchecked
            ((AtomicReference<String>) p[1]).set(value);
            ((CountDownLatch) p[0]).countDown();
        }
    }

    @SuppressLint({"AddJavascriptInterface", "SetJavaScriptEnabled"})
    private static AgentWebView createTab(Context ctx) {
        AgentWebView v = new AgentWebView(tabContext(ctx), nextTabId++);
        v.setBackgroundColor(Color.WHITE);
        v.addJavascriptInterface(new Bridge(), "YFBridge");
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView w, String title) {
                tabsChanged();
            }

            @Override
            public void onProgressChanged(WebView w, int p) {
                v.progress = p;
                tabsChanged();
            }
        });
        v.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView w, String url) {
                CookieManager.getInstance().flush();
                if (w instanceof AgentWebView && ((AgentWebView) w).desktop) {
                    // Responsive pages: lay out at desktop width (the page is then zoomed out to fit).
                    w.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');"
                            + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                            + "m.content='width=1280';})()", null);
                }
                if (w == view) {
                    CountDownLatch l = loaded;
                    if (l != null) l.countDown();
                }
                if (host != null && w.getParent() == host.browserSlot()) host.showBrowser(currentName + " · " + url);
                tabsChanged();
            }

            @Override
            public void onPageStarted(WebView w, String url, Bitmap favicon) {
                tabsChanged();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView w, android.webkit.WebResourceRequest req) {
                String sc = req.getUrl().getScheme();
                return sc == null || !(sc.equals("http") || sc.equals("https")); // no app links out of the page
            }

            @Override
            public void onReceivedError(WebView w, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                if (req.isForMainFrame() && w == view) lastError = String.valueOf(err.getDescription());
            }
        });
        configure(v, Connector.openWeb());
        v.onResume();
        v.resumeTimers();
        return v;
    }

    private static WebView viewFor(Context ctx, Connector c) {
        if (view == null) {
            AgentWebView t = createTab(ctx);
            tabs.add(t);
            view = t;
            tabsChanged();
        }
        configure(view, c);
        view.owner = c.name;
        currentName = c.name;
        attach(c.name);
        return view;
    }

    // ------------------------------------------------------------------ tabs (main thread)

    /** The current tab, or null. */
    public static AgentWebView current() {
        return view;
    }

    public static java.util.List<AgentWebView> tabList() {
        return new java.util.ArrayList<>(tabs);
    }

    /** Opens a new empty tab and makes it current; it takes the place of the previous one on screen. */
    public static AgentWebView newTab(Context ctx) {
        AgentWebView t = createTab(ctx);
        tabs.add(t);
        switchTo(t);
        return t;
    }

    public static boolean switchTab(int id) {
        for (AgentWebView t : tabs) {
            if (t.tabId == id) {
                switchTo(t);
                return true;
            }
        }
        return false;
    }

    private static void switchTo(AgentWebView t) {
        AgentWebView old = view;
        if (old == t) return;
        ViewGroup parent = old == null ? null : (ViewGroup) old.getParent();
        ViewGroup.LayoutParams lp = old == null ? null : old.getLayoutParams();
        if (old != null && parent != null) parent.removeView(old);
        view = t;
        currentName = t.owner;
        // The tab in the background keeps running off screen.
        if (old != null && old.getParent() == null) {
            old.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            old.layout(0, 0, W, H);
        }
        if (parent != null) t.changeGroup(parent, lp);
        else layoutDetached();
        tabsChanged();
    }

    public static boolean closeTab(int id) {
        AgentWebView target = null;
        for (AgentWebView t : tabs) if (t.tabId == id) target = t;
        if (target == null) return false;
        int idx = tabs.indexOf(target);
        if (target == view) {
            if (tabs.size() > 1) switchTo(tabs.get(idx > 0 ? idx - 1 : 1));
            else {
                if (target.getParent() instanceof ViewGroup) ((ViewGroup) target.getParent()).removeView(target);
                if (host != null) host.hideBrowser();
                removeOverlay();
                view = null;
            }
        } else if (target.getParent() instanceof ViewGroup) {
            ((ViewGroup) target.getParent()).removeView(target);
        }
        tabs.remove(target);
        try {
            target.stopLoading();
            target.destroy();
        } catch (RuntimeException ignored) {
        }
        tabsChanged();
        return true;
    }

    // ---- the same for the agent's tools (from any thread)

    public static JSONArray tabsJson(Context ctx) throws Exception {
        return onMain(() -> {
            JSONArray a = new JSONArray();
            for (AgentWebView t : tabs) {
                a.put(new JSONObject().put("tab", t.tabId).put("title", t.getTitle() == null ? "" : t.getTitle())
                        .put("url", t.getUrl() == null ? "" : t.getUrl()).put("current", t == view).put("by", t.owner));
            }
            return a;
        }, 5000);
    }

    public static int newTabFor(Context ctx, Connector c) throws Exception {
        return onMain(() -> {
            AgentWebView t = newTab(ctx);
            configure(t, c);
            t.owner = c.name;
            currentName = c.name;
            attach(c.name);
            return t.tabId;
        }, 5000);
    }

    public static boolean switchTabFor(int id) throws Exception {
        return onMain(() -> {
            boolean ok = switchTab(id);
            if (ok && view != null) attach(view.owner);
            return ok;
        }, 5000);
    }

    public static boolean closeTabFor(int id) throws Exception {
        return onMain(() -> closeTab(id), 5000);
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> call, long timeoutMs) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        main.post(() -> {
            try {
                out.set(call.call());
            } catch (Exception e) {
                err.set(e);
            }
            l.countDown();
        });
        if (!l.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new java.io.IOException("The page did not answer in time");
        if (err.get() != null) throw err.get();
        return out.get();
    }

    /** Checks that {@code url} belongs to the connector's site. */
    public static void checkUrl(Connector c, String url) throws java.io.IOException {
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new java.io.IOException("Only http(s) pages");
        if (!c.allowsHost(u.getHost())) {
            throw new java.io.IOException(u.getHost() + " is not part of " + c.name + " (" + c.hosts + ")");
        }
    }

    private static String withoutFragment(String u) {
        if (u == null) return "";
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    /**
     * Loads a page of the connector's site and waits until it has loaded (plus a moment for
     * scripts). An address that only changes the "#…" part of the open page (single-page apps
     * such as Telegram Web) just changes that part, without reloading.
     */
    public static String open(Context ctx, Connector c, String url, long timeoutMs) throws Exception {
        checkUrl(c, url);
        String now = onMain(() -> viewFor(ctx, c).getUrl(), 5000);
        if (now != null && url.contains("#") && withoutFragment(now).equals(withoutFragment(url))) {
            String hash = url.substring(url.indexOf('#'));
            eval(ctx, c, "location.hash = " + JSONObject.quote(hash) + "; return location.href;", 5000);
            Thread.sleep(2000);
            return "opened " + hash + " in the open page";
        }
        CountDownLatch l = new CountDownLatch(1);
        loaded = l;
        lastError = null;
        onMain(() -> {
            viewFor(ctx, c).loadUrl(url);
            return null;
        }, 5000);
        boolean ok = l.await(timeoutMs, TimeUnit.MILLISECONDS);
        Thread.sleep(2000); // single-page apps keep rendering after "loaded"
        if (lastError != null) return "Load error: " + lastError;
        return ok ? "loaded" : "still loading (timed out waiting)";
    }

    /**
     * Runs {@code script} in the current page and returns its result as JSON text ("ERROR: …"
     * when it threw). The script may be: a function body that uses {@code return} / {@code await};
     * a function ({@code async () => {…}} or {@code function(){…}}), which is called; or plain
     * statements / an expression, whose last value is returned. Promises are awaited.
     */
    public static String eval(Context ctx, Connector c, String script, long timeoutMs) throws Exception {
        byte[] b = new byte[12];
        RND.nextBytes(b);
        StringBuilder id = new StringBuilder();
        for (byte x : b) id.append(Integer.toHexString(x & 0xFF));
        String key = id.toString();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        pending.put(key, new Object[]{done, value});
        String s = script == null ? "" : script.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        String run;
        if (s.matches("(?s)^(async\\s+)?(function\\b.*|\\([^)]*\\)\\s*=>.*|[A-Za-z_$][\\w$]*\\s*=>.*)")) {
            run = "__r = (" + s + ");";                                   // a function: called below
        } else if (s.matches("(?s).*\\b(return|await)\\b.*")) {
            run = "__r = await (async () => {" + s + "\n})();";           // a function body
        } else {
            run = "__r = (0, eval)(" + JSONObject.quote(s) + ");";        // statements / expression
        }
        String js = "(async()=>{let __r;try{" + run
                + "if(typeof __r==='function')__r=__r();"
                + "if(__r&&typeof __r.then==='function')__r=await __r;"
                + "let __o;try{__o=JSON.stringify(__r===undefined?null:__r);}catch(e){__o=JSON.stringify(String(__r));}"
                + "YFBridge.done('" + key + "',__o);}"
                + "catch(e){YFBridge.done('" + key + "','ERROR: '+(e&&e.message?e.message:e));}})();";
        onMain(() -> {
            viewFor(ctx, c).evaluateJavascript(js, null);
            return null;
        }, 5000);
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.remove(key);
            return "ERROR: the script did not finish in " + (timeoutMs / 1000) + " s";
        }
        return value.get();
    }

    /** Title, address, visible text, links, buttons and inputs of the current page. */
    public static String text(Context ctx, Connector c, int maxChars) throws Exception {
        return eval(ctx, c, "const vis=e=>{const r=e.getBoundingClientRect();return r.width>1&&r.height>1};"
                + "const links=[...document.querySelectorAll('a[href]')].filter(vis).slice(0,60)"
                + ".map(a=>((a.innerText||a.getAttribute('aria-label')||'').trim().replace(/\\s+/g,' ').slice(0,60))+' -> '+a.href);"
                + "const buttons=[...document.querySelectorAll('button,[role=button]')].filter(vis).slice(0,40)"
                + ".map(b=>(b.innerText||b.getAttribute('aria-label')||b.title||'').trim().slice(0,40)).filter(t=>t);"
                + "const inputs=[...document.querySelectorAll('input,textarea,[contenteditable=true]')].filter(vis).slice(0,20)"
                + ".map(i=>(i.getAttribute('aria-label')||i.placeholder||i.name||i.tagName).slice(0,40));"
                + "return {title:document.title,url:location.href,text:(document.body?document.body.innerText:'').slice(0," + maxChars + "),"
                + "links:links,buttons:buttons,inputs:inputs};", 20_000);
    }

    private static final String FIND = "const __find=(sel,txt)=>{"
            + "if(sel){const e=document.querySelector(sel);if(e)return e;}"
            + "if(!txt)return null;const t=txt.trim().toLowerCase();let best=null,bl=1e9;"
            + "for(const e of document.querySelectorAll('body *')){const r=e.getBoundingClientRect();"
            + "if(r.width<2||r.height<2||r.bottom<0||r.right<0)continue;"
            + "const s=(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.title||'').trim().toLowerCase();"
            + "if(!s.includes(t))continue;if(s.length<bl){best=e;bl=s.length;}}return best;};";

    /**
     * Taps an element (CSS selector, or the smallest visible element containing {@code text}) with
     * a real touch at its centre, like a finger would. Returns what was tapped, or an error.
     */
    public static String tap(Context ctx, Connector c, String selector, String text) throws Exception {
        String where = eval(ctx, c, FIND + "const e=__find(" + JSONObject.quote(selector == null ? "" : selector) + ","
                + JSONObject.quote(text == null ? "" : text) + ");if(!e)return null;"
                + "e.scrollIntoView({block:'center',inline:'center'});await new Promise(r=>setTimeout(r,350));"
                + "const r=e.getBoundingClientRect();"
                + "return {x:r.left+r.width/2,y:r.top+r.height/2,vw:window.innerWidth,"
                + "what:(e.innerText||e.getAttribute('aria-label')||e.tagName).trim().slice(0,60)};", 10_000);
        if (where == null || where.equals("null")) return "ERROR: no element matches " + (selector != null && !selector.isEmpty() ? selector : "\"" + text + "\"");
        if (where.startsWith("ERROR")) return where;
        JSONObject o = new JSONObject(where);
        double x = o.optDouble("x"), y = o.optDouble("y"), vw = o.optDouble("vw", 0);
        onMain(() -> {
            WebView v = viewFor(ctx, c);
            float scale = vw > 0 ? v.getWidth() / (float) vw : ctx.getResources().getDisplayMetrics().density;
            float px = (float) (x * scale), py = (float) (y * scale);
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            v.dispatchTouchEvent(down);
            down.recycle();
            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, px, py, 0);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            v.dispatchTouchEvent(up);
            up.recycle();
            return null;
        }, 5000);
        Thread.sleep(1200);
        return "tapped \"" + o.optString("what").replace('\n', ' ') + "\"";
    }

    /** Types text into the focused field (or the one found first) like a keyboard: input events fire. */
    public static String type(Context ctx, Connector c, String selector, String text, String value) throws Exception {
        if ((selector != null && !selector.isEmpty()) || (text != null && !text.isEmpty())) {
            String t = tap(ctx, c, selector, text);
            if (t.startsWith("ERROR")) return t;
        }
        return eval(ctx, c, "const a=document.activeElement;if(!a||a===document.body)return 'ERROR: no field is focused (tap one first)';"
                + "const ok=document.execCommand('insertText',false," + JSONObject.quote(value) + ");"
                + "if(!ok&&'value' in a){const p=Object.getPrototypeOf(a);const d=Object.getOwnPropertyDescriptor(p,'value');"
                + "d.set.call(a,(a.value||'')+" + JSONObject.quote(value) + ");a.dispatchEvent(new Event('input',{bubbles:true}));}"
                + "return 'typed into '+(a.getAttribute('aria-label')||a.placeholder||a.tagName);", 10_000);
    }

    /** Presses a key in the page (Enter, Tab, Backspace, Escape, ArrowDown, ArrowUp). */
    public static String key(Context ctx, Connector c, String name) throws Exception {
        int code;
        switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "enter":
                code = KeyEvent.KEYCODE_ENTER;
                break;
            case "tab":
                code = KeyEvent.KEYCODE_TAB;
                break;
            case "backspace":
                code = KeyEvent.KEYCODE_DEL;
                break;
            case "escape":
            case "esc":
                code = KeyEvent.KEYCODE_ESCAPE;
                break;
            case "arrowdown":
            case "down":
                code = KeyEvent.KEYCODE_DPAD_DOWN;
                break;
            case "arrowup":
            case "up":
                code = KeyEvent.KEYCODE_DPAD_UP;
                break;
            default:
                return "ERROR: unknown key " + name;
        }
        onMain(() -> {
            WebView v = viewFor(ctx, c);
            v.requestFocus();
            long t = SystemClock.uptimeMillis();
            v.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            v.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
            return null;
        }, 5000);
        Thread.sleep(800);
        return "pressed " + name;
    }

    /** Scrolls the page, or the scrollable element matching {@code selector} / containing {@code text}. */
    public static String scroll(Context ctx, Connector c, String selector, String text, int dy) throws Exception {
        String r = eval(ctx, c, FIND + "let e=__find(" + JSONObject.quote(selector == null ? "" : selector) + ","
                + JSONObject.quote(text == null ? "" : text) + ");"
                + "while(e&&e!==document.body&&!(e.scrollHeight>e.clientHeight+4&&/(auto|scroll)/.test(getComputedStyle(e).overflowY)))e=e.parentElement;"
                + "const t=e&&e!==document.body?e:(document.scrollingElement||document.body);"
                + "const before=t.scrollTop;t.scrollBy(0," + dy + ");await new Promise(r=>setTimeout(r,900));"
                + "return {moved:Math.round(t.scrollTop-before),at:Math.round(t.scrollTop),max:Math.round(t.scrollHeight-t.clientHeight)};", 10_000);
        return r;
    }

    /** Goes back in the page history. */
    public static String back(Context ctx, Connector c) throws Exception {
        boolean went = onMain(() -> {
            WebView v = viewFor(ctx, c);
            if (!v.canGoBack()) return false;
            v.goBack();
            return true;
        }, 5000);
        Thread.sleep(1500);
        return went ? "went back" : "no page to go back to";
    }

    /** A picture of the page as the agent's browser shows it. */
    public static Bitmap screenshot(Context ctx, Connector c) throws Exception {
        return onMain(() -> {
            WebView v = viewFor(ctx, c);
            int w = v.getWidth() > 0 ? v.getWidth() : W, h = v.getHeight() > 0 ? v.getHeight() : H;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bmp));
            return bmp;
        }, 8000);
    }

    /** The current address (or ""). */
    public static String url(Context ctx, Connector c) throws Exception {
        String u = onMain(() -> view == null ? "" : view.getUrl(), 3000);
        return u == null ? "" : u;
    }

    /** Signs out of a site: removes its cookies. */
    public static void forget(Connector c) {
        CookieManager cm = CookieManager.getInstance();
        for (String host : c.hostList()) {
            for (String base : new String[]{"https://" + host, "https://www." + host}) {
                String all = cm.getCookie(base);
                if (all == null) continue;
                for (String kv : all.split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String name = kv.substring(0, eq).trim();
                    cm.setCookie(base, name + "=; Max-Age=0; Path=/");
                    cm.setCookie(base, name + "=; Max-Age=0; Path=/; Domain=." + host);
                }
            }
        }
        cm.flush();
    }

    /** Whether the site has any cookies (a rough "logged in?"). Sites that keep the login in page storage (Telegram Web) count as logged in once opened. */
    public static boolean hasCookies(Connector c) {
        CookieManager cm = CookieManager.getInstance();
        for (String host : c.hostList()) {
            String v = cm.getCookie("https://www." + host);
            if (v == null) v = cm.getCookie("https://" + host);
            if (v != null && !v.isEmpty()) return true;
        }
        return c.homeUrl.contains("web.telegram.org") || c.homeUrl.contains("web.whatsapp.com");
    }
}
