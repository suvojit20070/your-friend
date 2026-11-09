package com.suvo.agent.connectors;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * What makes the agent's browser easy for the agent, so the system handles the hard parts:
 * <ul>
 * <li>Every page is instrumented from its first line: fetch / XHR / WebSocket calls are recorded
 * (method, address, status, a bit of the request and the answer), so the agent can see the site's
 * own API and call it directly (browser action fetch) instead of clicking through pages.</li>
 * <li>An element list like the phone's screen tree: every button, link, field, icon button,
 * checkbox, menu item… with a number, a label (also for icon-only buttons: aria-label, title,
 * alt, the icon's name), its state and whether something covers it. The agent taps / types /
 * picks by that number — a real touch, so pages treat it as a user.</li>
 * <li>File pickers: the agent arms workspace files; the page's next file picker gets them at once
 * (no system picker), or they are put straight into the page's file input.</li>
 * <li>alert / confirm / prompt / "leave page?" wait for the agent (or the user) to answer.</li>
 * <li>Downloads land in the workspace; new windows open as tabs; camera / microphone / location
 * requests are refused and reported.</li>
 * </ul>
 */
public final class PageTools {

    private PageTools() {
    }

    // ------------------------------------------------------------------ network log (document start)

    /** Records the page's own API calls in window.__yfNet (newest 300). Runs before the page's scripts. */
    static final String HOOK_JS = "(function(){if(window.__yfNet)return;var L=window.__yfNet=[];"
            + "function add(o){L.push(o);if(L.length>300)L.shift();}"
            + "function abs(u){try{return new URL(u,location.href).href}catch(e){return String(u)}}"
            + "function txt(b){try{if(typeof b==='string')return b.slice(0,500);if(b instanceof URLSearchParams)return b.toString().slice(0,500);"
            + "if(b instanceof FormData){var a=[];b.forEach(function(v,k){a.push(k+'='+(typeof v==='string'?v.slice(0,80):'[file]'))});return a.join('&').slice(0,500)}}catch(e){}return b?'['+(b.constructor&&b.constructor.name||'body')+']':''}"
            + "var of=window.fetch;if(of){window.fetch=function(i,init){var u=abs(typeof i==='string'?i:(i&&i.url)||'');"
            + "var m=String((init&&init.method)||(i&&i.method)||'GET').toUpperCase();var t=Date.now();var rec={k:'fetch',m:m,u:u,b:txt(init&&init.body)};"
            + "return of.apply(this,arguments).then(function(r){rec.s=r.status;rec.ct=r.headers.get('content-type')||'';rec.ms=Date.now()-t;add(rec);"
            + "if(/json|text|javascript/.test(rec.ct)){try{r.clone().text().then(function(x){rec.res=x.slice(0,700)}).catch(function(){})}catch(e){}}return r},"
            + "function(e){rec.s=0;rec.err=String(e);add(rec);throw e})}}"
            + "var X=XMLHttpRequest.prototype,XO=X.open,XS=X.send;X.open=function(m,u){this.__yf={k:'xhr',m:String(m||'GET').toUpperCase(),u:abs(u)};return XO.apply(this,arguments)};"
            + "X.send=function(b){var r=this.__yf,self=this;if(r){r.b=txt(b);var t=Date.now();this.addEventListener('loadend',function(){r.s=self.status;"
            + "r.ct=self.getResponseHeader('content-type')||'';r.ms=Date.now()-t;try{if(/json|text/.test(r.ct)&&(self.responseType===''||self.responseType==='text'))r.res=String(self.responseText).slice(0,700)}catch(e){}add(r)})}"
            + "return XS.apply(this,arguments)};"
            + "var WS=window.WebSocket;if(WS){var W=function(u,p){add({k:'ws',m:'WS',u:abs(u)});return p===undefined?new WS(u):new WS(u,p)};"
            + "W.prototype=WS.prototype;W.CONNECTING=0;W.OPEN=1;W.CLOSING=2;W.CLOSED=3;window.WebSocket=W}"
            + "})();";

    /** Registers HOOK_JS to run at the start of every page of {@code v} (androidx.webkit, when the WebView supports it). */
    static boolean installDocumentStart(WebView v) {
        try {
            Class<?> feature = Class.forName("androidx.webkit.WebViewFeature");
            String name = (String) feature.getField("DOCUMENT_START_SCRIPT").get(null);
            if (!(Boolean) feature.getMethod("isFeatureSupported", String.class).invoke(null, name)) return false;
            Class<?> compat = Class.forName("androidx.webkit.WebViewCompat");
            Set<String> any = new HashSet<>(Collections.singletonList("*"));
            compat.getMethod("addDocumentStartJavaScript", WebView.class, String.class, Set.class).invoke(null, v, HOOK_JS, any);
            return true;
        } catch (Throwable e) {
            return false; // the page-started fallback still records later calls
        }
    }

    /** The site's API calls seen so far, grouped by method + address (without query), newest first. */
    public static String networkJs(String filter, int limit) {
        return "var f=" + JSONObject.quote(filter == null ? "" : filter.toLowerCase(java.util.Locale.ROOT)) + ";"
                + "var L=(window.__yfNet||[]).slice();"
                + "try{performance.getEntriesByType('resource').forEach(function(e){if(e.initiatorType==='fetch'||e.initiatorType==='xmlhttprequest')"
                + "if(!L.some(function(x){return x.u===e.name}))L.unshift({k:e.initiatorType,m:'?',u:e.name,s:e.responseStatus||'',early:true})})}catch(e){}"
                + "var g={},order=[];for(var i=L.length-1;i>=0;i--){var r=L[i];if(f&&(r.u+' '+r.m).toLowerCase().indexOf(f)<0)continue;"
                + "var base=r.u.split('#')[0].split('?')[0];var key=r.m+' '+base;if(!g[key]){g[key]={method:r.m,endpoint:base,calls:0,last_url:r.u,status:r.s,"
                + "type:r.ct||'',kind:r.k,request:r.b||undefined,response:r.res||undefined};order.push(key)}g[key].calls++}"
                + "return {page:location.href,recorded:(window.__yfNet||[]).length,endpoints:order.slice(0," + limit + ").map(function(k){return g[k]})};";
    }

    // ------------------------------------------------------------------ element list

    /**
     * Numbers every interactive element of the page (also in open shadow roots) and returns them as
     * lines like {@code [12] button "Like"} — icon buttons get their aria-label / title / icon name.
     * The numbers stay on the elements (data-yf) until the next list.
     */
    public static String elementsJs(int max, boolean all) {
        return "var MAX=" + max + ",ALL=" + all + ";"
                + "var R='a[href],button,input:not([type=hidden]),textarea,select,summary,label[for],[role=button],[role=link],[role=checkbox],"
                + "[role=radio],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],[role=option],[role=switch],[role=combobox],"
                + "[role=textbox],[role=searchbox],[role=slider],[role=treeitem],[onclick],[contenteditable=\"\"],[contenteditable=true],[tabindex]:not([tabindex=\"-1\"])';"
                + "var vh=innerHeight,vw=innerWidth,out=[],seen=new Set(),els=[];"
                + "function walk(root){var list=root.querySelectorAll('*');for(var i=0;i<list.length;i++){var e=list[i];"
                + "if(e.shadowRoot)walk(e.shadowRoot);if(seen.has(e))continue;var hit=false;try{hit=e.matches(R)}catch(x){}"
                + "if(!hit){try{var cs=getComputedStyle(e);hit=cs.cursor==='pointer'&&(!e.parentElement||getComputedStyle(e.parentElement).cursor!=='pointer')}catch(x){}}"
                + "if(hit){seen.add(e);els.push(e)}}}"
                + "walk(document);"
                + "function lab(e){var t=e.getAttribute('aria-label')||'';if(!t&&e.getAttribute('aria-labelledby')){t=e.getAttribute('aria-labelledby').split(' ')"
                + ".map(function(id){var n=document.getElementById(id);return n?n.innerText:''}).join(' ')}"
                + "if(!t&&e.labels&&e.labels.length)t=e.labels[0].innerText;if(!t)t=(e.innerText||'').trim();if(!t)t=e.title||e.getAttribute('alt')||e.placeholder||'';"
                + "if(!t&&e.value&&typeof e.value==='string'&&e.type!=='password')t=e.value;"
                + "if(!t){var im=e.querySelector&&e.querySelector('img[alt],svg[aria-label],svg title,[aria-label],[title]');"
                + "if(im)t=im.getAttribute('alt')||im.getAttribute('aria-label')||im.getAttribute('title')||im.textContent||'';if(t)t='icon: '+t}"
                + "if(!t)t=e.name||e.id||(e.getAttribute('data-testid')||'');if(!t&&e.querySelector&&e.querySelector('svg,img,i'))t='(icon)';"
                + "return String(t).replace(/\\s+/g,' ').trim().slice(0,90)}"
                + "function kind(e){var r=e.getAttribute('role'),tg=e.tagName.toLowerCase();if(tg==='input'){var ty=(e.type||'text').toLowerCase();"
                + "return ty==='checkbox'||ty==='radio'||ty==='file'||ty==='submit'||ty==='button'||ty==='range'?ty:'input:'+ty}"
                + "if(r)return r;if(tg==='a')return 'link';if(tg==='select')return 'select';if(tg==='textarea')return 'textarea';"
                + "if(e.isContentEditable)return 'editor';if(tg==='button')return 'button';return tg+'(clickable)'}"
                + "document.querySelectorAll('[data-yf]').forEach(function(e){e.removeAttribute('data-yf')});"
                + "var n=0,below=0,dlg=document.querySelector('dialog[open],[role=dialog],[role=alertdialog],[aria-modal=true]');"
                + "window.__yfEls=[];"
                + "for(var i=0;i<els.length&&n<MAX;i++){var e=els[i];var r=e.getBoundingClientRect();if(r.width<3||r.height<3)continue;"
                + "var st=getComputedStyle(e);if(st.visibility==='hidden'||st.display==='none'||Number(st.opacity)===0&&e.type!=='file')continue;"
                + "var on=r.bottom>0&&r.top<vh&&r.right>0&&r.left<vw;if(!on&&!ALL){if(r.top>=vh&&r.top<vh*2.5)below++;continue}"
                + "var parentHit=false;for(var p=e.parentElement;p&&!parentHit;p=p.parentElement)if(seen.has(p)&&p.tagName!=='LABEL'&&(p.innerText||'').trim()===(e.innerText||'').trim()&&(e.innerText||'').trim())parentHit=true;"
                + "if(parentHit)continue;"
                + "var id=window.__yfEls.length;window.__yfEls.push(e);e.setAttribute('data-yf',id);n++;"
                + "var s='['+id+'] '+kind(e)+' \"'+lab(e)+'\"';"
                + "if(e.tagName==='A'){var h=e.getAttribute('href')||'';if(h&&h.indexOf('javascript')!==0)s+=' -> '+h.slice(0,80)}"
                + "if(e.disabled||e.getAttribute('aria-disabled')==='true')s+=' (disabled)';"
                + "if(e.checked||e.getAttribute('aria-checked')==='true'||e.getAttribute('aria-pressed')==='true'||e.getAttribute('aria-selected')==='true')s+=' (on)';"
                + "if(e.getAttribute('aria-expanded'))s+=' (expanded='+e.getAttribute('aria-expanded')+')';"
                + "if(e.tagName==='SELECT'){s+=' options: '+[].slice.call(e.options,0,15).map(function(o){return (o.selected?'*':'')+o.text.trim().slice(0,30)}).join(' | ')}"
                + "else if((e.tagName==='INPUT'||e.tagName==='TEXTAREA')&&e.type!=='password'&&e.value&&e.type!=='file')s+=' value=\"'+String(e.value).slice(0,60)+'\"';"
                + "if(e.type==='file')s+=' accept='+(e.accept||'any')+(e.multiple?' multiple':'');"
                + "if(!on)s+=' (off screen)';else{var cx=Math.min(vw-1,Math.max(0,r.left+r.width/2)),cy=Math.min(vh-1,Math.max(0,r.top+r.height/2));"
                + "var top=document.elementFromPoint(cx,cy);if(top&&top!==e&&!e.contains(top)&&!(top.contains&&top.contains(e)))s+=' (covered)'}"
                + "if(dlg&&dlg.contains(e))s+=' (in dialog)';out.push(s)}"
                + "var sc=document.scrollingElement||document.body;"
                + "return {title:document.title,url:location.href,dialog_open:!!dlg,scroll:Math.round(sc.scrollTop)+'/'+Math.round(sc.scrollHeight-sc.clientHeight),"
                + "more_below:below,elements:out.join('\\n')};";
    }

    /** JS that finds element number {@code index} (from the last list), scrolls it into view and returns its centre. */
    public static String locateJs(int index) {
        return "var e=(window.__yfEls||[])[" + index + "]||document.querySelector('[data-yf=\"" + index + "\"]');"
                + "if(!e||!e.isConnected)return null;e.scrollIntoView({block:'center',inline:'center'});"
                + "await new Promise(function(r){setTimeout(r,300)});var r=e.getBoundingClientRect();"
                + "return {x:r.left+r.width/2,y:r.top+r.height/2,vw:window.innerWidth,"
                + "what:((e.getAttribute('aria-label')||e.innerText||e.title||e.placeholder||e.tagName)+'').trim().replace(/\\s+/g,' ').slice(0,60)};";
    }

    /** Picks an option of a native &lt;select&gt; (by its text or value) and fires the page's change events. */
    public static String selectJs(int index, String option) {
        return "var e=(window.__yfEls||[])[" + index + "]||document.querySelector('[data-yf=\"" + index + "\"]');"
                + "if(!e)return 'ERROR: no element " + index + " (list elements again)';if(e.tagName!=='SELECT')return 'ERROR: element " + index
                + " is not a dropdown (<select>); tap it and then tap the option instead';"
                + "var want=" + JSONObject.quote(option == null ? "" : option) + ".trim().toLowerCase();var o=[].slice.call(e.options).find(function(o){"
                + "return o.text.trim().toLowerCase()===want||o.value.toLowerCase()===want})||[].slice.call(e.options).find(function(o){"
                + "return o.text.trim().toLowerCase().indexOf(want)>=0});if(!o)return 'ERROR: no option '+want+'. Options: '+[].slice.call(e.options).map(function(o){return o.text.trim()}).join(' | ');"
                + "var d=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value');d.set.call(e,o.value);"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'picked '+o.text.trim();";
    }

    /** Empties field number {@code index} (or the focused one) the way a user would, so typing replaces it. */
    public static String clearJs(int index) {
        return "var e=" + (index >= 0 ? "(window.__yfEls||[])[" + index + "]" : "document.activeElement") + ";if(!e)return 'ERROR: no field';"
                + "e.focus();if('value' in e&&e.tagName!=='SELECT'){var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(p,'value');d.set.call(e,'');e.dispatchEvent(new Event('input',{bubbles:true}));}"
                + "else if(e.isContentEditable){document.execCommand('selectAll');document.execCommand('delete');}return 'cleared';";
    }

    // ------------------------------------------------------------------ uploads

    /**
     * Copies the files the agent wants to upload to a place the page can read and returns their
     * content:// addresses. Paths are workspace paths or "ext:" paths of a granted folder.
     */
    public static Uri[] prepareUploads(Context ctx, com.suvo.agent.AppGraph g, JSONArray paths) throws Exception {
        if (paths == null || paths.length() == 0) throw new IllegalArgumentException("Give files: workspace paths, e.g. [\"photos/a.jpg\"]");
        com.suvo.agent.files.FileAccess fa = new com.suvo.agent.files.FileAccess(g.workspace, g.saf);
        File dir = g.workspace.dir("uploads");
        Uri[] out = new Uri[paths.length()];
        for (int i = 0; i < paths.length(); i++) {
            String p = paths.optString(i).trim();
            File f;
            if (p.startsWith("ext:")) {
                String name = p.substring(p.lastIndexOf('/') + 1).replace("ext:", "");
                f = new File(dir, name.isEmpty() ? "file" + i : name);
                try (InputStream in = fa.open(p); OutputStream os = new java.io.FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            } else {
                f = g.workspace.resolve(p);
                if (!f.isFile()) throw new java.io.FileNotFoundException("No file " + p + " in the workspace");
            }
            out[i] = com.suvo.agent.files.WorkspaceProvider.uriFor(ctx, f);
        }
        return out;
    }

    /**
     * Puts workspace files straight into a file input of the page (DataTransfer), for pages where
     * no button opens the picker. index = the input's number, or -1 for the first file input.
     */
    public static String injectFilesJs(int index, JSONArray files) {
        // files: [{name, type, b64}]
        return "var fs=" + files + ";var e=" + (index >= 0 ? "(window.__yfEls||[])[" + index + "]" : "null") + ";"
                + "if(!e||e.type!=='file'){var all=[];(function w(r){r.querySelectorAll('*').forEach(function(x){if(x.shadowRoot)w(x.shadowRoot);"
                + "if(x.tagName==='INPUT'&&x.type==='file')all.push(x)})})(document);var d=document.querySelector('dialog[open],[role=dialog]');"
                + "e=(d&&all.find(function(x){return d.contains(x)}))||all[all.length-1]}"
                + "if(!e)return 'ERROR: the page has no file input';var dt=new DataTransfer();fs.forEach(function(f){var bin=atob(f.b64),a=new Uint8Array(bin.length);"
                + "for(var i=0;i<bin.length;i++)a[i]=bin.charCodeAt(i);dt.items.add(new File([a],f.name,{type:f.type}))});"
                + "if(!e.multiple&&dt.files.length>1){var one=new DataTransfer();one.items.add(dt.files[0]);dt=one}"
                + "e.files=dt.files;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'put '+e.files.length+' file(s) into the page\\'s file input';";
    }

    // ------------------------------------------------------------------ downloads

    /** Saves a download of the page into the workspace (downloads/), with the page's cookies. Background thread. */
    static void download(AgentWebView v, String url, String userAgent, String disposition, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            v.event("download of a " + (url == null ? "?" : url.substring(0, Math.min(12, url.length()))) + "… address can't be saved directly"
                    + " (blob/data): read it with js instead");
            return;
        }
        new Thread(() -> {
            try {
                com.suvo.agent.AppGraph g = com.suvo.agent.AppGraph.get();
                String name = URLUtil.guessFileName(url, disposition, mime);
                File dir = g.workspace.dir("downloads");
                File f = new File(dir, name);
                for (int i = 1; f.exists(); i++) {
                    int dot = name.lastIndexOf('.');
                    f = new File(dir, dot > 0 ? name.substring(0, dot) + "_" + i + name.substring(dot) : name + "_" + i);
                }
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
                c.setConnectTimeout(20_000);
                c.setReadTimeout(60_000);
                long total = 0;
                try (InputStream in = c.getInputStream(); OutputStream os = new java.io.FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                        if (total > 500L * 1024 * 1024) throw new java.io.IOException("larger than 500 MB");
                    }
                }
                v.event("downloaded " + name + " (" + (total / 1024) + " KB) to " + g.workspace.relative(f));
            } catch (Exception e) {
                v.event("download failed: " + e.getMessage());
            }
        }, "yf-download").start();
    }
}
