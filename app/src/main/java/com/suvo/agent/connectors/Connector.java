package com.suvo.agent.connectors;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your Friend — Original project by Suvo.
 *
 * One account the user connected for the agent: Gmail, YouTube, a Telegram bot, an Instagram
 * login, any API with a key, any website… Four kinds:
 * <ul>
 * <li>{@link #OAUTH} — sign in with the service in the browser (Google: Gmail, YouTube, Drive,
 * Calendar…); the agent calls the service's REST API with the access token, refreshed by itself.</li>
 * <li>{@link #TOKEN} — an API key / bot token (Telegram bot, GitHub, Notion, any REST API).</li>
 * <li>{@link #EMAIL} — a mailbox over IMAP + SMTP with an app password (Gmail, Outlook, Yahoo, any).</li>
 * <li>{@link #WEB} — a website the user logged in to inside the app (Instagram, Facebook, X,
 * WhatsApp Web, Telegram Web…); the agent uses that logged-in page.</li>
 * </ul>
 * Secrets (tokens, passwords, client secrets) are encrypted at rest by {@link ConnectorRepo}.
 */
public final class Connector {

    public static final String OAUTH = "oauth", TOKEN = "token", EMAIL = "email", WEB = "web";

    public String id = "";
    /** Preset id ("gmail", "youtube", "telegram_bot", "instagram", "custom_api"…). */
    public String service = "";
    public String type = TOKEN;
    /** Short name the agent and the user use ("gmail", "insta-main"); unique. */
    public String name = "";
    /** Account shown to the user (email, @username…), may be empty. */
    public String account = "";
    public long createdAt;

    // API (OAUTH, TOKEN)
    public String baseUrl = "";
    /** Hosts the agent may call with these credentials (comma separated; subdomains included). */
    public String hosts = "";
    /** "bearer", "header", "query", "path" (token is already in baseUrl) or "none". */
    public String authStyle = "bearer";
    /** Header or query parameter name for "header" / "query". */
    public String authName = "";
    public String token = "";

    // OAUTH
    public String clientId = "";
    public String clientSecret = "";
    public String authUrl = "";
    public String tokenUrl = "";
    public String scopes = "";
    public String refreshToken = "";
    /** When the access token runs out (epoch ms, 0 = unknown). */
    public long expiresAt;

    // EMAIL
    public String imapHost = "", smtpHost = "";
    public int imapPort = 993, smtpPort = 465;
    /** true: SMTP over SSL (465); false: STARTTLS (587). */
    public boolean smtpSsl = true;
    public String username = "";
    public String password = "";

    // WEB
    public String homeUrl = "";
    /** Ask for the desktop version of the site (WhatsApp Web needs it). */
    public boolean desktopSite;

    /** What the agent should know about this service's API (from the preset). */
    public String apiHint = "";

    public List<String> hostList() {
        List<String> out = new ArrayList<>();
        for (String h : hosts.split(",")) {
            String t = h.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Whether {@code host} is one of the allowed hosts or a subdomain of one. */
    public boolean allowsHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String a : hostList()) {
            if (h.equals(a) || h.endsWith("." + a)) return true;
        }
        return false;
    }

    public boolean isConnected() {
        switch (type) {
            case OAUTH:
                return !token.isEmpty() || !refreshToken.isEmpty();
            case EMAIL:
                return !username.isEmpty() && !password.isEmpty();
            case WEB:
                return !homeUrl.isEmpty();
            default:
                return !token.isEmpty() || "none".equals(authStyle);
        }
    }

    /** Plain fields (secrets are written by the repo, encrypted). */
    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id).put("service", service).put("type", type).put("name", name).put("account", account)
                .put("createdAt", createdAt).put("baseUrl", baseUrl).put("hosts", hosts).put("authStyle", authStyle)
                .put("authName", authName).put("clientId", clientId).put("authUrl", authUrl).put("tokenUrl", tokenUrl)
                .put("scopes", scopes).put("expiresAt", expiresAt).put("imapHost", imapHost).put("imapPort", imapPort)
                .put("smtpHost", smtpHost).put("smtpPort", smtpPort).put("smtpSsl", smtpSsl).put("username", username)
                .put("homeUrl", homeUrl).put("desktopSite", desktopSite).put("apiHint", apiHint);
        return o;
    }

    static Connector fromJson(JSONObject o) {
        Connector c = new Connector();
        c.id = o.optString("id");
        c.service = o.optString("service");
        c.type = o.optString("type", TOKEN);
        c.name = o.optString("name");
        c.account = o.optString("account");
        c.createdAt = o.optLong("createdAt");
        c.baseUrl = o.optString("baseUrl");
        c.hosts = o.optString("hosts");
        c.authStyle = o.optString("authStyle", "bearer");
        c.authName = o.optString("authName");
        c.clientId = o.optString("clientId");
        c.authUrl = o.optString("authUrl");
        c.tokenUrl = o.optString("tokenUrl");
        c.scopes = o.optString("scopes");
        c.expiresAt = o.optLong("expiresAt");
        c.imapHost = o.optString("imapHost");
        c.imapPort = o.optInt("imapPort", 993);
        c.smtpHost = o.optString("smtpHost");
        c.smtpPort = o.optInt("smtpPort", 465);
        c.smtpSsl = o.optBoolean("smtpSsl", true);
        c.username = o.optString("username");
        c.homeUrl = o.optString("homeUrl");
        c.desktopSite = o.optBoolean("desktopSite");
        c.apiHint = o.optString("apiHint");
        return c;
    }

    /** An independent copy (secrets included). */
    public Connector copy() {
        try {
            Connector c = fromJson(toJson());
            c.token = token;
            c.refreshToken = refreshToken;
            c.clientSecret = clientSecret;
            c.password = password;
            return c;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line for the agent: name, service, kind, account and how to use it. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" — ").append(ConnectorPresets.title(service));
        if (!account.isEmpty()) sb.append(" (").append(account).append(')');
        switch (type) {
            case EMAIL:
                sb.append(": mailbox, use the email tool");
                break;
            case WEB:
                sb.append(": logged-in website ").append(homeUrl).append(", use the web_session tool");
                break;
            default:
                sb.append(": API ").append(baseUrl).append(", use connector_api");
                break;
        }
        if (!isConnected()) sb.append(" [not signed in]");
        return sb.toString();
    }
}
