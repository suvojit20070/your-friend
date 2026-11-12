package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.code.CodeSandbox;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * run_code ("code mode"): instead of one tool call per step — each a full round trip that sends
 * the whole conversation again and puts every intermediate result into it — the agent writes one
 * small JavaScript script that calls the tools itself, loops, filters and combines, and only what
 * the script returns comes back. Every tool call inside goes through the normal pipeline
 * (validation, risk, the user's approval), so a script can do nothing a single call could not.
 */
public final class RunCodeTool extends BaseTool {

    public static final String SETTING = "code_mode";
    /** Send most tools as one-line signatures (used through run_code) instead of full schemas. */
    public static final String COMPACT = "code_mode_compact";

    /** Not callable from a script (itself; things that start their own runs or would wait on this one). */
    private static final Set<String> NOT_IN_SCRIPTS = new HashSet<>(Arrays.asList("run_code", "use_tool", "delegate_task"));

    /** Always offered as real tools, also in compact mode. */
    public static final Set<String> ALWAYS_NATIVE = new HashSet<>(Arrays.asList("run_code", "delegate_task", "load_skill", "memory",
            "record_mistake", "view_image", "list_tools", "wait", "use_tool", "schedule_task"));

    public RunCodeTool(AppGraph g) {
        super(g, ToolSpec.builder("run_code")
                .description("Code mode: run a JavaScript script that calls your tools itself. ONLY for a job that needs THREE OR "
                        + "MORE tool calls, a loop, or a lot of data to filter (read 20 chats and answer some, check many files, "
                        + "search then open the best results…): then it is ONE run instead of many calls and only what you return "
                        + "comes back. NEVER wrap one or two calls in a script — writing the script costs more tokens than the "
                        + "call: call the tool directly (or through use_tool). The code is the body of an async function. Every tool is "
                        + "tools.<name>({...same arguments as the tool...}) and returns a Promise: ALWAYS await it. It resolves "
                        + "to the tool's result object (its data plus message) and throws an Error when the tool fails "
                        + "(try/catch if you want to go on). Also: tools.list() (names), tools.docs(name) (description + "
                        + "schema), console.log(...) (shown to you), sleep(ms). Return a small JSON-able value: the answer, "
                        + "counts, the few items that matter — not the raw data. Modern JavaScript, no network, files or "
                        + "imports (only tools). Actions that need the user's approval still ask.")
                .schema("{\"type\":\"object\",\"properties\":{"
                        + "\"code\":{\"type\":\"string\",\"minLength\":1,\"description\":\"body of an async function, e.g. "
                        + "const r = await tools.shell({command:'ls'}); return r.stdout;\"},"
                        + "\"timeout_s\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":900,\"default\":300}"
                        + "},\"required\":[\"code\"],\"additionalProperties\":false}")
                .risk(RiskLevel.LOW).backend("code").timeoutMs(16 * 60_000).build());
    }

    @Override
    public String unavailableReason() {
        return g.settings.bool(SETTING, true) ? null : "Code mode is off (Settings)";
    }

    @Override
    public String describeCall(JSONObject a) {
        String c = a.optString("code").trim().replaceAll("\\s+", " ");
        return "Run a script: " + (c.length() > 140 ? c.substring(0, 140) + "…" : c);
    }

    /** The tools a script may call right now. */
    private List<Tool> callable(com.suvo.agent.core.tools.ToolExecutor exec) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : exec.registry().available(g.capabilities)) if (!NOT_IN_SCRIPTS.contains(t.spec().name)) out.add(t);
        return out;
    }

    @Override
    public ToolResult execute(ToolInvocation inv) throws Exception {
        String code = inv.str("code");
        if (code.contains("tools.") && !code.contains("await") && !code.contains(".then(")) {
            return ToolResult.fail("tools.<name>() returns a Promise: write `const r = await tools.name({...})`. Nothing was run.");
        }
        // A team member's script uses that member's own tools (and nothing more).
        final com.suvo.agent.core.tools.ToolExecutor exec = com.suvo.agent.agent.Team.executorFor(g, inv.taskId);
        List<Tool> tools = callable(exec);
        List<String> names = new ArrayList<>();
        for (Tool t : tools) names.add(t.spec().name);
        final JSONArray log = new JSONArray();
        final int[] n = {0};
        CodeSandbox.Caller caller = (name, argsJson, ok) -> {
            try {
                if ("__list".equals(name)) return new JSONArray(names).toString();
                if ("__docs".equals(name)) {
                    Tool t = exec.registry().get(new JSONObject(argsJson).optString("name"));
                    if (t == null) {
                        ok[0] = false;
                        return JSONObject.quote("No tool with that name: see tools.list()");
                    }
                    return J.obj("name", t.spec().name, "description", t.spec().description, "arguments", t.spec().inputSchema).toString();
                }
                if (NOT_IN_SCRIPTS.contains(name)) {
                    ok[0] = false;
                    return JSONObject.quote(name + " cannot be called from a script: call it directly");
                }
                int i;
                synchronized (n) {
                    i = ++n[0];
                }
                inv.progress.update(name + " (" + i + ")");
                long t0 = System.currentTimeMillis();
                ToolResult r = exec.execute(new ToolCall(inv.callId + "_c" + i, name, argsJson), inv.taskId, inv.cancel, inv.progress);
                boolean good = r.status == ToolResult.Status.SUCCEEDED;
                synchronized (log) {
                    if (log.length() < 80) log.put(name + (good ? " ok " : " FAILED ") + (System.currentTimeMillis() - t0) + "ms");
                }
                if (!good) {
                    ok[0] = false;
                    return JSONObject.quote(name + ": " + r.message);
                }
                JSONObject out = r.data == null ? new JSONObject() : new JSONObject(r.data.toString());
                if (!out.has("message")) out.put("message", r.message);
                return out.toString();
            } catch (Exception e) {
                ok[0] = false;
                return JSONObject.quote(name + ": " + e.getMessage());
            }
        };
        long timeout = Math.max(5, Math.min(900, inv.num("timeout_s", 300))) * 1000;
        CodeSandbox.Result r = CodeSandbox.run(g.ctx, code, names, caller, 80, timeout, inv.cancel);
        String logs = r.logs.toString().trim();
        JSONObject data = new JSONObject();
        data.put("tool_calls", log);
        if (!logs.isEmpty()) data.put("console", logs.length() > 6000 ? logs.substring(0, 6000) + "…" : logs);
        if (!r.ok) {
            return ToolResult.fail("Script failed: " + clean(r.value) + (r.calls > 0 ? " — " + r.calls
                    + " tool call(s) had already run (see tool_calls): do not repeat the ones that worked" : ""), data);
        }
        String v = r.value;
        boolean cut = v.length() > 16000;
        if (cut) v = v.substring(0, 16000);
        Object parsed;
        try {
            parsed = cut ? v : new org.json.JSONTokener(v).nextValue();
        } catch (Exception e) {
            parsed = v;
        }
        data.put("result", parsed == null ? JSONObject.NULL : parsed);
        if (cut) data.put("note", "The returned value was cut at 16,000 characters: return less (filter / summarize in the script)");
        ToolResult done = ToolResult.ok("Script finished (" + log.length() + " tool call(s))", data);
        // A script around a single call is the expensive way: say so, so the next one goes directly.
        if (log.length() <= 1) {
            done = done.withNote("This script made " + log.length() + " tool call: that costs MORE tokens than calling the tool "
                    + "directly. Next time call the tool itself (or use_tool {name, args}); keep run_code for 3+ calls, loops or "
                    + "filtering a lot of data.");
        }
        return done;
    }

    // ------------------------------------------------------------------ use_tool (one call, compact mode)

    /**
     * One direct call of a tool that is only listed as a one-line signature (compact mode): the
     * cheap way for a single call — no script to write, the tool's own result comes back as it is.
     */
    public static final class UseTool extends BaseTool {
        public UseTool(AppGraph g) {
            super(g, ToolSpec.builder("use_tool")
                    .description("Call ONE of your listed tools directly: name + its arguments. This is the way for a single call "
                            + "(cheaper than a script). docs=true returns the tool's full description and argument schema instead "
                            + "of running it. For three or more calls, loops or filtering a lot of data use run_code.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"name\":{\"type\":\"string\",\"minLength\":1,\"description\":\"tool name from your tool list\"},"
                            + "\"args\":{\"type\":\"object\",\"description\":\"the tool's arguments\"},"
                            + "\"docs\":{\"type\":\"boolean\",\"default\":false}"
                            + "},\"required\":[\"name\"],\"additionalProperties\":false}")
                    .risk(RiskLevel.READ_ONLY).backend("code").timeoutMs(46 * 60_000L).build());
        }

        @Override
        public String unavailableReason() {
            return g.settings.bool(SETTING, true) && g.settings.bool(COMPACT, false) ? null : "Only with the short tool list (Settings)";
        }

        @Override
        public String describeCall(JSONObject a) {
            return a.optString("name") + " " + a.optJSONObject("args");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            String name = inv.str("name").trim();
            if (name.startsWith("tools.")) name = name.substring(6);
            com.suvo.agent.core.tools.ToolExecutor exec = com.suvo.agent.agent.Team.executorFor(g, inv.taskId);
            Tool t = exec.registry().get(name);
            if (t == null || "use_tool".equals(name)) return ToolResult.fail("No tool \"" + name + "\": use a name from your tool list");
            if (inv.bool("docs", false)) {
                return ToolResult.ok(name, J.obj("name", name, "description", t.spec().description, "arguments", t.spec().inputSchema));
            }
            JSONObject args = inv.args.optJSONObject("args");
            // The real call: validated, risk-checked and approved like any other (this wrapper itself asks nothing).
            return exec.execute(new ToolCall(inv.callId + "_u", name, args == null ? "{}" : args.toString()), inv.taskId, inv.cancel, inv.progress);
        }
    }

    /** The system-prompt section that lists the tools not sent as functions (compact mode). */
    public static String compactSection(List<Tool> rest) {
        return "\n\n# Your other tools (not sent as separate functions)\nTwo ways to use them:\n"
                + "- ONE call → use_tool {name, args}. Never write a script for a single call: a script costs more tokens.\n"
                + "- Three or more calls, a loop, or a lot of data to filter → ONE run_code script with `await tools.<name>({...})`.\n"
                + "`?` = optional. When you are not sure about a tool's arguments, ask once: use_tool {name, docs:true}.\n"
                + signatures(rest);
    }

    /** Compact mode: only the always-native tools stay as full function definitions. */
    public static JSONArray compactFilter(JSONArray all) {
        JSONArray keep = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            JSONObject fn = t == null ? null : t.optJSONObject("function");
            if (fn != null && ALWAYS_NATIVE.contains(fn.optString("name"))) keep.put(t);
        }
        return keep;
    }

    public static boolean compact(AppGraph g) {
        return g.settings.bool(SETTING, true) && g.settings.bool(COMPACT, false);
    }

    /** Worker stack traces: the blob address out, line numbers as in the agent's own code. */
    static String clean(String err) {
        Matcher m = Pattern.compile("\\(?blob:\\S+?:(\\d+):(\\d+)\\)?").matcher(err);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int line = Math.max(1, Integer.parseInt(m.group(1)) - 2);
            m.appendReplacement(sb, "line " + line);
        }
        m.appendTail(sb);
        String s = sb.toString().replaceAll("\\n\\s+at line 1\\b.*", "").replace("Uncaught ", "");
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    // ------------------------------------------------------------------ compact tool list (system prompt)

    /** One line per tool: name(arg: type, …) — first sentence of what it does. */
    public static String signatures(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools) {
            ToolSpec s = t.spec();
            sb.append("- tools.").append(s.name).append("({");
            JSONObject props = s.inputSchema == null ? null : s.inputSchema.optJSONObject("properties");
            JSONArray req = s.inputSchema == null ? null : s.inputSchema.optJSONArray("required");
            Set<String> required = new HashSet<>();
            for (int i = 0; req != null && i < req.length(); i++) required.add(req.optString(i));
            boolean first = true;
            if (props != null) {
                for (Iterator<String> it = props.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject p = props.optJSONObject(k);
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(k).append(required.contains(k) ? "" : "?").append(": ").append(type(p));
                }
            }
            sb.append("}) — ").append(firstSentence(s.description)).append('\n');
        }
        return sb.toString();
    }

    private static String type(JSONObject p) {
        if (p == null) return "any";
        JSONArray en = p.optJSONArray("enum");
        if (en != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < en.length(); i++) {
                if (i > 0) sb.append('|');
                sb.append('"').append(en.optString(i)).append('"');
            }
            return sb.toString();
        }
        String t = p.optString("type", "any");
        if ("array".equals(t)) return type(p.optJSONObject("items")) + "[]";
        if ("integer".equals(t)) return "number";
        return t;
    }

    private static String firstSentence(String d) {
        if (d == null) return "";
        String s = d.replaceAll("\\s+", " ").trim();
        int dot = s.indexOf(". ");
        if (dot > 20 && dot < 200) s = s.substring(0, dot + 1);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
