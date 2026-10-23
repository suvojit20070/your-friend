package com.suvo.agent.core.tools;

import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONObject;

/** Everything a tool needs for one execution. Arguments are already schema-validated. */
public final class ToolInvocation {

    public interface Progress {
        void update(String message);

        Progress NONE = m -> {
        };
    }

    public final String callId;
    public final String taskId;
    public final JSONObject args;
    public final CancellationToken cancel;
    public final Progress progress;

    public ToolInvocation(String callId, String taskId, JSONObject args, CancellationToken cancel, Progress progress) {
        this.callId = callId;
        this.taskId = taskId;
        this.args = args;
        this.cancel = cancel;
        this.progress = progress == null ? Progress.NONE : progress;
    }

    public String str(String key) {
        return args.optString(key, "");
    }

    public String str(String key, String def) {
        return args.has(key) ? args.optString(key, def) : def;
    }

    public long num(String key, long def) {
        return args.has(key) ? args.optLong(key, def) : def;
    }

    public double dbl(String key, double def) {
        return args.has(key) ? args.optDouble(key, def) : def;
    }

    public boolean bool(String key, boolean def) {
        return args.has(key) ? args.optBoolean(key, def) : def;
    }
}
