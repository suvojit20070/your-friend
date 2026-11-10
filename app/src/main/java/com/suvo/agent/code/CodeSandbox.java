package com.suvo.agent.code;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Your Friend — Original project by Suvo.
 *
 * Code mode's sandbox: runs a script the agent wrote, in a JavaScript worker of its own hidden
 * page (the phone's own V8, no extra library). The script has no network (the page's content
 * policy allows no connections and the network objects are removed), no files, no page, no app
 * objects: only {@code tools.<name>(args)}, which asks the app to run that tool — through the
 * normal tool pipeline, so every call is validated, risk-checked and approved like any other —
 * and gives the result back as a Promise. Secrets (keys, cookies, tokens) never enter the script.
 * A script that hangs or runs too long is terminated (its worker is killed; nothing else stops).
 */
public final class CodeSandbox {

    /** Runs one tool for the script. */
    public interface Caller {
        /** JSON to give the script; {@code ok[0]} false = reject the Promise with it. */
        String call(String name, String argsJson, boolean[] ok);
    }

    /** What a run produced. */
    public static final class Result {
        public boolean ok;
        /** JSON of the returned value, or the error text. */
        public String value = "null";
        public final StringBuilder logs = new StringBuilder();
        public int calls;
        public boolean timedOut, cancelled;
    }

    // ---- inside the worker: everything the script can see

    static final String PRELUDE = "'use strict';var __p={},__n=0;"
            + "['fetch','XMLHttpRequest','WebSocket','EventSource','importScripts','indexedDB','caches','BroadcastChannel','WebTransport','Worker']"
            + ".forEach(function(n){try{Object.defineProperty(self,n,{value:undefined,writable:false,configurable:false})}catch(e){try{self[n]=undefined}catch(x){}}});"
            + "function __call(name,args){return new Promise(function(res,rej){var a;try{a=JSON.stringify(args===undefined?{}:args)}"
            + "catch(e){rej(new Error('tool arguments must be plain JSON'));return}var id=++__n;__p[id]={res:res,rej:rej};"
            + "postMessage({t:'call',id:id,name:name,args:a})})}"
            + "onmessage=function(e){var m=e.data;if(m.t!=='result')return;var p=__p[m.id];delete __p[m.id];if(!p)return;var v;"
            + "try{v=JSON.parse(m.value)}catch(x){v=m.value}if(m.ok)p.res(v);else{var er=new Error(typeof v==='string'?v:(v&&v.message)||'tool failed');"
            + "er.tool=v;p.rej(er)}};"
            + "var tools={};(/*NAMES*/).forEach(function(n){tools[n]=function(args){return __call(n,args)}});"
            + "tools.docs=function(n){return __call('__docs',{name:n})};tools.list=function(){return __call('__list',{})};"
            + "var console={log:function(){postMessage({t:'log',text:[].map.call(arguments,function(a){return typeof a==='string'?a:JSON.stringify(a)}).join(' ')})}};"
            + "console.info=console.warn=console.error=console.debug=console.log;"
            + "function sleep(ms){return new Promise(function(r){setTimeout(r,Math.min(Number(ms)||0,60000))})}"
            + "function __run(f){Promise.resolve().then(f).then(function(v){var s;try{s=JSON.stringify(v===undefined?null:v)}catch(e){s=JSON.stringify(String(v))}"
            + "postMessage({t:'done',ok:true,value:s===undefined?'null':s})},function(e){postMessage({t:'done',ok:false,value:String(e&&e.stack||e)})})}";

