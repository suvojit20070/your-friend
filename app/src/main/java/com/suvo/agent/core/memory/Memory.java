package com.suvo.agent.core.memory;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Minimal long-term memory: a plain text block the agent can read and append to.
 * (Phase 1. Later: structured per-fact store in Room + search.)
 */
public class Memory {

    private final File file;

    public Memory(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "memory.txt");
    }

    public synchronized String read() {
        try {
            if (!file.exists()) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized void append(String line) {
        try {
            String cur = read();
            String next = (cur.isEmpty() ? "" : cur + "\n") + line.trim();
            Files.write(file.toPath(), next.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {}
    }

    public synchronized void overwrite(String content) {
        try {
            Files.write(file.toPath(), (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {}
    }

    public synchronized void clear() {
        try { if (file.exists()) file.delete(); } catch (Exception ignore) {}
    }
}
