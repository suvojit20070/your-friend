package com.suvo.agent.core.tools.builtin;

import android.content.Context;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Create / read / write / list text files in the agent's workspace
 * (app files dir + /workspace). Broader storage comes via SAF or Shizuku later.
 */
public class FileTool implements Tool {

    private final File root;

    public FileTool(Context ctx) {
        root = new File(ctx.getFilesDir(), "workspace");
        if (!root.exists()) root.mkdirs();
    }

    @Override public String name() { return "file"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject op = new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("read").put("write").put("append").put("list").put("delete"));
            JSONObject props = new JSONObject()
                    .put("op", op)
                    .put("path", new JSONObject().put("type", "string").put("description", "Relative path in the workspace"))
                    .put("content", new JSONObject().put("type", "string").put("description", "For write/append"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("op"));
            return new ToolSchema(name(),
                    "Work with text files in your workspace: read, write, append, list, delete.",
                    params, "medium");
        } catch (JSONException e) {
            return new ToolSchema(name(), "File operations.", ToolSchema.emptyParams(), "medium");
        }
    }

    @Override public boolean isAvailable() { return true; }

    @Override public String run(JSONObject args) {
        String op = args.optString("op", "");
        String path = args.optString("path", "");
        File f = safe(path);
        if (f == null && !op.equals("list")) return "Error: bad path.";
        try {
            switch (op) {
                case "read":
                    if (!f.exists()) return "Error: not found.";
                    return clip(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                case "write":
                    f.getParentFile().mkdirs();
                    Files.write(f.toPath(), args.optString("content", "").getBytes(StandardCharsets.UTF_8));
                    return "Wrote " + path;
                case "append": {
                    f.getParentFile().mkdirs();
                    String cur = f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
                    Files.write(f.toPath(), (cur + args.optString("content", "")).getBytes(StandardCharsets.UTF_8));
                    return "Appended to " + path;
                }
                case "delete":
                    return f.delete() ? "Deleted " + path : "Error: could not delete.";
                case "list": {
                    File dir = path.isEmpty() ? root : f;
                    File[] fs = dir.listFiles();
                    if (fs == null) return "(empty)";
                    StringBuilder sb = new StringBuilder();
                    for (File x : fs) sb.append(x.isDirectory() ? "[dir] " : "").append(x.getName()).append('\n');
                    return sb.length() == 0 ? "(empty)" : sb.toString();
                }
                default: return "Error: op must be read|write|append|list|delete";
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private File safe(String rel) {
        if (rel == null) return null;
        File f = new File(root, rel);
        try {
            if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) return null;
        } catch (Exception e) { return null; }
        return f;
    }

    private static String clip(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) + "…" : s;
    }
}
