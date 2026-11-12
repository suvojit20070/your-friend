package com.suvo.agent.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.tools.BaseTool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Your Friend — Original project by Suvo.
 *
 * MCP connectors the user adds (Settings → MCP servers): any Model Context Protocol server
 * reachable over HTTP (its URL, plus a header such as "Authorization: Bearer …" when it needs a
 * login). Each server's tools become the agent's tools, named mcp_&lt;server&gt;_&lt;tool&gt;, with
 * the server's own description and argument schema. Tool lists are kept, so the tools are there
 * at once when the app starts; "Refresh" asks the server again. Header values are stored
 * encrypted.
 */
public final class Mcp {

    /** One server. */
    public static final class Server {
        public String id = "", name = "", url = "", error = "", serverName = "";
        public boolean enabled = true;
        public long refreshedAt;
        /** Header name → value (decrypted in memory). */
        public final Map<String, String> headers = new LinkedHashMap<>();
        /** The server's tools as it listed them. */
        public JSONArray tools = new JSONArray();
        /** Tools the user switched off. */
        public final List<String> off = new ArrayList<>();
    }

    private static final Map<String, McpClient> CLIENTS = new ConcurrentHashMap<>();
    /** Tool names registered by MCP (to remove them again). */
    private static final List<String> REGISTERED = new ArrayList<>();

