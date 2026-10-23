package com.suvo.agent.terminal;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.tools.J;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects which language runtimes actually exist. Android itself only provides /system/bin/sh and
 * toybox; Python, pip, Node.js, clang, OpenJDK and git come from Termux packages.
 */
public final class RuntimeManager {

    public static final class Runtime {
        public final String id;
        public final String label;
        public final String backend;
        public final boolean installed;
        public final String version;
        public final String installHint;

        Runtime(String id, String label, String backend, boolean installed, String version, String installHint) {
            this.id = id;
            this.label = label;
            this.backend = backend;
            this.installed = installed;
            this.version = version;
            this.installHint = installHint;
        }
    }

    /** id → [label, version command, termux package to install]. */
    private static final String[][] TERMUX_RUNTIMES = {
            {"python", "Python 3", "python3 --version 2>&1", "python"},
            {"pip", "pip", "pip --version 2>&1 | cut -d' ' -f1-2", "python-pip"},
            {"node", "Node.js", "node --version 2>&1", "nodejs"},
            {"npm", "npm", "npm --version 2>&1", "nodejs"},
            {"cpp", "C++ (clang++)", "clang++ --version 2>&1 | head -1", "clang"},
            {"java", "Java (OpenJDK)", "java -version 2>&1 | head -1", "openjdk-17"},
            {"git", "Git", "git --version 2>&1", "git"},
            {"curl", "curl", "curl --version 2>&1 | head -1", "curl"},
            {"py_requests", "Python requests", "python3 -c 'import requests;print(requests.__version__)' 2>&1", "pip install requests"},
            {"py_bs4", "Python bs4", "python3 -c 'import bs4;print(bs4.__version__)' 2>&1", "pip install beautifulsoup4"},
    };

    private final AppGraph g;
    private final Map<String, Runtime> cache = new LinkedHashMap<>();
    private volatile long lastCheck;
    private volatile String lastError = "";

    public RuntimeManager(AppGraph g) {
        this.g = g;
    }

    /** Runs detection (blocking; call from a background thread). */
    public synchronized Map<String, Runtime> refresh(CancellationToken cancel) {
        cache.clear();
        ExecResult sh = g.localShell().run("echo ok; toybox --version 2>/dev/null || echo toybox", null, 10_000, cancel);
        cache.put("sh", new Runtime("sh", "Android shell (sh + toybox)", "local", sh.ok(), sh.ok() ? sh.stdout.replace("ok", "").trim() : "", ""));
        String why = g.termux.unavailableReason();
        if (why != null) {
            lastError = why;
            for (String[] r : TERMUX_RUNTIMES) {
                cache.put(r[0], new Runtime(r[0], r[1], "termux", false, "", "Install Termux, then: " + hint(r[3])));
            }
        } else {
            StringBuilder script = new StringBuilder();
            for (String[] r : TERMUX_RUNTIMES) {
                String bin = r[2].split(" ")[0];
                script.append("echo \"@@").append(r[0]).append("@@$(");
                if (r[0].startsWith("py_")) script.append(r[2]);
                else script.append("command -v ").append(bin).append(" >/dev/null 2>&1 && ").append(r[2]).append(" || echo MISSING");
                script.append(")\"\n");
            }
            ExecResult res = g.termux.run(script.toString(), null, 60_000, cancel);
            lastError = res.ran() ? "" : res.error;
            Map<String, String> found = new LinkedHashMap<>();
            for (String line : res.stdout.split("\n")) {
                if (!line.startsWith("@@")) continue;
                int end = line.indexOf("@@", 2);
                if (end < 0) continue;
                found.put(line.substring(2, end), line.substring(end + 2).trim());
            }
            for (String[] r : TERMUX_RUNTIMES) {
                String v = found.get(r[0]);
                boolean ok = v != null && !v.isEmpty() && !v.contains("MISSING") && !v.contains("No module") && !v.contains("Error") && !v.contains("not found");
                cache.put(r[0], new Runtime(r[0], r[1], "termux", ok, ok ? v : "", hint(r[3])));
            }
        }
        lastCheck = System.currentTimeMillis();
        return new LinkedHashMap<>(cache);
    }

    private static String hint(String pkg) {
        return pkg.startsWith("pip ") ? pkg : "pkg install " + pkg;
    }

    public synchronized Map<String, Runtime> cached() {
        return new LinkedHashMap<>(cache);
    }

    /** Whether a runtime is known to be installed (null = unknown, never checked). */
    public synchronized Boolean isInstalled(String id) {
        Runtime r = cache.get(id);
        return r == null ? null : r.installed;
    }

    public synchronized JSONObject cachedStatus() {
        JSONObject o = new JSONObject();
        if (cache.isEmpty()) {
            J.put(o, "note", "Not checked yet. The user can check runtimes in Terminal, or you can run shell with backend=termux.");
            return o;
        }
        for (Runtime r : cache.values()) J.put(o, r.id, r.installed ? r.version : "not installed (" + r.installHint + ")");
        if (!lastError.isEmpty()) J.put(o, "termux", lastError);
        return o;
    }

    public long lastCheck() {
        return lastCheck;
    }

    public String lastError() {
        return lastError;
    }

    /** Installs a Termux package or pip package (blocking). */
    public ExecResult install(String spec, CancellationToken cancel) {
        String cmd;
        if (spec.startsWith("pip install ")) {
            String pkgs = spec.substring("pip install ".length()).trim();
            if (!pkgs.matches("[A-Za-z0-9_.\\-=<> \\[\\]]+")) return ExecResult.backendError("termux", "Invalid package name");
            cmd = "pip install " + pkgs;
        } else {
            String p = spec.startsWith("pkg install ") ? spec.substring("pkg install ".length()).trim() : spec.trim();
            if (!p.matches("[A-Za-z0-9_.+\\- ]+")) return ExecResult.backendError("termux", "Invalid package name");
            cmd = "yes | pkg install " + p;
        }
        return g.termux.run(cmd, null, 15 * 60_000L, cancel);
    }
}
