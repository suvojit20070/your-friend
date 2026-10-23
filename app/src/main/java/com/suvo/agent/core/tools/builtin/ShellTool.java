package com.suvo.agent.core.tools.builtin;

import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolSchema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Run a shell command at ADB privilege level via Shizuku (no root required).
 * HIGH RISK — the agent core asks the user to confirm before each run.
 *
 * Uses Shizuku.newProcess(...) via reflection (it is a hidden-but-stable API),
 * so the module compiles against the public Shizuku artifact.
 */
public class ShellTool implements Tool {

    @Override public String name() { return "shell"; }

    @Override public ToolSchema schema() {
        try {
            JSONObject props = new JSONObject();
            props.put("command", new JSONObject().put("type", "string")
                    .put("description", "Shell command, e.g. 'pm list packages -3' or 'input keyevent 26'"));
            JSONObject params = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", new JSONArray().put("command"));
            return new ToolSchema(name(),
                    "Run a shell command at ADB level via Shizuku (no root). Powerful: packages, "
                            + "settings, input, file ops. The user confirms each run.",
                    params, "high");
        } catch (JSONException e) {
            return new ToolSchema(name(), "Run a shell command via Shizuku.", ToolSchema.emptyParams(), "high");
        }
    }

    @Override public boolean isAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public String run(JSONObject args) {
        String cmd = args.optString("command", "");
        if (cmd.isEmpty()) return "Error: command required.";
        try {
            // Shizuku.newProcess(String[] cmd, String[] env, String dir)
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process proc = (Process) m.invoke(null,
                    new String[]{"sh", "-c", cmd}, null, null);

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String l; int lines = 0;
                while ((l = r.readLine()) != null && lines++ < 300) out.append(l).append('\n');
            }
            try (BufferedReader e = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String l;
                while ((l = e.readLine()) != null) out.append("[err] ").append(l).append('\n');
            }
            int code = proc.waitFor();
            String s = out.toString().trim();
            return "exit " + code + (s.isEmpty() ? "" : "\n" + (s.length() > 3500 ? s.substring(0, 3500) + "…" : s));
        } catch (Throwable t) {
            return "Error: shell failed — " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage());
        }
    }
}
