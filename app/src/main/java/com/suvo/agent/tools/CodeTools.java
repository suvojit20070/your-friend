package com.suvo.agent.tools;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import com.suvo.agent.AppGraph;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.data.SshHostRepo;
import com.suvo.agent.terminal.ExecResult;
import com.suvo.agent.terminal.ShellBackend;
import com.suvo.agent.terminal.TermuxBackend;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shell, code execution through Termux runtimes, and SSH to user-configured hosts. */
public final class CodeTools {

    private CodeTools() {
    }

    private static final Pattern DANGEROUS = Pattern.compile(
            "(?i)(\\brm\\s+-[a-z]*r[a-z]*f|\\brm\\s+-[a-z]*f[a-z]*r|\\bmkfs|\\bdd\\s+if=.*of=/dev|\\breboot\\b|\\bpm\\s+(uninstall|clear|disable)"
                    + "|\\bsettings\\s+put\\b|\\bsvc\\s+|\\bcmd\\s+package\\s+uninstall|\\bwipe\\b|:\\(\\)\\s*\\{|\\bchmod\\s+-R\\s+777\\s+/|\\bcurl\\b.*\\|\\s*(ba)?sh)");

    public static final class Shell extends BaseTool {
        public Shell(AppGraph g) {
            super(g, ToolSpec.builder("shell")
                    .description("Run a shell command. backend=local uses Android's sh/toybox in the private workspace (no packages, no root). "
                            + "backend=termux runs in Termux (bash, pkg, python, clang, node, git) if installed and authorized. "
                            + "backend=shizuku runs with ADB-shell identity via Shizuku (input, pm, dumpsys). Output is captured with exit code. "
                            + "Termux has its own files (it starts in its home folder ~); it cannot read this app's workspace (file_* tools), "
                            + "so create and edit files for Termux projects with this tool, e.g. cat > ~/apps/x/File.java <<'EOF' ... EOF, "
                            + "and always cd into the project folder with an absolute path.")
                    .schema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":20000},"
                            + "\"backend\":{\"type\":\"string\",\"enum\":[\"default\",\"local\",\"termux\",\"shizuku\"],\"default\":\"default\"},"
                            + "\"stdin\":{\"type\":\"string\",\"maxLength\":200000},"
                            + "\"timeout_s\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900,\"default\":60}},"
                            + "\"required\":[\"command\"],\"additionalProperties\":false}")
                    .requires(Capability.LOCAL_SHELL).risk(RiskLevel.MEDIUM).backend("shell").timeoutMs(905_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            String b = args.optString("backend", "default");
            if (b.equals("default")) b = g.settings.str(com.suvo.agent.data.Settings.SHELL_BACKEND, "local");
            if (b.equals("shizuku")) return RiskLevel.HIGH;
            return DANGEROUS.matcher(args.optString("command")).find() ? RiskLevel.HIGH : RiskLevel.MEDIUM;
        }

        @Override
        public String describeCall(JSONObject args) {
            return "[" + args.optString("backend", "default") + "] $ " + args.optString("command");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String b = inv.str("backend", "default");
            ShellBackend backend = g.shell(b.equals("default") ? null : b);
            if (backend.id().equals("shizuku") && !g.settings.bool(com.suvo.agent.data.Settings.ALLOW_SHIZUKU, true)) {
                return ToolResult.of(ToolResult.Status.DENIED, "Shizuku use is turned off in Privacy settings");
            }
            String why = backend.unavailableReason();
            if (why != null) return ToolResult.of(ToolResult.Status.UNAVAILABLE, backend.id() + " backend unavailable: " + why);
            inv.progress.update("Running on " + backend.id());
            com.suvo.agent.terminal.TerminalFeed.Run feed = com.suvo.agent.terminal.TerminalFeed.start("shell", backend.id(), inv.str("command"));
            ExecResult r = backend.run(inv.str("command"), inv.str("stdin"), inv.num("timeout_s", 60) * 1000L, inv.cancel);
            com.suvo.agent.terminal.TerminalFeed.finish(feed, r);
            JSONObject data = r.toJson(16_000);
            if (!r.ran()) return ToolResult.fail(r.error, data);
            if (r.timedOut) return ToolResult.of(ToolResult.Status.TIMED_OUT, "Command timed out");
            if (r.cancelled) return ToolResult.of(ToolResult.Status.CANCELLED, "Command cancelled");
            return r.exitCode == 0 ? ToolResult.ok("Exit code 0", data) : ToolResult.fail("Exit code " + r.exitCode, data);
        }
    }

    /** execute_python / execute_cpp / execute_java, all through Termux runtimes. */
    public static final class ExecuteCode extends BaseTool {
        private final String lang;

