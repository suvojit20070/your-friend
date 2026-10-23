package com.suvo.agent.core.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Guards web tools against being pointed at the phone itself or the local network (a classic way
 * for a malicious page to make an agent reach routers or local admin panels). LAN access can be
 * allowed explicitly in Privacy settings.
 */
public final class UrlSafety {

    private UrlSafety() {
    }

    /** Returns null if the URL may be fetched, otherwise the reason. Resolves DNS. */
    public static String check(String url, boolean allowLan) {
        URI u;
        try {
            u = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "Invalid URL";
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "Only http and https URLs are allowed";
        String host = u.getHost();
        if (host == null || host.isEmpty()) return "URL has no host";
        if (u.getUserInfo() != null) return "URLs with embedded credentials are not allowed";
        if (allowLan) return null;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return "Local addresses are blocked (enable LAN access in Privacy settings to allow)";
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (isPrivate(a)) return "The host resolves to a private/local address, which is blocked (enable LAN access in Privacy settings to allow)";
            }
        } catch (UnknownHostException e) {
            return "Unknown host: " + host;
        }
        return null;
    }

    public static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // CGNAT
            if (b0 == 0) return true;
        } else if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true; // unique local fc00::/7
        }
        return false;
    }
}
