package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.files.SafStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * File tools. Paths are workspace-relative ("notes/a.md") or "ext:&lt;Folder&gt;/..." for folders the user
 * granted in the Files screen. Workspace deletions go to a recoverable trash.
 */
public final class FileTools {

    private FileTools() {
    }

    static final String PATH_DOC = "Workspace path like \"notes/todo.md\" or a granted folder path like \"ext:Documents/report.txt\"";

    static FileAccess fa(AppGraph g) {
        return new FileAccess(g.workspace, g.saf);
    }

    static boolean isText(byte[] b) {
        int n = Math.min(b.length, 4096);
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32)) bad++;
        }
        return n == 0 || bad * 20 < n;
    }

    static String grantsHint(AppGraph g) {
        StringBuilder sb = new StringBuilder();
        for (SafStore.Grant gr : g.saf.grants()) sb.append(sb.length() == 0 ? "" : ", ").append("ext:").append(gr.name);
        return sb.length() == 0 ? "No external folders granted (the user can add one in Files)." : "Granted folders: " + sb;
    }

    public static final class ListFiles extends BaseTool {
        public ListFiles(AppGraph g) {
            super(g, ToolSpec.builder("file_list")
                    .description("List a folder. Default is the workspace root. Also reports which external folders are granted.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"default\":\"/\",\"description\":\"" + PATH_DOC + "\"}},\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.READ_ONLY).timeoutMs(30_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String path = inv.str("path", "/");
            List<FileAccess.Entry> list = fa(g).list(path);
            JSONArray arr = new JSONArray();
            for (FileAccess.Entry e : list) {
                if (arr.length() >= 500) break;
                arr.put(e.toJson());
            }
            return ToolResult.ok(list.size() + " entries in " + path, J.obj("entries", arr, "external", grantsHint(g)));
        }
    }

    public static final class ReadFile extends BaseTool {
        public ReadFile(AppGraph g) {
            super(g, ToolSpec.builder("file_read")
                    .description("Read a text file, optionally a line range. For images use view_image.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"" + PATH_DOC + "\"},"
                            + "\"start_line\":{\"type\":\"integer\",\"minimum\":1,\"default\":1},\"max_lines\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5000,\"default\":400}},"
                            + "\"required\":[\"path\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.READ_ONLY).timeoutMs(30_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String path = inv.str("path");
            FileAccess.Entry e = fa(g).stat(path);
            if (e.dir) return ToolResult.fail(path + " is a folder; use file_list");
            if (e.mime.startsWith("image/")) return ToolResult.fail(path + " is an image; use view_image");
            byte[] b = fa(g).read(path, 4 * 1024 * 1024);
            if (!isText(b)) return ToolResult.fail(path + " looks binary (" + e.mime + ", " + e.size + " bytes); it cannot be shown as text");
            String[] lines = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            int start = (int) inv.num("start_line", 1);
            int max = (int) inv.num("max_lines", 400);
            int from = Math.min(Math.max(0, start - 1), lines.length);
            int to = Math.min(lines.length, from + max);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) sb.append(lines[i]).append('\n');
            return ToolResult.ok("Read lines " + (from + 1) + "-" + to + " of " + lines.length,
                    J.obj("path", e.path, "content", sb.toString(), "total_lines", lines.length, "truncated", to < lines.length || e.size > b.length));
        }
    }

    public static final class WriteFile extends BaseTool {
        public WriteFile(AppGraph g) {
            super(g, ToolSpec.builder("file_write")
                    .description("Create, overwrite or append to a text file. Parent folders in the workspace are created automatically.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"" + PATH_DOC + "\"},"
                            + "\"content\":{\"type\":\"string\",\"maxLength\":2000000},"
                            + "\"mode\":{\"type\":\"string\",\"enum\":[\"create\",\"overwrite\",\"append\"],\"default\":\"create\"}},"
                            + "\"required\":[\"path\",\"content\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.LOW).timeoutMs(30_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            boolean ext = FileAccess.isExternal(args.optString("path"));
            boolean overwrite = "overwrite".equals(args.optString("mode"));
            if (ext) return overwrite ? RiskLevel.HIGH : RiskLevel.MEDIUM;
            return overwrite ? RiskLevel.MEDIUM : RiskLevel.LOW;
        }

        @Override
        public String describeCall(JSONObject args) {
            return args.optString("mode", "create") + " file " + args.optString("path") + " (" + args.optString("content").length() + " chars)";
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String path = inv.str("path");
            String mode = inv.str("mode", "create");
            FileAccess f = fa(g);
            boolean exists = f.exists(path);
            if (mode.equals("create") && exists) return ToolResult.fail(path + " already exists; use mode=overwrite or append, or file_edit");
            String p = f.write(path, inv.str("content").getBytes(StandardCharsets.UTF_8), mode.equals("append"));
            FileAccess.Entry e = f.stat(p);
            return ToolResult.ok((mode.equals("append") ? "Appended to " : exists ? "Overwrote " : "Created ") + p, J.obj("path", p, "size", e.size));
        }
    }

    public static final class EditFile extends BaseTool {
        public EditFile(AppGraph g) {
            super(g, ToolSpec.builder("file_edit")
                    .description("Replace exact text in a text file. Fails if find is not present, or appears a different number of times than expected_count.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1},"
                            + "\"find\":{\"type\":\"string\",\"minLength\":1},\"replace\":{\"type\":\"string\"},"
                            + "\"expected_count\":{\"type\":\"integer\",\"minimum\":1,\"default\":1}},"
                            + "\"required\":[\"path\",\"find\",\"replace\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.LOW).timeoutMs(30_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            return FileAccess.isExternal(args.optString("path")) ? RiskLevel.MEDIUM : RiskLevel.LOW;
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            FileAccess f = fa(g);
            String path = inv.str("path");
            byte[] b = f.read(path, 8 * 1024 * 1024);
            if (!isText(b)) return ToolResult.fail("Not a text file");
            String text = new String(b, StandardCharsets.UTF_8);
            String find = inv.str("find");
            int count = 0;
            for (int i = text.indexOf(find); i >= 0; i = text.indexOf(find, i + find.length())) count++;
            long expected = inv.num("expected_count", 1);
            if (count == 0) return ToolResult.fail("Text to replace was not found in " + path);
            if (count != expected) return ToolResult.fail("Found " + count + " occurrences, expected " + expected + "; make find more specific or set expected_count");
            String out = text.replace(find, inv.str("replace"));
            f.write(path, out.getBytes(StandardCharsets.UTF_8), false);
            return ToolResult.ok("Replaced " + count + " occurrence(s) in " + path, J.obj("path", path, "occurrences", count));
        }
    }

    public static final class ManageFile extends BaseTool {
        public ManageFile(AppGraph g) {
            super(g, ToolSpec.builder("file_manage")
                    .description("rename, copy, move, delete or mkdir. Deleting in the workspace moves to a recoverable trash; deleting in an external folder is permanent.")
                    .schema("{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"rename\",\"copy\",\"move\",\"delete\",\"mkdir\"]},"
                            + "\"path\":{\"type\":\"string\",\"minLength\":1},\"to\":{\"type\":\"string\",\"description\":\"Destination path for copy/move\"},"
                            + "\"new_name\":{\"type\":\"string\"},\"overwrite\":{\"type\":\"boolean\",\"default\":false}},"
                            + "\"required\":[\"action\",\"path\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.MEDIUM).timeoutMs(120_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String a = args.optString("action");
            boolean ext = FileAccess.isExternal(args.optString("path")) || FileAccess.isExternal(args.optString("to"));
            if (a.equals("delete")) return ext ? RiskLevel.HIGH : RiskLevel.MEDIUM;
            if (a.equals("mkdir")) return ext ? RiskLevel.MEDIUM : RiskLevel.LOW;
            if (args.optBoolean("overwrite")) return RiskLevel.HIGH;
            return ext ? RiskLevel.MEDIUM : RiskLevel.LOW;
        }

        @Override
        public String describeCall(JSONObject args) {
            String a = args.optString("action");
            return a + " " + args.optString("path") + (args.has("to") ? " → " + args.optString("to") : "") + (args.has("new_name") ? " → " + args.optString("new_name") : "");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            FileAccess f = fa(g);
            String path = inv.str("path");
            switch (inv.str("action")) {
                case "rename":
                    if (inv.str("new_name").isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "new_name is required");
                    return ToolResult.ok("Renamed to " + f.rename(path, inv.str("new_name")));
                case "copy":
                    if (inv.str("to").isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "to is required");
                    return ToolResult.ok("Copied to " + f.copy(path, inv.str("to"), inv.bool("overwrite", false)));
                case "move":
                    if (inv.str("to").isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "to is required");
                    return ToolResult.ok("Moved to " + f.move(path, inv.str("to"), inv.bool("overwrite", false)));
                case "delete":
                    return ToolResult.ok(path + ": " + f.delete(path));
                case "mkdir":
                    return ToolResult.ok("Folder ready: " + f.mkdirs(path));
                default:
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action");
            }
        }
    }

    public static final class SearchFiles extends BaseTool {
        public SearchFiles(AppGraph g) {
            super(g, ToolSpec.builder("file_search")
                    .description("Search files recursively by name (glob like *.py) and/or by text they contain.")
                    .schema("{\"type\":\"object\",\"properties\":{\"root\":{\"type\":\"string\",\"default\":\"/\"},"
                            + "\"name\":{\"type\":\"string\"},\"contains\":{\"type\":\"string\"},"
                            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":500,\"default\":50}},\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.READ_ONLY).timeoutMs(120_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            List<JSONObject> hits = fa(g).search(inv.str("root", "/"), inv.str("name"), inv.str("contains"), (int) inv.num("limit", 50));
            return ToolResult.ok(hits.size() + " match(es)", J.obj("matches", new JSONArray(hits)));
        }
    }

    public static final class FileInfo extends BaseTool {
        public FileInfo(AppGraph g) {
            super(g, ToolSpec.builder("file_info")
                    .description("Metadata of a file or folder: size, type, modified time, SHA-256 for files up to 50 MB.")
                    .schema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"path\"],\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.READ_ONLY).timeoutMs(60_000).build());
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            FileAccess f = fa(g);
            FileAccess.Entry e = f.stat(inv.str("path"));
            JSONObject o = e.toJson();
            if (!e.dir && e.size <= 50L * 1024 * 1024) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream in = f.open(e.path)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                }
                StringBuilder hex = new StringBuilder();
                for (byte x : md.digest()) hex.append(String.format("%02x", x));
                J.put(o, "sha256", hex.toString());
            }
            return ToolResult.ok(e.name, o);
        }
    }
}
