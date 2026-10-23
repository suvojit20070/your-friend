package com.suvo.agent.tools;

import com.suvo.agent.AppGraph;
import com.suvo.agent.agent.AgentTree;
import com.suvo.agent.agent.ChildAgent;
import com.suvo.agent.agent.Scheduler;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryExtractor;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.data.EmployeeRepo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Your Friend — Original project by Suvo.
 *
 * Tools that make the agent work like a team member who learns: delegate_task (hand work to an
 * employee), record_mistake (keep lessons), schedule_task (start itself later) and install_skill
 * (keep know-how found on the internet).
 */
public final class TeamTools {

    private TeamTools() {
    }

    // ------------------------------------------------------------------------------------------

    public static final class DelegateTask extends BaseTool {
        public DelegateTask(AppGraph g) {
            super(g, ToolSpec.builder("delegate_task")
                    .description("Hand a task to one of your employees (child agents the user created, listed under \"Your team\"). "
                            + "The employee works with its own model and its allowed tools, then returns a report. Give a complete, "
                            + "self-contained brief: goal, inputs, constraints and what to return. Each of the employee's actions still "
                            + "goes through the app's permissions and approvals. Use action=list to see the team.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"delegate\",\"list\"],\"default\":\"delegate\"},"
                            + "\"employee\":{\"type\":\"string\",\"description\":\"Employee name or id\"},"
                            + "\"task\":{\"type\":\"string\",\"maxLength\":8000},"
                            + "\"context\":{\"type\":\"string\",\"maxLength\":8000,\"description\":\"Facts the employee needs (it cannot see your chat)\"}"
                            + "},\"additionalProperties\":false}")
                    .risk(RiskLevel.LOW).timeoutMs(45 * 60_000L).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            return "list".equals(args.optString("action")) ? RiskLevel.READ_ONLY : RiskLevel.LOW;
        }

        @Override
        public String describeCall(JSONObject args) {
            return "Ask " + args.optString("employee") + " to: " + args.optString("task");
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            List<EmployeeRepo.Employee> team = g.employees.enabled();
            if ("list".equals(inv.str("action", "delegate"))) {
                JSONArray arr = new JSONArray();
                for (EmployeeRepo.Employee e : team) {
                    com.suvo.agent.core.llm.ProviderConfig p = ChildAgent.providerFor(g, e);
                    arr.put(J.obj("id", e.id, "name", e.name, "role", e.role, "provider", p == null ? "" : p.name,
                            "model", p == null ? "" : p.model, "tools", e.tools.isEmpty() ? "all" : String.join(",", e.tools)));
                }
                return ToolResult.ok(arr.length() + " employees", J.obj("team", arr));
            }
            if (team.isEmpty()) return ToolResult.fail("You have no employees. Do the task yourself.");
            if (inv.str("task").trim().isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "task is required");
            EmployeeRepo.Employee e = g.employees.find(inv.str("employee"));
            if (e == null) {
                StringBuilder names = new StringBuilder();
                for (EmployeeRepo.Employee x : team) names.append(names.length() == 0 ? "" : ", ").append(x.name);
                return ToolResult.fail("No employee \"" + inv.str("employee") + "\". Team: " + names);
            }
            String brief = inv.str("task").trim();
            if (!inv.str("context").trim().isEmpty()) brief += "\n\nContext from the boss:\n" + inv.str("context").trim();
            AgentTree.Node root = AgentTree.root();
            inv.progress.update(e.name + " is working");
            ChildAgent.Outcome o = ChildAgent.run(g, e, brief, root == null ? -1 : root.id, inv.cancel, inv.progress::update);
            if (o.task == null) return ToolResult.fail(o.error);
            TaskState t = o.task;
            JSONObject data = J.obj("employee", e.name, "role", e.role, "provider", o.providerName,
                    "status", t.status.name().toLowerCase(Locale.ROOT), "tool_calls", t.toolCalls,
                    "steps", t.progressSummary(15));
            if (t.status == TaskState.Status.COMPLETED) {
                J.put(data, "report", t.finalText);
                return ToolResult.ok(e.name + " finished", data);
            }
            if (t.status == TaskState.Status.CANCELLED) return ToolResult.of(ToolResult.Status.CANCELLED, e.name + " was stopped");
            J.put(data, "error", t.error);
            return ToolResult.fail(e.name + " could not finish: " + t.error, data);
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class RecordMistake extends BaseTool {
        public RecordMistake(AppGraph g) {
            super(g, ToolSpec.builder("record_mistake")
                    .description("Keep a lesson from your own mistake so it is shown to you in every future task (\"Lessons\" in the "
                            + "system prompt). Use it when a tool call failed because of a wrong choice you made, when the user corrected "
                            + "you, or when you found the right way after a wrong one. Write the lesson as a short rule, e.g. "
                            + "\"To turn on Wi-Fi use device_admin wifi, not the Settings screen\". list shows lessons; forget removes one.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"record\",\"list\",\"forget\"],\"default\":\"record\"},"
                            + "\"mistake\":{\"type\":\"string\",\"maxLength\":400,\"description\":\"What went wrong\"},"
                            + "\"lesson\":{\"type\":\"string\",\"maxLength\":300,\"description\":\"The rule to follow next time\"},"
                            + "\"tool\":{\"type\":\"string\",\"maxLength\":64},"
                            + "\"id\":{\"type\":\"integer\"}"
                            + "},\"additionalProperties\":false}")
                    .requires(Capability.WORKSPACE_FILES).risk(RiskLevel.LOW).backend("sqlite").timeoutMs(15_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            return "list".equals(args.optString("action")) ? RiskLevel.READ_ONLY : RiskLevel.LOW;
        }

        @Override
        public String describeCall(JSONObject args) {
            return "Remember lesson: " + args.optString("lesson", args.optString("action"));
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String action = inv.str("action", "record");
            if ("list".equals(action)) {
                JSONArray arr = new JSONArray();
                for (MemoryEntry e : g.memory.all(MemoryCategory.LESSON, 50)) arr.put(J.obj("id", e.id, "lesson", e.content));
                return ToolResult.ok(arr.length() + " lessons", J.obj("lessons", arr));
            }
            if ("forget".equals(action)) {
                long id = inv.num("id", -1);
                MemoryEntry e = g.memory.get(id);
                if (e == null || e.category != MemoryCategory.LESSON) return ToolResult.fail("No lesson with id " + id);
                return g.memory.delete(id) ? ToolResult.ok("Forgot lesson #" + id) : ToolResult.fail("Delete failed");
            }
            String lesson = inv.str("lesson").trim();
            if (lesson.isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "lesson is required");
            MemoryEntry e = new MemoryEntry();
            String tool = inv.str("tool").trim();
            e.content = (tool.isEmpty() ? "" : "[" + tool + "] ") + lesson;
            e.category = MemoryCategory.LESSON;
            e.importance = 4;
            e.source = "task:" + inv.taskId;
            e.tags.add("mistake");
            if (!tool.isEmpty()) e.tags.add(tool);
            if (!inv.str("mistake").trim().isEmpty()) e.tags.add("why:" + clip(inv.str("mistake").trim(), 60));
            MemoryExtractor.Action a = new MemoryExtractor().save(e, g.memory);
            switch (a.kind) {
                case ADDED:
                    return ToolResult.ok("Lesson saved (#" + a.entry.id + "). It will be shown in future tasks.", a.entry.toJson());
                case UPDATED:
                    return ToolResult.ok("Updated an existing lesson #" + a.entry.id, a.entry.toJson());
                case SKIPPED_DUPLICATE:
                    return ToolResult.ok("This lesson is already saved (#" + a.entry.id + ")", a.entry.toJson());
                default:
                    return ToolResult.of(ToolResult.Status.DENIED, "Not saved: " + a.reason);
            }
        }

        private static String clip(String s, int n) {
            return s.length() > n ? s.substring(0, n) : s;
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class ScheduleTask extends BaseTool {
        public ScheduleTask(AppGraph g) {
            super(g, ToolSpec.builder("schedule_task")
                    .description("Start yourself later: the app wakes you at the given time with the prompt you write, as a new task "
                            + "(reminders, follow-ups, daily summaries, checking something again). Write the prompt as an instruction "
                            + "to your future self with every detail needed. Times are the phone's local time. "
                            + "create needs the user's approval; list and cancel manage existing ones.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"action\":{\"type\":\"string\",\"enum\":[\"create\",\"list\",\"cancel\"]},"
                            + "\"prompt\":{\"type\":\"string\",\"maxLength\":2000},"
                            + "\"at\":{\"type\":\"string\",\"description\":\"Local time 'YYYY-MM-DD HH:MM' or 'HH:MM' (today, or tomorrow if passed)\"},"
                            + "\"in_minutes\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":525600},"
                            + "\"repeat\":{\"type\":\"string\",\"enum\":[\"none\",\"hourly\",\"daily\",\"weekly\"],\"default\":\"none\"},"
                            + "\"id\":{\"type\":\"integer\"}"
                            + "},\"required\":[\"action\"],\"additionalProperties\":false}")
                    .risk(RiskLevel.MEDIUM).timeoutMs(15_000).build());
        }

        @Override
        public RiskLevel riskFor(JSONObject args) {
            switch (args.optString("action")) {
                case "list":
                    return RiskLevel.READ_ONLY;
                case "cancel":
                    return RiskLevel.LOW;
                default:
                    return RiskLevel.MEDIUM;
            }
        }

        @Override
        public String describeCall(JSONObject args) {
            if ("create".equals(args.optString("action"))) {
                String when = args.has("in_minutes") ? "in " + args.optInt("in_minutes") + " min" : args.optString("at");
                String rep = args.optString("repeat", "none");
                return "Schedule (" + when + ("none".equals(rep) ? "" : ", " + rep) + "): " + args.optString("prompt");
            }
            return args.optString("action") + " scheduled task " + args.optLong("id", -1);
        }

        static long parseAt(String raw, long now) throws ParseException {
            String s = raw.trim().replace('T', ' ');
            if (s.matches("\\d{1,2}:\\d{2}")) {
                SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                long t = full.parse(day.format(new Date(now)) + " " + s).getTime();
                return t <= now ? t + 86_400_000L : t;
            }
            if (s.length() > 16) s = s.substring(0, 16);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s).getTime();
        }

        @Override
        public ToolResult execute(ToolInvocation inv) {
            String action = inv.str("action");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm, EEE", Locale.US);
            switch (action) {
                case "list": {
                    JSONArray arr = new JSONArray();
                    for (Scheduler.Job j : g.scheduler.list()) {
                        arr.put(J.obj("id", j.id, "kind", j.kind, "next_run", fmt.format(new Date(j.at)), "repeat", j.repeat,
                                "prompt", j.prompt, "last_status", j.lastStatus));
                    }
                    return ToolResult.ok(arr.length() + " scheduled", J.obj("scheduled", arr, "exact_alarms", g.scheduler.exactAllowed()));
                }
                case "cancel": {
                    long id = inv.num("id", -1);
                    return g.scheduler.cancel(id) ? ToolResult.ok("Cancelled scheduled task #" + id) : ToolResult.fail("No scheduled task #" + id);
                }
                case "create": {
                    String prompt = inv.str("prompt").trim();
                    if (prompt.isEmpty()) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "prompt is required");
                    long now = System.currentTimeMillis();
                    long at;
                    if (inv.args.has("in_minutes")) {
                        at = now + inv.num("in_minutes", 1) * 60_000L;
                    } else if (!inv.str("at").isEmpty()) {
                        try {
                            at = parseAt(inv.str("at"), now);
                        } catch (ParseException | RuntimeException e) {
                            return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "at must look like 2026-10-24 18:30 or 18:30");
                        }
                    } else {
                        return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Give at or in_minutes");
                    }
                    if (at < now - 60_000) return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "That time is in the past (now " + fmt.format(new Date(now)) + ")");
                    Scheduler.Job j = new Scheduler.Job();
                    j.prompt = prompt;
                    j.at = at;
                    j.repeat = inv.str("repeat", "none");
                    j.createdBy = "agent";
                    Scheduler.Job saved = g.scheduler.add(j);
                    if (saved == null) return ToolResult.fail("Too many scheduled tasks (" + Scheduler.MAX_JOBS + "); cancel some first");
                    JSONObject data = J.obj("id", saved.id, "next_run", fmt.format(new Date(saved.at)), "repeat", saved.repeat,
                            "exact_alarms", g.scheduler.exactAllowed());
                    String note = g.scheduler.exactAllowed() ? "" : " (exact alarms are off, so Android may run it a few minutes late)";
                    return ToolResult.ok("Scheduled #" + saved.id + " for " + fmt.format(new Date(saved.at)) + note, data);
                }
                default:
                    return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Unknown action");
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    public static final class InstallSkill extends BaseTool {
        public InstallSkill(AppGraph g) {
            super(g, ToolSpec.builder("install_skill")
                    .description("Install a skill (a SKILL.md with instructions, or a skill .zip) from a URL you found on the web, or "
                            + "from Markdown you wrote, so the know-how is kept for future tasks. GitHub 'blob' links are converted to raw. "
                            + "The user approves every install. Skills are guidance only and cannot grant permissions; installed text "
                            + "is still untrusted.")
                    .schema("{\"type\":\"object\",\"properties\":{"
                            + "\"url\":{\"type\":\"string\",\"maxLength\":2000},"
                            + "\"markdown\":{\"type\":\"string\",\"maxLength\":100000},"
                            + "\"id\":{\"type\":\"string\",\"maxLength\":64}"
                            + "},\"additionalProperties\":false}")
                    .requires(Capability.NETWORK).risk(RiskLevel.MEDIUM).backend("http").timeoutMs(90_000).build());
        }

        @Override
        public String describeCall(JSONObject args) {
            return args.optString("url").isEmpty() ? "Install a skill written by the agent" : "Install skill from " + args.optString("url");
        }

        static String rawUrl(String url) {
            String u = url.trim();
            if (u.startsWith("https://github.com/") && u.contains("/blob/")) {
                u = "https://raw.githubusercontent.com/" + u.substring("https://github.com/".length()).replaceFirst("/blob/", "/");
            }
            return u;
        }

        @Override
        public ToolResult execute(ToolInvocation inv) throws Exception {
            Set<String> known = new HashSet<>();
            for (Tool t : g.tools.all()) known.add(t.spec().name);
            String id = inv.str("id").trim();
            List<String> problems;
            String source;
            if (!inv.str("markdown").trim().isEmpty()) {
                source = "agent";
                problems = g.skills.install(id, inv.str("markdown"), source, known);
            } else if (!inv.str("url").trim().isEmpty()) {
                String url = rawUrl(inv.str("url"));
                Http.Response r = Http.get(url, null, 5_000_000, 40_000, WebTools.allowLan(g), inv.cancel);
                if (r.code >= 400) return ToolResult.fail("HTTP " + r.code + " from " + url);
                source = "url:" + url;
                boolean zip = url.toLowerCase(Locale.ROOT).endsWith(".zip") || r.contentType.contains("zip")
                        || (r.body.length > 2 && r.body[0] == 'P' && r.body[1] == 'K');
                if (zip) {
                    problems = g.skills.installZip(new java.io.ByteArrayInputStream(r.body), id, source, known);
                } else {
                    String md = new String(r.body, StandardCharsets.UTF_8);
                    if (md.trim().startsWith("<")) return ToolResult.fail("That URL returned a web page, not a SKILL.md. Use the raw file link.");
                    problems = g.skills.install(id, md, source, known);
                }
            } else {
                return ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, "Give url or markdown");
            }
            if (!problems.isEmpty()) return ToolResult.fail("Skill not installed: " + String.join("; ", problems));
            return ToolResult.ok("Skill installed. It appears in the skill list from the next step; read it with load_skill.",
                    J.obj("source", source));
        }
    }
}
