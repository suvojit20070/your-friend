package com.suvo.agent.plugins;

import android.webkit.CookieManager;

import com.suvo.agent.AppGraph;

import java.util.Arrays;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Native connector plugins: the agent gets a plugin's tool only after the user installs it
 * (Settings → Plugins) — so it only sees what this user needs. Instagram, YouTube and Facebook use
 * the login of the agent's browser (the user logs in there once) and call the sites' own internal
 * APIs directly; WhatsApp links the user's number as a device through a bridge that runs in Termux.
 */
public final class Plugins {

    private Plugins() {
    }

    /** One plugin as the Plugins screen shows it. */
    public static final class Info {
        public final String id, name, description, loginUrl, cookieOrigin, loginCookie;

        Info(String id, String name, String description, String loginUrl, String cookieOrigin, String loginCookie) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.loginUrl = loginUrl;
            this.cookieOrigin = cookieOrigin;
            this.loginCookie = loginCookie;
        }
    }

    public static final Info INSTAGRAM = new Info("instagram", "Instagram",
            "Post photos, read profiles and posts, comment, like, follow, direct messages — as your account.",
            "https://www.instagram.com/accounts/login/", "https://www.instagram.com", "sessionid");
    public static final Info YOUTUBE = new Info("youtube", "YouTube",
            "Search, video details, comments (read and write), like, subscribe, your feeds — as your account.",
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/", "https://www.youtube.com", "SAPISID");
    public static final Info FACEBOOK = new Info("facebook", "Facebook",
            "Uses your Facebook login in the agent's browser; the agent learns the actions it needs once and repeats them directly.",
            "https://www.facebook.com/login/", "https://www.facebook.com", "c_user");
    public static final Info WHATSAPP = new Info("whatsapp", "WhatsApp",
            "Your WhatsApp number as a linked device: read chats, send messages and files. Runs in Termux.",
            "", "", "");

    public static List<Info> all() {
        return Arrays.asList(INSTAGRAM, YOUTUBE, FACEBOOK, WHATSAPP);
    }

    public static String key(Info p) {
        return "plugin_" + p.id;
    }

    public static boolean installed(AppGraph g, Info p) {
        return g.settings.bool(key(p), false);
    }

    /** Whether the browser has the site's login cookie (WhatsApp: whether the bridge is linked is checked live). */
    public static boolean loggedIn(Info p) {
        if (p.cookieOrigin.isEmpty()) return false;
        String all = CookieManager.getInstance().getCookie(p.cookieOrigin);
        if (all == null) return false;
        for (String kv : all.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).trim().equals(p.loginCookie) && kv.length() > eq + 2) return true;
        }
        return false;
    }

    /** The reason a plugin's tool is not offered (not installed), or null. */
    public static String unavailable(AppGraph g, Info p) {
        return installed(g, p) ? null : p.name + " plugin is not installed (Settings → Plugins)";
    }

    /** Null when logged in; otherwise what to tell the user (the tool is still offered, so the agent can say it). */
    public static String needLogin(Info p) {
        if (p.cookieOrigin.isEmpty() || loggedIn(p)) return null;
        return "Not logged in to " + p.name + ". Ask the user to open Settings → Plugins → " + p.name
                + " → Log in (once, in the agent's browser), or open " + p.loginUrl + " with the browser tool and let them log in.";
    }

    /** What to tell when a site says the session ended. */
    public static String needLoginOr(Info p) {
        String n = needLogin(p);
        return n != null ? n : "log in again: Settings → Plugins → " + p.name + " → Log in";
    }
}
