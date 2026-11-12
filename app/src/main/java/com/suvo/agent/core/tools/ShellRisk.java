package com.suvo.agent.core.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * How risky a command in the app's own shell is, so managing files with commands asks no more
 * often than the file tools did: only reading in the workspace = read-only; creating, copying,
 * moving and editing files there = low (like writing a file); anything else, or a path that
 * leaves the workspace = medium. Pure Java; deliberately cautious (unsure = medium).
 */
public final class ShellRisk {

    private ShellRisk() {
    }

    private static final Set<String> READ = new HashSet<>(Arrays.asList(
            "ls", "cat", "head", "tail", "sed", "grep", "egrep", "fgrep", "find", "wc", "stat", "diff", "cmp", "file", "du", "df",
            "pwd", "echo", "printf", "base64", "md5sum", "sha1sum", "sha256sum", "sort", "uniq", "cut", "tr", "nl", "od", "xxd",
            "hexdump", "realpath", "basename", "dirname", "tree", "true", "test", "[", "cd", "date", "expr", "seq", "awk", "strings",
            "unzip", "zipinfo", "tar", "readlink", "which", "type", "column", "fold", "rev", "tac", "paste", "comm", "join"));

    private static final Set<String> WRITE = new HashSet<>(Arrays.asList(
            "mkdir", "cp", "mv", "touch", "rm", "rmdir", "tee", "ln", "chmod", "gzip", "gunzip", "zip", "split", "truncate"));

    /**
     * @param workspace the workspace's absolute path (paths inside it are fine)
     */
    public static RiskLevel local(String command, String workspace) {
        if (command == null) return RiskLevel.MEDIUM;
        String c = command;
        // Heredoc bodies are file content, not commands.
        c = c.replaceAll("(?s)<<-?\\s*'?\"?(\\w+)'?\"?\\n.*?\\n\\1(\\n|$)", "<<DOC\n");
        if (c.contains("..") || c.contains("$(") || c.contains("`")) return RiskLevel.MEDIUM;
        boolean writes = false;
        for (String part : c.split("&&|\\|\\||;|\\||\\n")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] w = p.split("\\s+");
            int i = 0;
            while (i < w.length && w[i].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) i++; // VAR=value prefixes
            if (i >= w.length) continue;
            String cmd = w[i];
            if (cmd.contains("/")) return RiskLevel.MEDIUM;
            for (int k = i + 1; k < w.length; k++) {
                String a = w[k].replaceAll("^[<>]+", "").replace("\"", "").replace("'", "");
                if (a.startsWith("/") && (workspace == null || !a.startsWith(workspace))) {
                    if (!a.equals("/dev/null")) return RiskLevel.MEDIUM;
                }
                if (a.startsWith("~") || a.startsWith("$HOME/..")) return RiskLevel.MEDIUM;
            }
            boolean redirect = p.matches(".*[^0-9&]>{1,2}\\s*[^&\\s].*") && !p.matches(".*>\\s*/dev/null.*") || p.contains(">>");
            if (READ.contains(cmd)) {
                if (cmd.equals("sed") && p.matches(".*\\s-[a-zA-Z]*i.*")) writes = true;
                else if (cmd.equals("find") && (p.contains("-delete") || p.contains("-exec") || p.contains("-ok"))) return RiskLevel.MEDIUM;
                else if (cmd.equals("awk") && p.contains("system(")) return RiskLevel.MEDIUM;
                else if ((cmd.equals("tar") || cmd.equals("unzip")) && !p.matches(".*\\s-[a-zA-Z]*[tl].*")) writes = true;
                if (redirect) writes = true;
            } else if (WRITE.contains(cmd)) {
                if (cmd.equals("rm") && p.matches(".*\\s-[a-zA-Z]*[rR].*")) return RiskLevel.MEDIUM; // whole folders: ask
                if (cmd.equals("chmod") && p.contains("-R")) return RiskLevel.MEDIUM;
                writes = true;
            } else {
                return RiskLevel.MEDIUM;
            }
        }
        return writes ? RiskLevel.LOW : RiskLevel.READ_ONLY;
    }
}
