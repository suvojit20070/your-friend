package com.suvo.agent.plugins;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.tools.BaseTool;
import com.suvo.agent.tools.J;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * youtube: the user's YouTube account through YouTube's own web API (InnerTube, the one youtube.com
 * itself calls), signed in with the login of the agent's browser. Its answers are deep JSON trees;
 * this reads the useful parts by name wherever they sit, so small layout changes do not break it.
 */
public final class YouTubePlugin extends BaseTool {

    private static final String WEB = "https://www.youtube.com";
    private final SiteHttp http;
    private String clientVersion, visitorData;
    private long configAt;

    public YouTubePlugin(AppGraph g) {
        super(g, ToolSpec.builder("youtube")
                .description("The user's YouTube account, directly. Actions: search (query, count: videos and channels); video "
                        + "(video: id or url → title, channel, views, length, description); comments (video: top comments); "
                        + "comment (video, text: post a comment); like / dislike / unlike (video); subscribe / unsubscribe "
                        + "(channel: id, @handle or a video of it); channel (channel: name, subscribers, latest videos); feed "
                        + "(name: home, subscriptions, history, liked, watch_later); api (endpoint like \"browse\", json: any "
                        + "InnerTube call — the context is added for you). Uploading a video: use the browser tool on "
                        + "studio.youtube.com (upload action).")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"action\":{\"type\":\"string\",\"enum\":[\"search\",\"video\",\"comments\",\"comment\",\"like\",\"dislike\",\"unlike\","
                        + "\"subscribe\",\"unsubscribe\",\"channel\",\"feed\",\"api\"]},"
                        + "\"query\":{\"type\":\"string\"},"
                        + "\"video\":{\"type\":\"string\",\"description\":\"video id or url\"},"
                        + "\"channel\":{\"type\":\"string\",\"description\":\"channel id (UC…), @handle or url\"},"
                        + "\"text\":{\"type\":\"string\"},"
                        + "\"name\":{\"type\":\"string\",\"enum\":[\"home\",\"subscriptions\",\"history\",\"liked\",\"watch_later\"]},"
                        + "\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":50,\"default\":15},"
                        + "\"endpoint\":{\"type\":\"string\"},"
                        + "\"json\":{\"type\":\"object\"}"
                        + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                .risk(RiskLevel.MEDIUM).backend("plugin").timeoutMs(120_000).build());
        http = new SiteHttp(g.ctx, WEB, 700);
    }

    @Override
    public String unavailableReason() {
        return Plugins.unavailable(g, Plugins.YOUTUBE);
    }

    @Override
    public RiskLevel riskFor(JSONObject a) {
        switch (a.optString("action")) {
            case "search":
            case "video":
            case "comments":
            case "channel":
            case "feed":
                return RiskLevel.READ_ONLY;
            case "comment":
            case "api":
                return RiskLevel.HIGH;
            default:
                return RiskLevel.MEDIUM;
        }
    }

    @Override
    public String describeCall(JSONObject a) {
        String act = a.optString("action");
        if ("comment".equals(act)) return "YouTube comment on " + a.optString("video") + ": " + a.optString("text");
        return "YouTube " + act + " " + a.optString("query", a.optString("video", a.optString("channel", a.optString("name", a.optString("endpoint")))));
    }

    // ------------------------------------------------------------------ requests

