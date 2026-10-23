package com.suvo.agent.terminal;

import org.json.JSONException;
import org.json.JSONObject;

/** Outcome of one command execution on any backend. */
public final class ExecResult {

    public final String backend;
    public final int exitCode;
    public final String stdout;
    public final String stderr;
    public final boolean timedOut;
    public final boolean cancelled;
    public final long durationMs;
    /** Backend-level failure (backend unavailable, permission missing...), empty when the command ran. */
    public final String error;

    public ExecResult(String backend, int exitCode, String stdout, String stderr, boolean timedOut, boolean cancelled,
                      long durationMs, String error) {
        this.backend = backend;
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
        this.cancelled = cancelled;
        this.durationMs = durationMs;
        this.error = error == null ? "" : error;
    }

    public static ExecResult backendError(String backend, String error) {
        return new ExecResult(backend, -1, "", "", false, false, 0, error);
    }

    public boolean ran() {
        return error.isEmpty();
    }

    public boolean ok() {
        return ran() && exitCode == 0 && !timedOut && !cancelled;
    }

    public JSONObject toJson(int maxChars) {
        JSONObject o = new JSONObject();
        try {
            o.put("backend", backend).put("exit_code", exitCode).put("duration_ms", durationMs);
            if (timedOut) o.put("timed_out", true);
            if (cancelled) o.put("cancelled", true);
            if (!error.isEmpty()) o.put("error", error);
            o.put("stdout", com.suvo.agent.core.util.TextUtil.headTail(stdout, maxChars));
            if (!stderr.isEmpty()) o.put("stderr", com.suvo.agent.core.util.TextUtil.headTail(stderr, maxChars / 2));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }
}
