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
    @SuppressLint("StaticFieldLeak") // application context only
    private static WebView view;
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

    /** The chat became visible: the browser shows there from now on. Main thread. */
    public static void setHost(Host h) {
        host = h;
        overlayClosed = false;
        if (view != null && view.getParent() != null) attach(currentName); // move it here at once
    }

    /** The chat went away. Main thread. */
    public static void clearHost(Host h) {
        if (host != h) return;
        host = null;
        if (view != null && view.getParent() == h.browserSlot()) {
            h.browserSlot().removeView(view);
            h.hideBrowser();
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

    /** Puts the browser on screen: in the chat, else a floating window, else off screen. Main thread. */
    private static void attach(String title) {
        if (view == null) return;
        String label = title + (view.getUrl() == null ? "" : " · " + view.getUrl());
        if (host != null) {
            ViewGroup slot = host.browserSlot();
            if (view.getParent() != slot) {
                removeOverlay();
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                slot.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            host.showBrowser(label);
            return;
        }
        Context ctx = view.getContext();
        if (!overlayClosed && android.provider.Settings.canDrawOverlays(ctx)) {
            if (overlay == null) showOverlay(ctx);
            if (overlay != null && view.getParent() != overlay.findViewById(android.R.id.content)) {
                if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                ((FrameLayout) overlay.findViewById(android.R.id.content)).addView(view,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (overlay != null) {
                android.widget.TextView t = overlay.findViewById(android.R.id.title);
                if (t != null) t.setText(label);
                return;
            }
        }
        layoutDetached();
    }

    /** A small floating browser at the bottom of the screen, with a title and a close button. */
    private static void showOverlay(Context ctx) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;
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
            int sw = ctx.getResources().getDisplayMetrics().widthPixels, sh = ctx.getResources().getDisplayMetrics().heightPixels;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(sw * 0.94f), Math.round(sh * 0.5f),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(24 * d);
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).addView(root, lp);
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
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(v, true);
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
    private static WebView viewFor(Context ctx, Connector c) {
        if (view == null) {
            view = new WebView(ctx.getApplicationContext());
            view.setBackgroundColor(Color.WHITE);
            view.addJavascriptInterface(new Bridge(), "YFBridge");
            view.setWebChromeClient(new WebChromeClient());
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    CookieManager.getInstance().flush();
                    CountDownLatch l = loaded;
                    if (l != null) l.countDown();
                    if (host != null && v.getParent() == host.browserSlot()) host.showBrowser(currentName + " · " + url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                    String s = req.getUrl().getScheme();
                    return s == null || !(s.equals("http") || s.equals("https")); // no app links out of the page
                }

                @Override
                public void onReceivedError(WebView v, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                    if (req.isForMainFrame()) lastError = String.valueOf(err.getDescription());
                }
            });
            view.onResume();
            view.resumeTimers();
        }
        configure(view, c);
        currentName = c.name;
        attach(c.name);
        return view;
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