    private void config() throws Exception {
        if (clientVersion != null && System.currentTimeMillis() - configAt < 6 * 3600_000L) return;
        SiteHttp.Response r = http.get(WEB + "/", SiteHttp.map("Accept", "text/html"));
        Matcher v = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").matcher(r.body);
        clientVersion = v.find() ? v.group(1) : "2.20250101.00.00";
        Matcher vd = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(r.body);
        visitorData = vd.find() ? vd.group(1) : null;
        configAt = System.currentTimeMillis();
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** The signed-in header youtube.com sends (SAPISIDHASH and its 1P / 3P twins). */
    private String authorization() throws Exception {
        long ts = System.currentTimeMillis() / 1000;
        StringBuilder sb = new StringBuilder();
        String[][] kinds = {{"SAPISIDHASH", "SAPISID"}, {"SAPISID1PHASH", "__Secure-1PAPISID"}, {"SAPISID3PHASH", "__Secure-3PAPISID"}};
        for (String[] k : kinds) {
            String v = http.cookie(k[1]);
            if (v == null && k[0].equals("SAPISIDHASH")) v = http.cookie("__Secure-3PAPISID");
            if (v == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(k[0]).append(' ').append(ts).append('_').append(sha1(ts + " " + v + " " + WEB));
        }
        return sb.toString();
    }

    private JSONObject call(String endpoint, JSONObject body) throws Exception {
        config();
        JSONObject client = J.obj("clientName", "WEB", "clientVersion", clientVersion, "hl", Locale.getDefault().getLanguage(),
                "gl", Locale.getDefault().getCountry().isEmpty() ? "US" : Locale.getDefault().getCountry());
        if (visitorData != null) client.put("visitorData", visitorData);
        body.put("context", J.obj("client", client));
        Map<String, String> h = SiteHttp.map("Authorization", authorization(), "X-Origin", WEB, "X-Goog-AuthUser", "0",
                "X-Youtube-Client-Name", "1", "X-Youtube-Client-Version", clientVersion, "Referer", WEB + "/");
        SiteHttp.Response r = http.postJson(WEB + "/youtubei/v1/" + endpoint + "?prettyPrint=false", h, body.toString());
        if (r.status == 401 || r.status == 403) throw new IllegalStateException("YouTube says you are not signed in — " + Plugins.needLoginOr(Plugins.YOUTUBE));
        if (!r.ok()) throw new IllegalStateException(endpoint + " failed (HTTP " + r.status + "): " + cut(r.body, 400));
        return new JSONObject(r.body);
    }

    // ------------------------------------------------------------------ tree reading

    /** Every object stored under {@code key} anywhere in the tree. */
    private static void find(Object node, String key, List<Object> out, int max) {
        if (out.size() >= max || node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                if (k.equals(key)) out.add(v);
                else find(v, key, out, max);
                if (out.size() >= max) return;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < max; i++) find(a.opt(i), key, out, max);
        }
    }

    private static List<Object> all(Object root, String key, int max) {
        List<Object> out = new ArrayList<>();
        find(root, key, out, max);
        return out;
    }

    private static Object first(Object root, String key) {
        List<Object> l = all(root, key, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Text of a YouTube text object ({simpleText} or {runs:[{text}]} or {content}). */
    private static String text(Object t) {
        if (t instanceof String) return (String) t;
        if (!(t instanceof JSONObject)) return "";
        JSONObject o = (JSONObject) t;
        if (o.has("simpleText")) return o.optString("simpleText");
        if (o.has("content")) return o.optString("content");
        JSONArray runs = o.optJSONArray("runs");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) sb.append(runs.optJSONObject(i).optString("text"));
        return sb.toString();
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String videoId(String v) {
        if (v == null) return "";
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(v);
        if (m.find()) return m.group(1);
        return v.trim();
    }

    /** Videos in any list / feed answer (old and new layouts). */
    private static JSONArray videos(Object root, int max) {
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String key : new String[]{"videoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "compactVideoRenderer", "reelItemRenderer"}) {
            for (Object o : all(root, key, max * 2)) {
                if (!(o instanceof JSONObject) || out.length() >= max) continue;
                JSONObject v = (JSONObject) o;
                String id = v.optString("videoId");
                if (id.isEmpty() || !seen.add(id)) continue;
                out.put(J.obj("video", id, "title", text(v.opt("title")).isEmpty() ? text(v.opt("headline")) : text(v.opt("title")),
                        "channel", text(v.has("ownerText") ? v.opt("ownerText") : v.opt("shortBylineText")),
                        "length", text(v.opt("lengthText")), "views", text(v.opt("viewCountText")), "when", text(v.opt("publishedTimeText"))));
            }
        }
        for (Object o : all(root, "lockupViewModel", max * 2)) {
            if (!(o instanceof JSONObject) || out.length() >= max) continue;
            JSONObject v = (JSONObject) o;
            String id = v.optString("contentId");
            if (id.isEmpty() || !seen.add(id)) continue;
            Object title = first(v, "lockupMetadataViewModel");
            String t = title instanceof JSONObject ? text(((JSONObject) title).opt("title")) : "";
            List<Object> parts = all(v, "metadataParts", 3);
            StringBuilder meta = new StringBuilder();
            for (Object p : parts) {
                if (!(p instanceof JSONArray)) continue;
                JSONArray a = (JSONArray) p;
                for (int i = 0; i < a.length(); i++) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(text(a.optJSONObject(i).opt("text")));
                }
            }
            out.put(J.obj("video", id, "title", t, "info", meta.toString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String login = Plugins.needLogin(Plugins.YOUTUBE);
        String a = inv.str("action");
        int count = (int) inv.num("count", 15);
        boolean needsAccount = !a.equals("search") && !a.equals("video") && !a.equals("comments") && !a.equals("channel");
        if (login != null && needsAccount) return ToolResult.fail(login);
        try {
            switch (a) {
                case "search": {
                    JSONObject r = call("search", J.obj("query", need(inv, "query")));
                    JSONArray chans = new JSONArray();
                    for (Object o : all(r, "channelRenderer", 5)) {
                        JSONObject c = (JSONObject) o;
                        chans.put(J.obj("channel", c.optString("channelId"), "name", text(c.opt("title")),
                                "subscribers", text(c.opt("videoCountText"))));
                    }
                    return ToolResult.ok("Search results", J.obj("videos", videos(r, count), "channels", chans));
                }
                case "video": {
                    String id = videoId(need(inv, "video"));
                    JSONObject r = call("player", J.obj("videoId", id));
                    JSONObject d = r.optJSONObject("videoDetails");
                    if (d == null) return ToolResult.fail("No video " + id);
                    return ToolResult.ok(d.optString("title"), J.obj("video", id, "title", d.optString("title"), "channel", d.optString("author"),
                            "channel_id", d.optString("channelId"), "seconds", d.optLong("lengthSeconds"), "views", d.optLong("viewCount"),
                            "live", d.optBoolean("isLiveContent"), "description", cut(d.optString("shortDescription"), 2000),
                            "url", WEB + "/watch?v=" + id));
                }
                case "comments": {
                    JSONObject page = commentsPage(videoId(need(inv, "video")));
                    return ToolResult.ok("Top comments", J.obj("comments", comments(page, count)));
                }
                case "comment": {
                    String id = videoId(need(inv, "video"));
                    JSONObject page = commentsPage(id);
                    Object params = first(page, "createCommentParams");
                    if (params == null) return ToolResult.fail("Comments are off for this video (or YouTube did not offer a comment box)");
                    JSONObject r = call("comment/create_comment", J.obj("createCommentParams", params, "commentText", need(inv, "text")));
                    return "STATUS_SUCCEEDED".equals(r.optString("actionResult", "")) || r.has("actions")
                            ? ToolResult.ok("Commented on " + WEB + "/watch?v=" + id) : ToolResult.ok("Sent", J.obj("answer", cut(r.toString(), 600)));
                }
                case "like":
                case "dislike":
                case "unlike": {
                    String id = videoId(need(inv, "video"));
                    call("like/" + (a.equals("unlike") ? "removelike" : a), J.obj("target", J.obj("videoId", id)));
                    return ToolResult.ok((a.equals("like") ? "Liked " : a.equals("dislike") ? "Disliked " : "Removed the like of ") + id);
                }
                case "subscribe":
                case "unsubscribe": {
                    String ch = channelId(need(inv, "channel"));
                    call("subscription/" + a, J.obj("channelIds", new JSONArray().put(ch), "params", a.equals("subscribe") ? "EgIIAhgA" : "CgIIAhgA"));
                    return ToolResult.ok((a.equals("subscribe") ? "Subscribed to " : "Unsubscribed from ") + ch);
                }
                case "channel": {
                    String ch = channelId(need(inv, "channel"));
                    JSONObject head = call("browse", J.obj("browseId", ch));
                    JSONObject list = call("browse", J.obj("browseId", ch, "params", "EgZ2aWRlb3PyBgQKAjoA"));
                    Object meta = first(head, "channelMetadataRenderer");
                    JSONObject m = meta instanceof JSONObject ? (JSONObject) meta : new JSONObject();
                    List<Object> subs = all(head, "subscriberCountText", 1);
                    String subText = subs.isEmpty() ? "" : text(subs.get(0));
                    if (subText.isEmpty()) {
                        for (Object o : all(head, "content", 60)) {
                            if (o instanceof String && ((String) o).toLowerCase(Locale.ROOT).contains("subscriber")) {
                                subText = (String) o;
                                break;
                            }
                        }
                    }
                    return ToolResult.ok(m.optString("title", ch), J.obj("channel", ch, "name", m.optString("title"),
                            "description", cut(m.optString("description"), 800), "subscribers", subText,
                            "url", m.optString("vanityChannelUrl", WEB + "/channel/" + ch), "latest", videos(list, count)));
                }
                case "feed": {
                    String name = inv.str("name", "home");
                    String id;
                    switch (name) {
                        case "subscriptions":
                            id = "FEsubscriptions";
                            break;
                        case "history":
                            id = "FEhistory";
                            break;
                        case "liked":
                            id = "VLLL";
                            break;
                        case "watch_later":
                            id = "VLWL";
                            break;
                        default:
                            id = "FEwhat_to_watch";
                    }
                    JSONObject r = call("browse", J.obj("browseId", id));
                    return ToolResult.ok("Feed " + name, J.obj("videos", videos(r, count)));
                }
                case "api": {
                    JSONObject body = inv.args.optJSONObject("json");
                    JSONObject r = call(need(inv, "endpoint"), body == null ? new JSONObject() : new JSONObject(body.toString()));
                    return ToolResult.ok("Answer", J.obj("body", cut(r.toString(), 12000)));
                }
                default:
                    return ToolResult.fail("Unknown action " + a);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    private static String need(ToolInvocation inv, String k) {
        String v = inv.str(k).trim();
        if (v.isEmpty()) throw new IllegalArgumentException(k + " is required for this action");
        return v;
    }

    /** The first page of a video's comments (also carries the comment box's createCommentParams). */
    private JSONObject commentsPage(String videoId) throws Exception {
        JSONObject next = call("next", J.obj("videoId", videoId));
        String token = null;
        for (Object o : all(next, "itemSectionRenderer", 20)) {
            JSONObject s = (JSONObject) o;
            if (!s.optString("sectionIdentifier").contains("comment")) continue;
            Object cmd = first(s, "continuationCommand");
            if (cmd instanceof JSONObject) token = ((JSONObject) cmd).optString("token");
        }
        if (token == null) {
            for (Object o : all(next, "continuationCommand", 20)) {
                String t = ((JSONObject) o).optString("token");
                if (t.startsWith("Eg0SC")) token = t; // comment section tokens begin like this
            }
        }
        if (token == null) throw new IllegalStateException("This video has no comments section (comments may be off)");
        return call("next", J.obj("continuation", token));
    }

    private static JSONArray comments(JSONObject page, int max) {
        JSONArray out = new JSONArray();
        for (Object o : all(page, "commentEntityPayload", max)) {
            JSONObject c = (JSONObject) o;
            JSONObject p = c.optJSONObject("properties"), au = c.optJSONObject("author"), tb = c.optJSONObject("toolbar");
            out.put(J.obj("author", au == null ? "" : au.optString("displayName"), "text", p == null ? "" : text(p.opt("content")),
                    "likes", tb == null ? "" : tb.optString("likeCountNotliked"), "when", p == null ? "" : p.optString("publishedTime")));
        }
        if (out.length() == 0) {
            for (Object o : all(page, "commentRenderer", max)) {
                JSONObject c = (JSONObject) o;
                out.put(J.obj("author", text(c.opt("authorText")), "text", text(c.opt("contentText")), "likes", text(c.opt("voteCount")),
                        "when", text(c.opt("publishedTimeText"))));
            }
        }
        return out;
    }

    /** A channel id from UC…, @handle, a channel url or a video. */
    private String channelId(String v) throws Exception {
        String s = v.trim();
        Matcher uc = Pattern.compile("(UC[A-Za-z0-9_-]{22})").matcher(s);
        if (uc.find()) return uc.group(1);
        if (s.startsWith("@") || s.contains("youtube.com/@") || s.contains("youtube.com/c/") || s.contains("youtube.com/user/")) {
            String url = s.startsWith("@") ? WEB + "/" + s : s;
            JSONObject r = call("navigation/resolve_url", J.obj("url", url));
            Object id = first(r, "browseId");
            if (id instanceof String) return (String) id;
            throw new IllegalStateException("No channel " + v);
        }
        JSONObject p = call("player", J.obj("videoId", videoId(s)));
        JSONObject d = p.optJSONObject("videoDetails");
        if (d != null && !d.optString("channelId").isEmpty()) return d.optString("channelId");
        throw new IllegalStateException("Give a channel id (UC…), @handle or a video of the channel");
    }
}
