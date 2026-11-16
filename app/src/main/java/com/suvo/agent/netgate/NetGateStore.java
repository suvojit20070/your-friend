package com.suvo.agent.netgate;

import android.content.Context;

import com.suvo.agent.data.SecretStore;
import com.suvo.agent.data.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Net Gate's settings: on/off, which apps, the mode ("only these apps have internet" or "these
 * apps have no internet"), and the user's proxies (kept encrypted — they hold passwords).
 * Your Friend itself is never in the list: its internet is never blocked.
 */
public final class NetGateStore {

    public static final String ON = "netgate_on";
    /** "allow": only the chosen apps have internet · "block": the chosen apps have none. */
    public static final String MODE = "netgate_mode";
    public static final String APPS = "netgate_apps";
    public static final String PROXY_ON = "netgate_proxy_on";
    public static final String ROTATE = "netgate_proxy_rotate";
    private static final String PROXIES = "netgate_proxies_enc";

    public static final String ALLOW = "allow", BLOCK = "block";

    /** One proxy the user added. */
    public static final class Proxy {
        public String type = "socks5"; // socks5 | http
        public String host = "";
        public int port = 1080;
        public String user = "", pass = "";

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("type", type).put("host", host).put("port", port).put("user", user).put("pass", pass);
        }

        static Proxy from(JSONObject o) {
            Proxy p = new Proxy();
            p.type = "http".equals(o.optString("type")) ? "http" : "socks5";
            p.host = o.optString("host", "").trim();
            p.port = o.optInt("port", 1080);
            p.user = o.optString("user", "");
            p.pass = o.optString("pass", "");
            return p;
        }

        public boolean valid() {
            return !host.isEmpty() && port > 0 && port < 65536;
        }

        public String label() {
            return (type.equals("http") ? "HTTP" : "SOCKS5") + " · " + host + ":" + port + (user.isEmpty() ? "" : " · " + user);
        }
    }

    private final Settings s;
    private final Context ctx;

    public NetGateStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.s = Settings.get(this.ctx);
    }

    public boolean on() {
        return s.bool(ON, false);
    }

    public void setOn(boolean v) {
        s.put(ON, v);
    }

    public String mode() {
        return BLOCK.equals(s.str(MODE, ALLOW)) ? BLOCK : ALLOW;
    }

    public Set<String> apps() {
        Set<String> out = new LinkedHashSet<>();
        for (String p : s.str(APPS, "").split(",")) {
            String t = p.trim();
            if (!t.isEmpty() && !t.equals(ctx.getPackageName())) out.add(t);
        }
        return out;
    }

    public void setApps(Set<String> apps) {
        Set<String> clean = new LinkedHashSet<>(apps);
        clean.remove(ctx.getPackageName()); // never: Your Friend always has internet
        s.put(APPS, String.join(",", clean));
    }

    public boolean proxyOn() {
        return s.bool(PROXY_ON, false);
    }

    public boolean rotate() {
        return s.bool(ROTATE, true);
    }

    public List<Proxy> proxies() {
        List<Proxy> out = new ArrayList<>();
        String enc = s.str(PROXIES, "");
        if (enc.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(SecretStore.get().decrypt(enc));
            for (int i = 0; i < a.length(); i++) {
                Proxy p = Proxy.from(a.getJSONObject(i));
                if (p.valid()) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setProxies(List<Proxy> list) {
        try {
            JSONArray a = new JSONArray();
            for (Proxy p : list) a.put(p.json());
            s.put(PROXIES, list.isEmpty() ? "" : SecretStore.get().encrypt(a.toString()));
        } catch (org.json.JSONException ignored) {
        }
    }

    /** Proxy mode is in use: switched on, with at least one proxy, and the engine in this APK. */
    public boolean useProxy() {
        return proxyOn() && !proxies().isEmpty() && hev.htproxy.TProxyService.available() && android.os.Build.VERSION.SDK_INT >= 29;
    }

    /** Parses "host:port" or "user:pass@host:port" (a quick way to paste one). */
    public static Proxy parse(String type, String line) {
        Proxy p = new Proxy();
        p.type = "http".equals(type) ? "http" : "socks5";
        String t = line.trim();
        int at = t.lastIndexOf('@');
        if (at > 0) {
            String cred = t.substring(0, at);
            t = t.substring(at + 1);
            int c = cred.indexOf(':');
            p.user = c < 0 ? cred : cred.substring(0, c);
            p.pass = c < 0 ? "" : cred.substring(c + 1);
        }
        int c = t.lastIndexOf(':');
        if (c > 0) {
            p.host = t.substring(0, c).trim();
            try {
                p.port = Integer.parseInt(t.substring(c + 1).trim());
            } catch (NumberFormatException e) {
                p.port = -1;
            }
        } else {
            p.host = t;
        }
        return p;
    }

    /** Checks a proxy (opens example.com through it; network, so not on the main thread). Null = it works. */
    public static String test(Proxy p) {
        return LocalSocks.test(p);
    }

    static List<String> list(String csv) {
        return Arrays.asList(csv.split(","));
    }
}