    private Mcp() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE);
    }

    public static synchronized List<Server> servers(AppGraph g) {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(g.ctx).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Server s = new Server();
                s.id = o.optString("id");
                s.name = o.optString("name");
                s.url = o.optString("url");
                s.enabled = o.optBoolean("enabled", true);
                s.refreshedAt = o.optLong("refreshedAt");
                s.error = o.optString("error");
                s.serverName = o.optString("serverName");
                JSONArray t = o.optJSONArray("tools");
                if (t != null) s.tools = t;
                JSONArray off = o.optJSONArray("off");
                for (int k = 0; off != null && k < off.length(); k++) s.off.add(off.optString(k));
                String enc = o.optString("headers", "");
                if (!enc.isEmpty()) {
                    try {
                        JSONObject h = new JSONObject(g.secrets.decrypt(enc));
                        for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                            String k = it.next();
                            s.headers.put(k, h.optString(k));
                        }
                    } catch (Exception ignored) {
                        // a key that can no longer be read: the user enters it again
                    }
                }
                out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static synchronized void write(AppGraph g, List<Server> all) {
        JSONArray a = new JSONArray();
        for (Server s : all) {
            try {
                JSONObject h = new JSONObject();
                for (Map.Entry<String, String> e : s.headers.entrySet()) h.put(e.getKey(), e.getValue());
                a.put(new JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("enabled", s.enabled)
                        .put("refreshedAt", s.refreshedAt).put("error", s.error).put("serverName", s.serverName)
                        .put("tools", s.tools).put("off", new JSONArray(s.off))
                        .put("headers", h.length() == 0 ? "" : g.secrets.encrypt(h.toString())));
            } catch (JSONException ignored) {
            }
        }
        prefs(g.ctx).edit().putString("list", a.toString()).apply();
    }

    public static synchronized void save(AppGraph g, Server s) {
        List<Server> all = servers(g);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(s.id)) {
                all.set(i, s);
                found = true;
            }
        }
        if (!found) {
            if (s.id.isEmpty()) s.id = Long.toString(System.currentTimeMillis(), 36);
            all.add(s);
        }
        write(g, all);
        CLIENTS.remove(s.id);
        registerAll(g);
    }

    public static synchronized void delete(AppGraph g, String id) {
        List<Server> all = servers(g);
        all.removeIf(s -> s.id.equals(id));
        write(g, all);
        CLIENTS.remove(id);
        registerAll(g);
    }

    static McpClient client(Server s) {
        McpClient c = CLIENTS.get(s.id);
        if (c == null) {
            c = new McpClient(s.url, new LinkedHashMap<>(s.headers));
            CLIENTS.put(s.id, c);
        }
        return c;
    }

    /** Asks the server for its tools again (network: not on the main thread). Null = ok, else the problem. */
    public static String refresh(AppGraph g, String id) {
        Server s = null;
        for (Server x : servers(g)) if (x.id.equals(id)) s = x;
        if (s == null) return "No such server";
        CLIENTS.remove(id);
        try {
            McpClient c = client(s);
            s.tools = c.listTools();
            s.serverName = c.serverName;
            s.error = "";
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        s.refreshedAt = System.currentTimeMillis();
        save(g, s);
        return s.error.isEmpty() ? null : s.error;
    }

    /** "my server" → "my_server" (tool names allow a-z, 0-9, _ and -). */
    static String slug(String s, int max) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (t.isEmpty()) t = "x";
        return t.length() > max ? t.substring(0, max) : t;
    }

    public static String toolName(Server s, String tool) {
        String base = "mcp_" + slug(s.name.isEmpty() ? s.id : s.name, 16) + "_";
        return base + slug(tool, 64 - base.length());
    }

    /** (Re)registers the tools of every enabled server from the kept lists. */
    public static synchronized void registerAll(AppGraph g) {
        for (String n : REGISTERED) g.tools.unregister(n);
        REGISTERED.clear();
        for (Server s : servers(g)) {
            if (!s.enabled) continue;
            for (int i = 0; i < s.tools.length(); i++) {
                JSONObject t = s.tools.optJSONObject(i);
                if (t == null || t.optString("name").isEmpty() || s.off.contains(t.optString("name"))) continue;
                try {
                    McpTool tool = new McpTool(g, s, t);
                    if (g.tools.get(tool.spec().name) != null) continue; // a name clash: the first one stays
                    g.tools.register(tool);
                    REGISTERED.add(tool.spec().name);
                } catch (RuntimeException ignored) {
                    // an unusable schema: that tool is skipped
                }
            }
        }
    }

    /** One tool of an MCP server, as an agent tool. */
    static final class McpTool extends BaseTool {
        private final String serverId, remoteName, serverLabel;

        McpTool(AppGraph g, Server s, JSONObject t) {
            super(g, spec(s, t));
            serverId = s.id;
            remoteName = t.optString("name");
            serverLabel = s.name;
        }

        private static ToolSpec spec(Server s, JSONObject t) {
            JSONObject schema = t.optJSONObject("inputSchema");
            if (schema == null) {
                try {
                    schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            JSONObject ann = t.optJSONObject("annotations");
            RiskLevel risk = RiskLevel.MEDIUM;
            if (ann != null) {
                if (ann.optBoolean("readOnlyHint", false)) risk = RiskLevel.READ_ONLY;
                else if (ann.optBoolean("destructiveHint", false)) risk = RiskLevel.HIGH;
            }
            String desc = t.optString("description", "").trim();
            if (desc.length() > 1200) desc = desc.substring(0, 1200) + "…";
            return ToolSpec.builder(toolName(s, t.optString("name")))
                    .description("[MCP · " + s.name + "] " + (desc.isEmpty() ? t.optString("name") : desc))
                    .schema(schema).risk(risk).backend("mcp").timeoutMs(5 * 60_000L).build();
        }

        @Override
        public String describeCall(JSONObject a) {
            String s = a.toString();
            return serverLabel + " · " + remoteName + " " + (s.length() > 160 ? s.substring(0, 160) + "…" : s);
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            Server s = null;
            for (Server x : servers(g)) if (x.id.equals(serverId)) s = x;
            if (s == null || !s.enabled) return ToolResult.of(ToolResult.Status.UNAVAILABLE, "This MCP server was removed or switched off");
            JSONObject r = client(s).callTool(remoteName, inv.args, spec().timeoutMs);
            StringBuilder text = new StringBuilder();
            JSONArray content = r.optJSONArray("content");
            JSONArray other = new JSONArray();
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                if ("text".equals(c.optString("type"))) {
                    if (text.length() > 0) text.append('\n');
                    text.append(c.optString("text"));
                } else {
                    // images, audio, resources: described, not inlined (they can be very large)
                    JSONObject d = new JSONObject().put("type", c.optString("type")).put("mimeType", c.optString("mimeType"));
                    JSONObject res = c.optJSONObject("resource");
                    if (res != null) {
                        d.put("uri", res.optString("uri"));
                        if (res.has("text")) d.put("text", clip(res.optString("text"), 20_000));
                    }
                    other.put(d);
                }
            }
            JSONObject data = new JSONObject().put("text", clip(text.toString(), 60_000));
            if (other.length() > 0) data.put("other_content", other);
            if (r.has("structuredContent")) data.put("structured", r.opt("structuredContent"));
            if (r.optBoolean("isError", false)) return ToolResult.fail(clip(text.toString(), 2000).isEmpty() ? "The tool reported an error" : clip(text.toString(), 2000), data);
            return ToolResult.ok(remoteName + " (" + serverLabel + ")", data);
        }

        private static String clip(String s, int n) {
            return s.length() > n ? s.substring(0, n) + "…" : s;
        }
    }
}
