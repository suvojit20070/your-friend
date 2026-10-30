package com.suvo.agent.connectors;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Your Friend — Original project by Suvo.
 *
 * The logged-in websites of the web connectors (Instagram, Facebook, X, WhatsApp Web…), used by
 * the agent in the background. One off-screen WebView shares its cookies with the login screen,
 * so it is the user's own session. The agent opens pages, reads their text, runs JavaScript in
 * them (click, type, scroll) and can make requests from inside the page (with the site's own
 * cookies) — anything the user could do there.
 *
 * All calls block the calling (agent) thread; the WebView itself lives on the main thread.
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

    /** The user agent for a connector: the phone's, without the WebView marker (some sites refuse it), or desktop. */
    public static String userAgent(Context ctx, Connector c) {
        if (c != null && c.desktopSite) return DESKTOP_UA;
        String ua = WebSettings.getDefaultUserAgent(ctx);
        return ua.replace("; wv)", ")").replaceAll("Version/\\d+\\.\\d+ ", "");
    }

    /** Settings shared by the login screen and the background view. */
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
        s.setUserAgentString(userAgent(v.getContext(), c));
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
                }

                @Override
                public void onReceivedError(WebView v, android.webkit.WebResourceRequest req, android.webkit.WebResourceError err) {
                    if (req.isForMainFrame()) lastError = String.valueOf(err.getDescription());
                }
            });
            // Off screen but with a real size, so pages lay out (and innerText works).
            view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, W, H);
            view.onResume();
            view.resumeTimers();
        }
        configure(view, c);
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

    /** Loads a page of the connector's site and waits until it has loaded (plus a moment for scripts). */
    public static String open(Context ctx, Connector c, String url, long timeoutMs) throws Exception {
        checkUrl(c, url);
        CountDownLatch l = new CountDownLatch(1);
        loaded = l;
        lastError = null;
        onMain(() -> {
            viewFor(ctx, c).loadUrl(url);
            return null;
        }, 5000);
        boolean ok = l.await(timeoutMs, TimeUnit.MILLISECONDS);
        Thread.sleep(1800); // single-page apps keep rendering after "loaded"
        if (lastError != null) return "Load error: " + lastError;
        return ok ? "loaded" : "still loading (timed out waiting)";
    }

    /**
     * Runs {@code script} as the body of an async function in the current page and returns its
     * result as JSON text ("ERROR: …" when it threw).
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
        String js = "(async()=>{let __r;try{__r=await (async()=>{" + script + "\n})();"
                + "YFBridge.done('" + key + "',JSON.stringify(__r===undefined?null:__r));}"
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

    /** Title, address, visible text and links of the current page. */
    public static String text(Context ctx, Connector c, int maxChars) throws Exception {
        return eval(ctx, c, "const links=[...document.querySelectorAll('a[href]')].slice(0,60)"
                + ".map(a=>((a.innerText||a.getAttribute('aria-label')||'').trim().slice(0,60))+' -> '+a.href);"
                + "const buttons=[...document.querySelectorAll('button,[role=button]')].slice(0,40)"
                + ".map(b=>(b.innerText||b.getAttribute('aria-label')||'').trim().slice(0,40)).filter(t=>t);"
                + "const inputs=[...document.querySelectorAll('input,textarea,[contenteditable=true]')].slice(0,20)"
                + ".map(i=>(i.getAttribute('aria-label')||i.placeholder||i.name||i.tagName).slice(0,40));"
                + "return {title:document.title,url:location.href,text:(document.body?document.body.innerText:'').slice(0," + maxChars + "),"
                + "links:links,buttons:buttons,inputs:inputs};", 20_000);
    }

    /** A picture of the page as the agent's browser shows it. */
    public static Bitmap screenshot(Context ctx, Connector c) throws Exception {
        return onMain(() -> {
            WebView v = viewFor(ctx, c);
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
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

    /** Whether the site has any cookies (a rough "logged in?"). */
    public static boolean hasCookies(Connector c) {
        CookieManager cm = CookieManager.getInstance();
        for (String host : c.hostList()) {
            String v = cm.getCookie("https://www." + host);
            if (v == null) v = cm.getCookie("https://" + host);
            if (v != null && !v.isEmpty()) return true;
        }
        return false;
    }
}