        public ExecuteCode(AppGraph g, String lang) {
            super(g, ToolSpec.builder("execute_" + lang)
                    .description(desc(lang))
                    .schema("{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":300000},"
                            + "\"args\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":50},"
                            + "\"timeout_s\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900,\"default\":120}},"
                            + "\"required\":[\"code\"],\"additionalProperties\":false}")
                    .requires(Capability.TERMUX).risk(RiskLevel.MEDIUM).backend("termux").timeoutMs(905_000).build());
            this.lang = lang;
        }

        private static String desc(String lang) {
            switch (lang) {
                case "python":
                    return "Run a Python 3 program in Termux (packages installed with pip there are available). Prints stdout/stderr and exit code.";
                case "cpp":
                    return "Compile a C++17 program with clang++ in Termux and run it. Compiler errors are returned.";
                default:
                    return "Compile and run a Java program with OpenJDK in Termux. Put the entry point in a public class with main().";
            }
        }

        private String runtimeId() {
            return lang.equals("python") ? "python" : lang.equals("cpp") ? "cpp" : "java";
        }

        @Override
        public String unavailableReason() {
            Boolean installed = g.runtimes.isInstalled(runtimeId());
            if (installed != null && !installed) {
                String pkg = lang.equals("python") ? "python" : lang.equals("cpp") ? "clang" : "openjdk-17";
                return lang + " is not installed in Termux (run: pkg install " + pkg + ")";
            }
            return null;
        }

