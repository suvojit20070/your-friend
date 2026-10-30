package com.suvo.agent.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * The services the user can connect, with everything already filled in (API addresses, sign-in
 * pages, mail servers, what the agent should know). "Custom" presets let the user connect anything
 * else.
 */
public final class ConnectorPresets {

    private ConnectorPresets() {
    }

    public static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";

    public static final class Preset {
        public final String id, title, type, group;
        /** One line under the title in the list. */
        public final String blurb;
        final Filler fill;

        Preset(String id, String title, String type, String group, String blurb, Filler fill) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.group = group;
            this.blurb = blurb;
            this.fill = fill;
        }

        /** A new connector of this kind with the preset's settings filled in. */
        public Connector create() {
            Connector c = new Connector();
            c.service = id;
            c.type = type;
            c.name = id.replace('_', '-');
            if (fill != null) fill.fill(c);
            return c;
        }
    }

    interface Filler {
        void fill(Connector c);
    }

    public static final String G_MAIL = "Mail", G_GOOGLE = "Google", G_SOCIAL = "Social & web", G_API = "APIs & bots";

    private static final List<Preset> ALL = new ArrayList<>();

    private static void add(String id, String title, String type, String group, String blurb, Filler f) {
        ALL.add(new Preset(id, title, type, group, blurb, f));
    }

    private static void google(Connector c, String scopes, String base, String hint) {
        c.authUrl = GOOGLE_AUTH;
        c.tokenUrl = GOOGLE_TOKEN;
        c.scopes = "openid email " + scopes;
        c.baseUrl = base;
        c.hosts = "googleapis.com";
        c.authStyle = "bearer";
        c.apiHint = hint;
    }

    private static void mail(Connector c, String imap, int imapPort, String smtp, int smtpPort, boolean ssl) {
        c.imapHost = imap;
        c.imapPort = imapPort;
        c.smtpHost = smtp;
        c.smtpPort = smtpPort;
        c.smtpSsl = ssl;
    }

    private static void web(Connector c, String home, String hosts, String hint) {
        c.homeUrl = home;
        c.hosts = hosts;
        c.apiHint = hint;
    }

    static {
        // ---- Mail (IMAP/SMTP with an app password: read, search, send, reply, move, delete)
        add("gmail", "Gmail", Connector.EMAIL, G_MAIL, "Read, search, send and organise mail (app password)",
                c -> mail(c, "imap.gmail.com", 993, "smtp.gmail.com", 465, true));
        add("outlook", "Outlook / Hotmail", Connector.EMAIL, G_MAIL, "IMAP + SMTP with an app password",
                c -> mail(c, "outlook.office365.com", 993, "smtp-mail.outlook.com", 587, false));
        add("yahoo", "Yahoo Mail", Connector.EMAIL, G_MAIL, "IMAP + SMTP with an app password",
                c -> mail(c, "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, true));
        add("custom_email", "Other mailbox", Connector.EMAIL, G_MAIL, "Any IMAP + SMTP server", null);

        // ---- Google APIs (sign in with Google; your own OAuth client)
        add("youtube", "YouTube", Connector.OAUTH, G_GOOGLE, "Your channel, playlists, likes, comments, subscriptions, search",
                c -> google(c, "https://www.googleapis.com/auth/youtube.force-ssl", "https://www.googleapis.com/youtube/v3/",
                        "YouTube Data API v3. Paths: search?part=snippet&q=…, videos?part=snippet,statistics&id=…, "
                                + "channels?part=snippet,statistics&mine=true, playlists?part=snippet&mine=true, "
                                + "playlistItems (POST to add), videos/rate?id=…&rating=like (POST), commentThreads (GET/POST), "
                                + "subscriptions?part=snippet&mine=true (POST to subscribe)."));
        add("gmail_api", "Gmail (Google sign-in)", Connector.OAUTH, G_GOOGLE, "Full Gmail API: labels, threads, drafts, filters, send",
                c -> google(c, "https://mail.google.com/", "https://gmail.googleapis.com/gmail/v1/users/me/",
                        "Gmail API. Paths: messages?q=… (search), messages/{id}?format=full, messages/send (POST "
                                + "{\"raw\": base64url RFC 822}), threads, labels, drafts, messages/{id}/modify, messages/{id}/trash."));
        add("google", "Google (all)", Connector.OAUTH, G_GOOGLE, "Gmail, YouTube, Drive, Calendar and Contacts in one sign-in",
                c -> google(c, "https://mail.google.com/ https://www.googleapis.com/auth/youtube.force-ssl "
                                + "https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/calendar "
                                + "https://www.googleapis.com/auth/contacts",
                        "https://www.googleapis.com/",
                        "Google APIs with one token. Use full URLs: https://gmail.googleapis.com/gmail/v1/users/me/…, "
                                + "https://www.googleapis.com/youtube/v3/…, https://www.googleapis.com/drive/v3/files…, "
                                + "https://www.googleapis.com/calendar/v3/calendars/primary/events…, "
                                + "https://people.googleapis.com/v1/people/me/connections?personFields=names,emailAddresses,phoneNumbers."));

        // ---- Social & websites (log in once inside the app; the agent uses that logged-in page)
        add("instagram", "Instagram", Connector.WEB, G_SOCIAL, "Log in once; the agent browses, reads DMs, posts, likes",
                c -> web(c, "https://www.instagram.com/", "instagram.com,cdninstagram.com",
                        "instagram.com while logged in. Pages: / (feed), /direct/inbox/, /<username>/, /explore/. "
                                + "Read pages with web_session text; act with js (click buttons by their text / aria-label)."));
        add("facebook", "Facebook", Connector.WEB, G_SOCIAL, "Log in once; feed, messages, pages",
                c -> web(c, "https://m.facebook.com/", "facebook.com,messenger.com,fbcdn.net", "Mobile Facebook while logged in."));
        add("x", "X (Twitter)", Connector.WEB, G_SOCIAL, "Log in once; timeline, posts, DMs",
                c -> web(c, "https://x.com/home", "x.com,twitter.com,twimg.com", "x.com while logged in."));
        add("youtube_web", "YouTube (website)", Connector.WEB, G_SOCIAL, "No setup: log in to m.youtube.com",
                c -> web(c, "https://m.youtube.com/", "youtube.com,google.com,googlevideo.com", "Mobile YouTube while logged in."));
        add("telegram_web", "Telegram (your account)", Connector.WEB, G_SOCIAL, "Telegram Web with your own account",
                c -> web(c, "https://web.telegram.org/k/", "telegram.org,web.telegram.org", "Telegram Web K while logged in (your own account)."));
        add("whatsapp_web", "WhatsApp Web", Connector.WEB, G_SOCIAL, "Link with your phone's WhatsApp (scan the QR)",
                c -> {
                    web(c, "https://web.whatsapp.com/", "whatsapp.com,whatsapp.net", "WhatsApp Web while linked.");
                    c.desktopSite = true;
                });
        add("linkedin", "LinkedIn", Connector.WEB, G_SOCIAL, "Log in once; feed, messages, profile",
                c -> web(c, "https://www.linkedin.com/feed/", "linkedin.com,licdn.com", "LinkedIn while logged in."));
        add("reddit", "Reddit", Connector.WEB, G_SOCIAL, "Log in once; feed, posts, comments",
                c -> web(c, "https://www.reddit.com/", "reddit.com,redd.it,redditmedia.com", "Reddit while logged in."));
        add("custom_web", "Any website", Connector.WEB, G_SOCIAL, "Log in to any site; the agent uses it as you", null);

        // ---- APIs & bots (keys and tokens)
        add("telegram_bot", "Telegram bot", Connector.TOKEN, G_API, "Bot token from @BotFather: messages, files, groups",
                c -> {
                    c.baseUrl = "https://api.telegram.org/bot{token}/";
                    c.hosts = "api.telegram.org";
                    c.authStyle = "path";
                    c.apiHint = "Telegram Bot API: call the method as the path (getMe, getUpdates, sendMessage with JSON "
                            + "{\"chat_id\":…,\"text\":…}, sendPhoto / sendDocument with files, getFile then file/bot{token}/<file_path>).";
                });
        add("github", "GitHub", Connector.TOKEN, G_API, "Personal access token: repos, issues, PRs",
                c -> {
                    c.baseUrl = "https://api.github.com/";
                    c.hosts = "api.github.com,uploads.github.com,raw.githubusercontent.com";
                    c.apiHint = "GitHub REST API (user, user/repos, repos/{owner}/{repo}/issues, search/repositories?q=…).";
                });
        add("notion", "Notion", Connector.TOKEN, G_API, "Integration token: pages and databases",
                c -> {
                    c.baseUrl = "https://api.notion.com/v1/";
                    c.hosts = "api.notion.com";
                    c.apiHint = "Notion API; send header Notion-Version: 2022-06-28 (search POST, pages, databases/{id}/query).";
                });
        add("custom_api", "Any API (key)", Connector.TOKEN, G_API, "Base URL + API key / bearer token", null);
        add("custom_oauth", "Any OAuth service", Connector.OAUTH, G_API, "Your own OAuth client (authorize + token URLs)", null);
    }

    public static List<Preset> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static Preset get(String id) {
        for (Preset p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    public static String title(String id) {
        Preset p = get(id);
        return p == null ? id : p.title;
    }

    public static boolean isGoogle(Connector c) {
        return GOOGLE_AUTH.equals(c.authUrl);
    }
}