    /** The host page: starts a worker per run and passes messages between it and the app. */
    static String page() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' blob:; "
                + "worker-src blob:; connect-src 'none'\"></head><body><script>"
                + "var W=null,runId=0,PRELUDE=" + JSONObject.quote(PRELUDE) + ";"
                + "function __yfRun(id,code,names){if(W){try{W.terminate()}catch(e){}}runId=id;"
                + "var src=PRELUDE.replace('/*NAMES*/',JSON.stringify(names))+'\\n__run(async function(){\\n'+code+'\\n});';"
                + "var url=URL.createObjectURL(new Blob([src],{type:'text/javascript'}));"
                + "try{W=new Worker(url)}catch(e){YF.done(id,false,'could not start the sandbox: '+e);return}"
                + "W.onmessage=function(e){var m=e.data;if(m.t==='call')YF.call(id,m.id,m.name,m.args);else if(m.t==='log')YF.log(id,m.text);"
                + "else if(m.t==='done'){YF.done(id,m.ok,m.value);try{W.terminate()}catch(x){}W=null;URL.revokeObjectURL(url)}};"
                + "W.onerror=function(e){YF.done(id,false,String(e.message||'script error')+(e.lineno?' (line '+Math.max(1,e.lineno-2)+')':''));"
                + "try{W.terminate()}catch(x){}W=null};}"
                + "function __yfResolve(id,callId,ok,json){if(W&&id===runId)W.postMessage({t:'result',id:callId,ok:ok,value:json})}"
                + "function __yfStop(id){if(W&&id===runId){try{W.terminate()}catch(e){}W=null}}"
                + "</script></body></html>";
    }

    // ---- the app side

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ReentrantLock LOCK = new ReentrantLock(true); // one script at a time
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "code-tool");
        t.setDaemon(true);
        return t;
    });
    private static WebView web;
    private static volatile CountDownLatch pageReady;
    private static int nextRun;

    private static final class Run {
        final int id;
        final Caller caller;
        final Result result = new Result();
        final CountDownLatch done = new CountDownLatch(1);
        final int maxCalls;

        Run(int id, Caller caller, int maxCalls) {
            this.id = id;
            this.caller = caller;
            this.maxCalls = maxCalls;
        }
    }

    private static volatile Run current;

    private CodeSandbox() {
    }

    /** What the page calls. */
    private static final class Bridge {
        @JavascriptInterface
        public void call(int runId, int callId, String name, String args) {
            Run r = current;
            if (r == null || r.id != runId) return;
            final int n;
            synchronized (r.result) {
                n = ++r.result.calls;
            }
            POOL.execute(() -> {
                boolean[] ok = {true};
                String out;
                if (n > r.maxCalls) {
                    ok[0] = false;
                    out = JSONObject.quote("Too many tool calls in one script (limit " + r.maxCalls + "): do the rest in another run");
                } else {
                    try {
                        out = r.caller.call(name, args, ok);
                    } catch (RuntimeException e) {
                        ok[0] = false;
                        out = JSONObject.quote(String.valueOf(e.getMessage()));
                    }
                }
                final String json = out == null ? "null" : out;
                final boolean good = ok[0];
                MAIN.post(() -> {
                    if (web != null && current == r) {
                        web.evaluateJavascript("__yfResolve(" + runId + "," + callId + "," + good + "," + JSONObject.quote(json) + ")", null);
                    }
                });
            });
        }

        @JavascriptInterface
        public void log(int runId, String text) {
            Run r = current;
            if (r == null || r.id != runId || text == null) return;
            synchronized (r.result) {
                if (r.result.logs.length() < 8000) r.result.logs.append(text).append('\n');
            }
        }

        @JavascriptInterface
        public void done(int runId, boolean ok, String value) {
            Run r = current;
            if (r == null || r.id != runId) return;
            r.result.ok = ok;
            r.result.value = value == null ? "null" : value;
            r.done.countDown();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void ensurePage(Context ctx) throws InterruptedException {
        if (pageReady == null) {
            final CountDownLatch ready = new CountDownLatch(1);
            pageReady = ready;
            MAIN.post(() -> {
                web = new WebView(ctx.getApplicationContext());
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setDomStorageEnabled(false);
                s.setDatabaseEnabled(false);
                s.setGeolocationEnabled(false);
                s.setSaveFormData(false);
                web.addJavascriptInterface(new Bridge(), "YF");
                web.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        ready.countDown();
                    }
                });
                // A fixed, unreachable origin: blob workers work, nothing is shared with real sites.
                web.loadDataWithBaseURL("https://sandbox.yourfriend.invalid/", page(), "text/html", "utf-8", null);
            });
        }
        if (!pageReady.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("The code sandbox did not start");
    }

    /**
     * Runs {@code code} (the body of an async function). Blocks until it returns, fails, is
     * cancelled, or {@code timeoutMs} passed. Never call it on the main thread.
     */
    public static Result run(Context ctx, String code, List<String> toolNames, Caller caller, int maxCalls, long timeoutMs,
                             CancellationToken cancel) throws InterruptedException {
        LOCK.lockInterruptibly();
        try {
            ensurePage(ctx);
            final Run r = new Run(++nextRun, caller, maxCalls);
            current = r;
            final String js = "__yfRun(" + r.id + "," + JSONObject.quote(code) + "," + new JSONArray(toolNames) + ")";
            MAIN.post(() -> web.evaluateJavascript(js, null));
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!r.done.await(200, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.isCancelled()) {
                    r.result.cancelled = true;
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    r.result.timedOut = true;
                    break;
                }
            }
            if (r.result.cancelled || r.result.timedOut) {
                r.result.ok = false;
                r.result.value = r.result.cancelled ? "cancelled" : "The script ran longer than " + (timeoutMs / 1000) + " s and was stopped";
                MAIN.post(() -> {
                    if (web != null) web.evaluateJavascript("__yfStop(" + r.id + ")", null);
                });
            }
            current = null;
            return r.result;
        } finally {
            LOCK.unlock();
        }
    }
}
