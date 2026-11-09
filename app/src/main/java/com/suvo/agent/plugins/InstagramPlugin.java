package com.suvo.agent.plugins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.tools.BaseTool;
import com.suvo.agent.tools.J;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Your Friend — Original project by Suvo.
 *
 * instagram: the user's Instagram account through Instagram's own web API, with the login of the
 * agent's browser (no password stored, requests from this phone). Instagram can change these
 * internal endpoints; the api action reaches any endpoint (find them with the browser's network
 * action) so the agent is never stuck.
 */
public final class InstagramPlugin extends BaseTool {

    private static final String WEB = "https://www.instagram.com";
    private static final String APP_ID = "936619743392459";
    private final SiteHttp http;

    public InstagramPlugin(AppGraph g) {
        super(g, ToolSpec.builder("instagram")
                .description("The user's Instagram account, directly (no browser clicking). Actions: me; profile (username); posts "
                        + "(username, count); post (url or shortcode or media_id: details); comments (post); comment (post, text); "
                        + "like / unlike (post); follow / unfollow (username); search (query: users, hashtags, places); "
                        + "post_photo (files: 1–10 workspace image paths → one photo or a carousel, caption); inbox (direct "
                        + "messages, limit); thread (thread_id: its messages); send_dm (username or thread_id, text); api (method, "
                        + "path like /api/v1/…, form or json: any Instagram web endpoint, e.g. one seen with the browser's network "
                        + "action). Videos / reels: upload with the browser tool (upload action). Act like a person: no mass "
                        + "following, liking or messaging — Instagram restricts accounts that do.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"me\",\"profile\",\"posts\",\"post\",\"comments\",\"comment\",\"like\","
                        + "\"unlike\",\"follow\",\"unfollow\",\"search\",\"post_photo\",\"inbox\",\"thread\",\"send_dm\",\"api\"]},"
                        + "\"username\":{\"type\":\"string\"},"
                        + "\"post\":{\"type\":\"string\",\"description\":\"post url, shortcode or media id\"},"
                        + "\"text\":{\"type\":\"string\"},"
                        + "\"caption\":{\"type\":\"string\"},"
                        + "\"files\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + "\"query\":{\"type\":\"string\"},"
                        + "\"thread_id\":{\"type\":\"string\"},"
                        + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":50,\"default\":12},"
                        + "\"method\":{\"type\":\"string\",\"default\":\"GET\"},"
                        + "\"path\":{\"type\":\"string\"},"
                        + "\"form\":{\"type\":\"object\"},"
                        + "\"json\":{\"type\":\"object\"}"
                        + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                .risk(RiskLevel.MEDIUM).backend("plugin").timeoutMs(180_000).build());
        http = new SiteHttp(g.ctx, WEB, 1200);
    }

    @Override
    public String unavailableReason() {
        return Plugins.unavailable(g, Plugins.INSTAGRAM);
    }

    @Override
    public RiskLevel riskFor(JSONObject a) {
        switch (a.optString("action")) {
            case "me":
            case "profile":
            case "posts":
            case "post":
            case "comments":
            case "search":
            case "inbox":
            case "thread":
                return RiskLevel.READ_ONLY;
            case "api":
                return "GET".equalsIgnoreCase(a.optString("method", "GET")) ? RiskLevel.READ_ONLY : RiskLevel.HIGH;
            case "post_photo":
            case "send_dm":
                return RiskLevel.HIGH;
            default:
                return RiskLevel.MEDIUM;
        }
    }

    @Override
    public String describeCall(JSONObject a) {
        String act = a.optString("action");
        switch (act) {
            case "post_photo":
                return "Instagram: post " + a.optJSONArray("files") + " — " + a.optString("caption");
            case "send_dm":
                return "Instagram DM to " + a.optString("username", a.optString("thread_id")) + ": " + a.optString("text");
            case "comment":
                return "Instagram comment on " + a.optString("post") + ": " + a.optString("text");
            default:
                return "Instagram " + act + " " + a.optString("username", a.optString("post", a.optString("query", a.optString("path"))));
        }
    }

    private Map<String, String> headers() {
        String csrf = http.cookie("csrftoken");
        return SiteHttp.map("X-IG-App-ID", APP_ID, "X-CSRFToken", csrf == null ? "" : csrf, "X-Requested-With", "XMLHttpRequest",
                "X-ASBD-ID", "129477", "X-Instagram-AJAX", "1", "Referer", WEB + "/", "Accept", "*/*");
    }

    private JSONObject json(SiteHttp.Response r, String what) throws Exception {
        if (r.status == 401 || r.status == 403 || r.body.contains("\"require_login\":true") || r.body.contains("login_required")) {
            throw new IllegalStateException("Instagram says you are logged out — " + Plugins.needLoginOr(Plugins.INSTAGRAM));
        }
        if (r.body.contains("checkpoint_required") || r.body.contains("challenge_required")) {
            throw new IllegalStateException("Instagram wants a security check: open instagram.com in the browser tool so the user can confirm it");
        }
        if (r.status == 429 || r.body.contains("Please wait a few minutes")) {
            throw new IllegalStateException("Instagram is limiting requests right now — stop for a while (do not retry at once)");
        }
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (Exception e) {
            throw new IllegalStateException(what + " failed (HTTP " + r.status + "): " + abbreviate(r.body, 300));
        }
        if (!r.ok() || "fail".equals(o.optString("status"))) {
            throw new IllegalStateException(what + " failed (HTTP " + r.status + "): " + o.optString("message", abbreviate(r.body, 300)));
        }
        return o;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String login = Plugins.needLogin(Plugins.INSTAGRAM);
        if (login != null) return ToolResult.fail(login);
        String a = inv.str("action");
        int count = (int) inv.num("count", 12);
        try {
            switch (a) {
                case "me": {
                    String uid = http.cookie("ds_user_id");
                    try {
                        JSONObject u = json(http.get(WEB + "/api/v1/users/" + uid + "/info/", headers()), "me").optJSONObject("user");
                        return ToolResult.ok("Logged in as @" + (u == null ? "?" : u.optString("username")), J.obj("user", user(u)));
                    } catch (IllegalStateException e) {
                        // Another way to the own username (the edit-profile form).
                        JSONObject f = json(http.get(WEB + "/api/v1/accounts/edit/web_form_data/", headers()), "me").optJSONObject("form_data");
                        String name = f == null ? "" : f.optString("username");
                        return ToolResult.ok("Logged in as @" + name, J.obj("user", name.isEmpty() ? J.obj("id", uid) : profile(name)));
                    }
                }
                case "profile": {
                    JSONObject u = profile(need(inv, "username"));
                    return ToolResult.ok("@" + u.optString("username"), J.obj("profile", u));
                }
                case "posts": {
                    String uid = userId(need(inv, "username"));
                    JSONObject o = json(http.get(WEB + "/api/v1/feed/user/" + uid + "/?count=" + count, headers()), "posts");
                    return ToolResult.ok("Posts of @" + inv.str("username"), J.obj("posts", posts(o.optJSONArray("items"))));
                }
                case "post": {
                    JSONObject o = json(http.get(WEB + "/api/v1/media/" + mediaId(need(inv, "post")) + "/info/", headers()), "post");
                    JSONArray items = o.optJSONArray("items");
                    return ToolResult.ok("Post", J.obj("post", items == null || items.length() == 0 ? o : post(items.optJSONObject(0))));
                }
                case "comments": {
                    JSONObject o = json(http.get(WEB + "/api/v1/media/" + mediaId(need(inv, "post"))
                            + "/comments/?can_support_threading=true&permalink_enabled=false", headers()), "comments");
                    JSONArray out = new JSONArray();
                    JSONArray cs = o.optJSONArray("comments");
                    for (int i = 0; cs != null && i < cs.length() && i < 50; i++) {
                        JSONObject c = cs.optJSONObject(i);
                        JSONObject u = c.optJSONObject("user");
                        out.put(J.obj("id", c.optString("pk"), "user", u == null ? "" : u.optString("username"), "text", c.optString("text"),
                                "likes", c.optInt("comment_like_count")));
                    }
                    return ToolResult.ok(out.length() + " comment(s)", J.obj("comments", out));
                }
                case "comment": {
                    String id = mediaId(need(inv, "post"));
                    JSONObject o = json(http.postForm(WEB + "/api/v1/web/comments/" + id + "/add/", headers(),
                            SiteHttp.map("comment_text", need(inv, "text"))), "comment");
                    return ToolResult.ok("Commented", J.obj("comment_id", o.optString("id")));
                }
                case "like":
                case "unlike": {
                    String id = mediaId(need(inv, "post"));
                    json(http.postForm(WEB + "/api/v1/web/likes/" + id + "/" + a + "/", headers(), SiteHttp.map()), a);
                    return ToolResult.ok(a.equals("like") ? "Liked" : "Unliked");
                }
                case "follow":
                case "unfollow": {
                    String uid = userId(need(inv, "username"));
                    JSONObject o = json(http.postForm(WEB + "/api/v1/friendships/" + (a.equals("follow") ? "create" : "destroy") + "/" + uid + "/",
                            headers(), SiteHttp.map("container_module", "profile", "user_id", uid)), a);
                    return ToolResult.ok((a.equals("follow") ? "Followed @" : "Unfollowed @") + inv.str("username"),
                            J.obj("friendship", o.optJSONObject("friendship_status")));
                }
                case "search": {
                    JSONObject o = json(http.get(WEB + "/api/v1/web/search/topsearch/?query=" + URLEncoder.encode(need(inv, "query"), "UTF-8"),
                            headers()), "search");
                    JSONArray users = new JSONArray(), tags = new JSONArray(), places = new JSONArray();
                    JSONArray us = o.optJSONArray("users");
                    for (int i = 0; us != null && i < us.length() && i < 15; i++) {
                        JSONObject u = us.optJSONObject(i).optJSONObject("user");
                        if (u != null) users.put(J.obj("username", u.optString("username"), "name", u.optString("full_name"),
                                "verified", u.optBoolean("is_verified"), "private", u.optBoolean("is_private")));
                    }
                    JSONArray hs = o.optJSONArray("hashtags");
                    for (int i = 0; hs != null && i < hs.length() && i < 10; i++) {
                        JSONObject h = hs.optJSONObject(i).optJSONObject("hashtag");
                        if (h != null) tags.put(J.obj("tag", h.optString("name"), "posts", h.optLong("media_count")));
                    }
                    JSONArray ps = o.optJSONArray("places");
                    for (int i = 0; ps != null && i < ps.length() && i < 8; i++) {
                        JSONObject pl = ps.optJSONObject(i).optJSONObject("place");
                        if (pl != null) places.put(J.obj("title", pl.optString("title"), "subtitle", pl.optString("subtitle")));
                    }
                    return ToolResult.ok("Search results", J.obj("users", users, "hashtags", tags, "places", places));
                }
                case "post_photo":
                    return postPhotos(inv);
                case "inbox": {
                    JSONObject o = json(http.get(WEB + "/api/v1/direct_v2/inbox/?persistentBadging=true&folder=&limit=" + count
                            + "&thread_message_limit=1", headers()), "inbox");
                    JSONObject inbox = o.optJSONObject("inbox");
                    JSONArray threads = inbox == null ? null : inbox.optJSONArray("threads");
                    JSONArray out = new JSONArray();
                    for (int i = 0; threads != null && i < threads.length(); i++) {
                        JSONObject t = threads.optJSONObject(i);
                        JSONArray users = t.optJSONArray("users");
                        StringBuilder who = new StringBuilder();
                        for (int k = 0; users != null && k < users.length(); k++) {
                            if (who.length() > 0) who.append(", ");
                            who.append('@').append(users.optJSONObject(k).optString("username"));
                        }
                        JSONArray items = t.optJSONArray("items");
                        JSONObject last = items == null || items.length() == 0 ? null : items.optJSONObject(0);
                        out.put(J.obj("thread_id", t.optString("thread_id"), "title", t.optString("thread_title"), "with", who.toString(),
                                "unread", t.optLong("read_state") == 1 || t.has("has_newer") && t.optBoolean("has_newer"),
                                "last", last == null ? "" : messageText(last)));
                    }
                    return ToolResult.ok(out.length() + " chat(s)", J.obj("threads", out));
                }
                case "thread": {
                    JSONObject o = json(http.get(WEB + "/api/v1/direct_v2/threads/" + need(inv, "thread_id") + "/?limit=" + count, headers()), "thread");
                    JSONObject t = o.optJSONObject("thread");
                    JSONArray items = t == null ? null : t.optJSONArray("items");
                    String me = http.cookie("ds_user_id");
                    JSONObject names = new JSONObject();
                    JSONArray users = t == null ? null : t.optJSONArray("users");
                    for (int k = 0; users != null && k < users.length(); k++) {
                        names.put(users.optJSONObject(k).optString("pk"), users.optJSONObject(k).optString("username"));
                    }
                    JSONArray out = new JSONArray();
                    for (int i = items == null ? -1 : items.length() - 1; i >= 0; i--) {
                        JSONObject it = items.optJSONObject(i);
                        String from = it.optString("user_id");
                        out.put(J.obj("from", from.equals(me) ? "me" : "@" + names.optString(from, from), "text", messageText(it),
                                "time", it.optLong("timestamp") / 1000));
                    }
                    return ToolResult.ok(out.length() + " message(s)", J.obj("messages", out));
                }
                case "send_dm": {
                    String text = need(inv, "text");
                    Map<String, String> f = SiteHttp.map("action", "send_item", "client_context", UUID.randomUUID().toString(),
                            "mutation_token", UUID.randomUUID().toString(), "text", text, "offline_threading_id", String.valueOf(System.nanoTime()));
                    if (!inv.str("thread_id").isEmpty()) f.put("thread_ids", "[" + inv.str("thread_id") + "]");
                    else f.put("recipient_users", "[[" + userId(need(inv, "username")) + "]]");
                    JSONObject o = json(http.postForm(WEB + "/api/v1/direct_v2/threads/broadcast/text/", headers(), f), "send_dm");
                    JSONObject p = o.optJSONObject("payload");
                    return ToolResult.ok("Sent", J.obj("thread_id", p == null ? "" : p.optString("thread_id")));
                }
                case "api":
                    return api(inv);
                default:
                    return ToolResult.fail("Unknown action " + a);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String need(ToolInvocation inv, String k) {
        String v = inv.str(k).trim();
        if (v.isEmpty()) throw new IllegalArgumentException(k + " is required for this action");
        return v.startsWith("@") ? v.substring(1) : v;
    }

    private JSONObject profile(String username) throws Exception {
        JSONObject o = json(http.get(WEB + "/api/v1/users/web_profile_info/?username=" + URLEncoder.encode(username, "UTF-8"), headers()), "profile");
        JSONObject d = o.optJSONObject("data");
        JSONObject u = d == null ? null : d.optJSONObject("user");
        if (u == null) throw new IllegalStateException("No Instagram user @" + username);
        JSONObject e = u.optJSONObject("edge_followed_by"), f = u.optJSONObject("edge_follow"), m = u.optJSONObject("edge_owner_to_timeline_media");
        return J.obj("id", u.optString("id"), "username", u.optString("username"), "name", u.optString("full_name"), "bio", u.optString("biography"),
                "followers", e == null ? 0 : e.optLong("count"), "following", f == null ? 0 : f.optLong("count"),
                "posts", m == null ? 0 : m.optLong("count"), "private", u.optBoolean("is_private"), "verified", u.optBoolean("is_verified"),
                "you_follow", u.optBoolean("followed_by_viewer"), "follows_you", u.optBoolean("follows_viewer"), "link", u.optString("external_url"));
    }

    private String userId(String username) throws Exception {
        if (username.matches("\\d{5,}")) return username;
        return profile(username).optString("id");
    }

    private static JSONObject user(JSONObject u) {
        if (u == null) return new JSONObject();
        return J.obj("id", u.optString("pk"), "username", u.optString("username"), "name", u.optString("full_name"),
                "followers", u.optLong("follower_count"), "following", u.optLong("following_count"), "posts", u.optLong("media_count"),
                "private", u.optBoolean("is_private"));
    }

    private static JSONArray posts(JSONArray items) {
        JSONArray out = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) out.put(post(items.optJSONObject(i)));
        return out;
    }

    private static JSONObject post(JSONObject m) {
        if (m == null) return new JSONObject();
        JSONObject cap = m.optJSONObject("caption");
        int type = m.optInt("media_type");
        return J.obj("media_id", m.optString("pk"), "url", WEB + "/p/" + m.optString("code") + "/",
                "type", type == 1 ? "photo" : type == 2 ? "video" : type == 8 ? "carousel" : String.valueOf(type),
                "caption", cap == null ? "" : abbreviate(cap.optString("text"), 500), "likes", m.optLong("like_count"),
                "comments", m.optLong("comment_count"), "time", m.optLong("taken_at"));
    }

    private static String messageText(JSONObject it) {
        String type = it.optString("item_type");
        if ("text".equals(type)) return it.optString("text");
        if ("link".equals(type)) {
            JSONObject l = it.optJSONObject("link");
            return l == null ? "[link]" : l.optString("text");
        }
        return "[" + type + "]";
    }

    /** A post url / shortcode / numeric id → the numeric media id. */
    static String mediaId(String post) {
        String p = post.trim();
        if (p.matches("\\d+(_\\d+)?")) return p.contains("_") ? p.substring(0, p.indexOf('_')) : p;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(p);
        String code = m.find() ? m.group(1) : p;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        BigInteger id = BigInteger.ZERO;
        for (char c : code.toCharArray()) {
            int v = alphabet.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not an Instagram post: " + post);
            id = id.shiftLeft(6).add(BigInteger.valueOf(v));
        }
        return id.toString();
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ posting

    private ToolResult postPhotos(ToolInvocation inv) throws Exception {
        JSONArray files = inv.args.optJSONArray("files");
        if (files == null || files.length() == 0) return ToolResult.fail("files (workspace image paths) are required");
        if (files.length() > 10) return ToolResult.fail("At most 10 photos in one post");
        String caption = inv.str("caption");
        JSONArray ids = new JSONArray();
        for (int i = 0; i < files.length(); i++) {
            inv.progress.update("Uploading photo " + (i + 1) + "/" + files.length());
            ids.put(uploadPhoto(g.workspace.resolve(files.optString(i))));
        }
        JSONObject o;
        if (ids.length() == 1) {
            o = json(http.postForm(WEB + "/api/v1/media/configure/", headers(), SiteHttp.map("source_type", "library", "caption", caption,
                    "upload_id", ids.optString(0), "disable_comments", "0", "like_and_view_counts_disabled", "0",
                    "igtv_share_preview_to_feed", "1", "is_unified_video", "1", "video_subtitles_enabled", "0", "disable_oa_reuse", "false")),
                    "post");
        } else {
            JSONArray children = new JSONArray();
            for (int i = 0; i < ids.length(); i++) children.put(J.obj("upload_id", ids.optString(i)));
            JSONObject body = J.obj("archive_only", false, "caption", caption, "children_metadata", children,
                    "client_sidecar_id", String.valueOf(System.currentTimeMillis()), "disable_comments", "0",
                    "like_and_view_counts_disabled", false, "source_type", "library");
            o = json(http.postJson(WEB + "/api/v1/media/configure_sidecar/", headers(), body.toString()), "carousel post");
        }
        JSONObject media = o.optJSONObject("media");
        String url = media == null ? "" : WEB + "/p/" + media.optString("code") + "/";
        return ToolResult.ok("Posted" + (url.isEmpty() ? "" : ": " + url), J.obj("url", url, "media_id", media == null ? "" : media.optString("pk")));
    }

    /** Uploads one photo (as a JPEG in an aspect ratio Instagram accepts) and returns its upload id. */
    private String uploadPhoto(File f) throws Exception {
        if (!f.isFile()) throw new IllegalArgumentException("No file " + f.getName() + " in the workspace");
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        int sample = 1;
        while (b.outWidth / sample > 2160 || b.outHeight / sample > 2700) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp == null) throw new IllegalArgumentException(f.getName() + " is not an image");
        // Instagram takes 4:5 (tall) … 1.91:1 (wide): crop the middle when the photo is outside that.
        float r = bmp.getWidth() / (float) bmp.getHeight();
        if (r < 0.8f) {
            int h = Math.round(bmp.getWidth() / 0.8f);
            bmp = Bitmap.createBitmap(bmp, 0, (bmp.getHeight() - h) / 2, bmp.getWidth(), h);
        } else if (r > 1.91f) {
            int w = Math.round(bmp.getHeight() * 1.91f);
            bmp = Bitmap.createBitmap(bmp, (bmp.getWidth() - w) / 2, 0, w, bmp.getHeight());
        }
        if (bmp.getWidth() > 1440) bmp = Bitmap.createScaledBitmap(bmp, 1440, Math.round(1440f * bmp.getHeight() / bmp.getWidth()), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, bo);
        byte[] jpg = bo.toByteArray();
        String id = String.valueOf(System.currentTimeMillis());
        String name = "fb_uploader_" + id;
        Map<String, String> h = headers();
        h.put("X-Instagram-Rupload-Params", J.obj("media_type", 1, "upload_id", id, "upload_media_height", bmp.getHeight(),
                "upload_media_width", bmp.getWidth()).toString());
        h.put("X-Entity-Name", name);
        h.put("X-Entity-Length", String.valueOf(jpg.length));
        h.put("X-Entity-Type", "image/jpeg");
        h.put("Offset", "0");
        SiteHttp.Response res = http.send("POST", WEB + "/rupload_igphoto/" + name, h, jpg, "image/jpeg");
        JSONObject j = json(res, "photo upload");
        return j.optString("upload_id", id);
    }

    // ------------------------------------------------------------------ any endpoint

    private ToolResult api(ToolInvocation inv) throws Exception {
        String path = need(inv, "path");
        String url = path.startsWith("http") ? path : WEB + (path.startsWith("/") ? "" : "/") + path;
        if (!url.startsWith(WEB) && !url.startsWith("https://i.instagram.com")) return ToolResult.fail("Only instagram.com endpoints");
        String method = inv.str("method", "GET").toUpperCase(java.util.Locale.ROOT);
        SiteHttp.Response r;
        if (inv.args.has("json")) {
            r = http.send(method, url, headers(), inv.args.optJSONObject("json").toString().getBytes("UTF-8"), "application/json");
        } else if (inv.args.has("form")) {
            Map<String, String> f = SiteHttp.map();
            JSONObject form = inv.args.optJSONObject("form");
            for (Iterator<String> it = form.keys(); it.hasNext(); ) {
                String k = it.next();
                f.put(k, String.valueOf(form.opt(k)));
            }
            r = http.send(method, url, headers(), SiteHttp.form(f).getBytes("UTF-8"), "application/x-www-form-urlencoded");
        } else {
            r = http.send(method, url, headers(), null, null);
        }
        return (r.ok() ? ToolResult.ok("HTTP " + r.status, J.obj("body", abbreviate(r.body, 12000)))
                : ToolResult.fail("HTTP " + r.status + ": " + abbreviate(r.body, 1500)));
    }
}
