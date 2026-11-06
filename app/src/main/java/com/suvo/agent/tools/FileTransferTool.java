package com.suvo.agent.tools;

import android.util.Base64;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.files.FileAccess;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.TermuxBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * file_transfer: copies or moves files and folders between the three places the agent works in —
 * the app's private workspace ("notes/a.md"), a folder the user granted ("ext:Documents/…") and the
 * agent's Termux folder ("termux:project/…" = ~/yf/project). Termux cannot read the app's private
 * storage and the app cannot read Termux's, so files into / out of Termux go through Termux itself
 * in small base64 chunks (works for binaries too).
 */
public final class FileTransferTool extends BaseTool {

    private static final int CHUNK_IN = 192 * 1024;   // raw bytes per command into Termux
    private static final int CHUNK_OUT = 60 * 1024;   // raw bytes per command out of Termux (stdout is capped)
    private static final long MAX_TOTAL = 200L * 1024 * 1024;

    public FileTransferTool(AppGraph g) {
        super(g, ToolSpec.builder("file_transfer")
                .description("Copy or move a file or a whole folder between the private workspace ('src/app.py'), a granted "
                        + "external folder ('ext:Documents/x') and your Termux folder ('termux:proj/x' = ~/yf/proj/x, the only "
                        + "Termux folder you use). Use it to put files you made in the workspace into Termux to build/run them, "
                        + "or to bring Termux results to the workspace or an external folder for the user.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"from\":{\"type\":\"string\",\"minLength\":1},"
                        + "\"to\":{\"type\":\"string\",\"minLength\":1,\"description\":\"destination (a folder path ending with / keeps the name)\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"copy\",\"move\"],\"default\":\"copy\"},"
                        + "\"overwrite\":{\"type\":\"boolean\",\"default\":false}"
                        + "},\"required\":[\"from\",\"to\"],\"additionalProperties\":false}")
                .risk(RiskLevel.MEDIUM).timeoutMs(15 * 60_000).build());
    }