        @Override
        public String describeCall(JSONObject args) {
            String code = args.optString("code");
            return "Run " + lang + " code (" + code.split("\n").length + " lines)";
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            String dir = TermuxBackend.HOME + "/yourfriend/run/" + id;
            StringBuilder argv = new StringBuilder();
            org.json.JSONArray a = inv.args.optJSONArray("args");
            if (a != null) for (int i = 0; i < a.length(); i++) argv.append(' ').append(com.suvo.agent.device.shizuku.ShizukuManager.shellQuote(a.optString(i)));
            String cmd;
            switch (lang) {
                case "python":
                    cmd = "mkdir -p " + dir + " && cd " + dir + " && cat > main.py && python3 main.py" + argv + " </dev/null";
                    break;
                case "cpp":
                    cmd = "mkdir -p " + dir + " && cd " + dir + " && cat > main.cpp && clang++ -std=c++17 -O1 -o main main.cpp && ./main" + argv + " </dev/null";
                    break;
                default: {
                    Matcher m = Pattern.compile("public\\s+(?:final\\s+)?class\\s+(\\w+)").matcher(inv.str("code"));
                    String cls = m.find() ? m.group(1) : "Main";
                    cmd = "mkdir -p " + dir + " && cd " + dir + " && cat > " + cls + ".java && javac " + cls + ".java && java -cp . " + cls + argv + " </dev/null";
                }
            }
            inv.progress.update("Running " + lang + " in Termux");
            String firstLine = inv.str("code").split("\n", 2)[0];
            com.suvo.agent.terminal.TerminalFeed.Run feed = com.suvo.agent.terminal.TerminalFeed.start("execute_" + lang, "termux",
                    lang + " program (" + inv.str("code").split("\n").length + " lines): " + (firstLine.length() > 60 ? firstLine.substring(0, 60) + "…" : firstLine));
            ExecResult r = g.termux.run(cmd, inv.str("code"), inv.num("timeout_s", 120) * 1000L, inv.cancel);
            com.suvo.agent.terminal.TerminalFeed.finish(feed, r);
            JSONObject data = r.toJson(16_000);
            J.put(data, "workdir", dir);
            if (!r.ran()) return ToolResult.of(ToolResult.Status.UNAVAILABLE, r.error);
            if (r.timedOut) return ToolResult.of(ToolResult.Status.TIMED_OUT, "Program timed out (it may still be running in Termux)");
            if (r.exitCode == 127) return ToolResult.fail("Runtime not found in Termux: " + r.stderr.trim(), data);
            return r.exitCode == 0 ? ToolResult.ok("Exit code 0", data) : ToolResult.fail("Exit code " + r.exitCode, data);
        }
    }

    public static final class Ssh extends BaseTool {
        public Ssh(AppGraph g) {
            super(g, ToolSpec.builder("ssh")
                    .description("Run a command on a remote server the user configured in Terminal > SSH hosts (by host name). "
                            + "Arbitrary hosts cannot be reached. The server key is pinned on first connection.")
                    .schema("{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\",\"minLength\":1,\"description\":\"Configured host name\"},"
                            + "\"command\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":20000},"
                            + "\"timeout_s\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":900,\"default\":60}},"
                            + "\"required\":[\"host\",\"command\"],\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.HIGH).backend("jsch").timeoutMs(905_000).build());
        }

        @Override
        public String unavailableReason() {
            return g.sshHosts.list().isEmpty() ? "no SSH hosts configured (Terminal > SSH hosts)" : null;
        }

        @Override
        public String describeCall(JSONObject args) {
            return "ssh " + args.optString("host") + " $ " + args.optString("command");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            SshHostRepo.Host h = g.sshHosts.byName(inv.str("host"));
            if (h == null) {
                StringBuilder names = new StringBuilder();
                for (SshHostRepo.Host x : g.sshHosts.list()) names.append(names.length() == 0 ? "" : ", ").append(x.name);
                return ToolResult.fail("Unknown host \"" + inv.str("host") + "\". Configured: " + names);
            }
            if (h.secret.isEmpty()) return ToolResult.fail("No credentials stored for " + h.name + " (re-enter them in Terminal > SSH hosts)");
            JSch jsch = new JSch();
            final String[] seen = new String[1];
            jsch.setHostKeyRepository(new PinningRepository(h.knownHostKey, seen));
            if ("key".equals(h.auth)) jsch.addIdentity(h.name, h.secret.getBytes(StandardCharsets.UTF_8), null, null);
            Session s = jsch.getSession(h.username, h.host, h.port);
            if (!"key".equals(h.auth)) s.setPassword(h.secret);
            s.setConfig("StrictHostKeyChecking", "yes");
            s.setConfig("PreferredAuthentications", "key".equals(h.auth) ? "publickey" : "password,keyboard-interactive");
            s.setUserInfo(new NoPromptUserInfo(h.secret));
            Runnable kill = s::disconnect;
            inv.cancel.onCancel(kill);
            long t0 = System.currentTimeMillis();
            try {
                s.connect(20_000);
            } catch (JSchException e) {
                String m = String.valueOf(e.getMessage());
                if (m.toLowerCase(Locale.ROOT).contains("hostkey") || m.contains("changed")) {
                    return ToolResult.fail("Server identity changed for " + h.name + " — refusing to connect (possible interception). "
                            + "If the server was reinstalled, the user can reset the pinned key in Terminal > SSH hosts.");
                }
                return ToolResult.fail("SSH connection failed: " + m);
            }
            try {
                if (h.knownHostKey.isEmpty() && seen[0] != null) g.sshHosts.pinHostKey(h.id, seen[0]);
                ChannelExec ch = (ChannelExec) s.openChannel("exec");
                ch.setCommand(inv.str("command"));
                InputStream out = ch.getInputStream();
                InputStream err = ch.getExtInputStream();
                ch.connect(15_000);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                ByteArrayOutputStream e = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long deadline = t0 + inv.num("timeout_s", 60) * 1000L;
                boolean timedOut = false;
                while (true) {
                    while (out.available() > 0) {
                        int n = out.read(buf);
                        if (n < 0) break;
                        if (o.size() < 1_000_000) o.write(buf, 0, n);
                    }
                    while (err.available() > 0) {
                        int n = err.read(buf);
                        if (n < 0) break;
                        if (e.size() < 500_000) e.write(buf, 0, n);
                    }
                    if (ch.isClosed() && out.available() == 0 && err.available() == 0) break;
                    if (inv.cancel.isCancelled()) break;
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true;
                        break;
                    }
                    Thread.sleep(50);
                }
                int exit = ch.getExitStatus();
                ch.disconnect();
                ExecResult r = new ExecResult("ssh:" + h.name, exit, o.toString("UTF-8"), e.toString("UTF-8"), timedOut,
                        inv.cancel.isCancelled(), System.currentTimeMillis() - t0, "");
                JSONObject data = r.toJson(16_000);
                if (h.knownHostKey.isEmpty() && seen[0] != null) J.put(data, "note", "First connection: server key pinned (" + seen[0] + ")");
                if (timedOut) return ToolResult.of(ToolResult.Status.TIMED_OUT, "Remote command timed out");
                return exit == 0 ? ToolResult.ok("Exit code 0", data) : ToolResult.fail("Exit code " + exit, data);
            } finally {
                inv.cancel.removeListener(kill);
                s.disconnect();
            }
        }
    }

    /** Trust-on-first-use host key pinning by SHA-256 fingerprint. */
    static final class PinningRepository implements HostKeyRepository {
        private final String pinned;
        private final String[] seen;

        PinningRepository(String pinned, String[] seen) {
            this.pinned = pinned == null ? "" : pinned;
            this.seen = seen;
        }

        @Override
        public int check(String host, byte[] key) {
            String fp = fingerprint(key);
            seen[0] = fp;
            if (pinned.isEmpty()) return OK;
            return pinned.equals(fp) ? OK : CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "yourfriend-pinned";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }

        static String fingerprint(byte[] key) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(key);
                return "SHA256:" + android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            } catch (Exception e) {
                return "";
            }
        }
    }

    static final class NoPromptUserInfo implements UserInfo {
        private final String secret;

        NoPromptUserInfo(String secret) {
            this.secret = secret;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return secret;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
        }
    }
}