    @Override
    public RiskLevel riskFor(JSONObject args) {
        if (args.optBoolean("overwrite")) return RiskLevel.HIGH;
        return "move".equals(args.optString("mode")) ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    @Override
    public String describeCall(JSONObject args) {
        return args.optString("mode", "copy") + " " + args.optString("from") + " → " + args.optString("to");
    }

    private static boolean isTermux(String p) {
        return p.startsWith("termux:");
    }

    /** "termux:a/b" → the real path under ~/yf (refuses anything that leaves it). */
    static String termuxPath(String p) throws IllegalArgumentException {
        String rel = p.substring("termux:".length()).trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.startsWith("~/")) rel = rel.substring(2);
        if (rel.startsWith("yf/")) rel = rel.substring(3);
        for (String part : rel.split("/")) if (part.equals("..")) throw new IllegalArgumentException("'..' is not allowed: stay inside ~/yf");
        return rel.isEmpty() ? TermuxBackend.AGENT_HOME : TermuxBackend.AGENT_HOME + "/" + rel;
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String name(String p) {
        String s = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String join(String dir, String rel) {
        if (dir.endsWith("/") || dir.endsWith(":")) return dir + rel;
        return dir + "/" + rel;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String from = inv.str("from").trim(), to = inv.str("to").trim();
        boolean move = "move".equals(inv.str("mode", "copy"));
        boolean overwrite = inv.bool("overwrite", false);
        FileAccess fa = new FileAccess(g.workspace, g.saf);
        if (!isTermux(from) && !isTermux(to)) {
            String done = move ? fa.move(from, to, overwrite) : fa.copy(from, to, overwrite);
            return ToolResult.ok((move ? "Moved to " : "Copied to ") + done);
        }
        TermuxBackend t = g.termux.forUser(); // real paths; every path is checked to stay inside ~/yf
        String why = t.unavailableReason();
        if (why != null) return ToolResult.fail(why);
        if (isTermux(from) && isTermux(to)) {
            String a = termuxPath(from), b = termuxPath(to);
            ExecResult r = t.run("mkdir -p \"$(dirname " + q(b) + ")\" && " + (move ? "mv " : "cp -r ") + (overwrite ? "-f " : "-n ") + q(a) + " " + q(b),
                    null, 120_000, inv.cancel);
            return r.exitCode == 0 ? ToolResult.ok((move ? "Moved to " : "Copied to ") + to) : ToolResult.fail("Termux: " + r.stderr + r.error);
        }
        if (isTermux(to)) return intoTermux(fa, t, from, to, move, overwrite, inv);
        return outOfTermux(fa, t, from, to, move, overwrite, inv);
    }

    // ---- workspace / ext → Termux

    private ToolResult intoTermux(FileAccess fa, TermuxBackend t, String from, String to, boolean move, boolean overwrite,
                                  ToolInvocation inv) throws Exception {
        FileAccess.Entry root = fa.stat(from);
        String dest = termuxPath(to);
        if (to.endsWith("/")) dest = dest + "/" + root.name;
        List<String[]> files = new ArrayList<>(); // {source path, termux path}
        List<String> dirs = new ArrayList<>();
        long[] total = new long[1];
        collect(fa, root, dest, files, dirs, total);
        if (total[0] > MAX_TOTAL) return ToolResult.fail("Too large to transfer this way (max 200 MB)");
        if (!overwrite) {
            ExecResult ex = t.run("[ -e " + q(dest) + " ] && echo EXISTS || true", null, 30_000, inv.cancel);
            if (ex.stdout.contains("EXISTS")) return ToolResult.fail(to + " already exists (set overwrite to replace it)");
        }
        StringBuilder mk = new StringBuilder("mkdir -p");
        for (String d : dirs) mk.append(' ').append(q(d));
        for (String[] f : files) mk.append(' ').append("\"$(dirname ").append(q(f[1])).append(")\"");
        ExecResult r0 = t.run(mk.toString(), null, 60_000, inv.cancel);
        if (r0.exitCode != 0) return ToolResult.fail("Termux: " + r0.stderr + r0.error);
        for (String[] f : files) {
            if (inv.cancel != null && inv.cancel.isCancelled()) return ToolResult.fail("Cancelled");
            boolean first = true;
            try (InputStream in = fa.open(f[0])) {
                byte[] buf = new byte[CHUNK_IN];
                while (true) {
                    int n = readFully(in, buf);
                    if (n <= 0 && !first) break;
                    String b64 = Base64.encodeToString(buf, 0, Math.max(0, n), Base64.NO_WRAP);
                    ExecResult r = t.run("base64 -d " + (first ? ">" : ">>") + " " + q(f[1]), b64, 120_000, inv.cancel);
                    if (r.exitCode != 0) return ToolResult.fail("Termux could not write " + f[1] + ": " + r.stderr + r.error);
                    first = false;
                    if (n < buf.length) break;
                }
            }
        }
        if (move) fa.delete(from);
        return ToolResult.ok((move ? "Moved " : "Copied ") + files.size() + " file(s) to " + to,
                J.obj("files", files.size(), "bytes", total[0], "termux_path", dest));
    }

    private static void collect(FileAccess fa, FileAccess.Entry e, String dest, List<String[]> files, List<String> dirs, long[] total)
            throws Exception {
        if (!e.dir) {
            files.add(new String[]{e.path, dest});
            total[0] += e.size;
            return;
        }
        dirs.add(dest);
        for (FileAccess.Entry c : fa.list(e.path)) collect(fa, c, dest + "/" + c.name, files, dirs, total);
    }

    private static int readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    // ---- Termux → workspace / ext

    private ToolResult outOfTermux(FileAccess fa, TermuxBackend t, String from, String to, boolean move, boolean overwrite,
                                   ToolInvocation inv) throws Exception {
        String src = termuxPath(from);
        ExecResult ls = t.run("cd " + q(src) + " 2>/dev/null && find . -type f -printf '%s\\t%p\\n' || "
                + "( [ -f " + q(src) + " ] && printf '%s\\t.\\n' \"$(stat -c %s " + q(src) + ")\" )", null, 60_000, inv.cancel);
        if (ls.stdout.trim().isEmpty()) return ToolResult.fail("Nothing found at " + from + (ls.stderr.isEmpty() ? "" : ": " + ls.stderr));
        boolean single = ls.stdout.trim().endsWith("\t.") && !ls.stdout.contains("./");
        String base = to.endsWith("/") ? join(to, name(from)) : to;
        if (!overwrite && fa.exists(base)) return ToolResult.fail(to + " already exists (set overwrite to replace it)");
        JSONArray done = new JSONArray();
        long total = 0;
        for (String line : ls.stdout.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            long size = Long.parseLong(line.substring(0, tab).trim());
            String rel = line.substring(tab + 1).trim();
            String termuxFile = single ? src : src + "/" + (rel.startsWith("./") ? rel.substring(2) : rel);
            String target = single ? base : join(base, rel.startsWith("./") ? rel.substring(2) : rel);
            total += size;
            if (total > MAX_TOTAL) return ToolResult.fail("Too large to transfer this way (max 200 MB)");
            int slash = target.lastIndexOf('/');
            if (slash > 0) {
                try {
                    fa.mkdirs(target.substring(0, slash));
                } catch (Exception ignored) {
                    // Already there.
                }
            }
            fa.write(target, new byte[0], false);
            for (long off = 0; off < size; off += CHUNK_OUT) {
                if (inv.cancel != null && inv.cancel.isCancelled()) return ToolResult.fail("Cancelled");
                ExecResult r = t.run("dd if=" + q(termuxFile) + " bs=" + CHUNK_OUT + " skip=" + (off / CHUNK_OUT) + " count=1 2>/dev/null | base64 -w0",
                        null, 120_000, inv.cancel);
                if (r.exitCode != 0) return ToolResult.fail("Termux could not read " + termuxFile + ": " + r.stderr + r.error);
                byte[] data = Base64.decode(r.stdout.trim(), Base64.DEFAULT);
                fa.write(target, data, true);
            }
            done.put(target);
        }
        if (move) t.run("rm -rf " + q(src), null, 60_000, inv.cancel);
        return ToolResult.ok((move ? "Moved " : "Copied ") + done.length() + " file(s) to " + to, J.obj("files", done, "bytes", total));
    }
}
