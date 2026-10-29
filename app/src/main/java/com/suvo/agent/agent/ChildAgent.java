package com.suvo.agent.agent;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.agent.AgentConfig;
import com.suvo.agent.core.agent.AgentHost;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.AgentLoop;
import com.suvo.agent.core.agent.HistorySanitizer;
import com.suvo.agent.core.agent.PromptAssembler;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.EmployeeRepo;
import com.suvo.agent.data.Settings;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Your Friend — Original project by Suvo.
 *
 * Runs one employee (child agent) on a task the boss delegated. The employee uses its own LLM
 * provider and only the tools the user allowed for it: it gets its own tool registry, so a tool
 * that is not on its list cannot run even if the model asks for it. Approvals, autonomy mode and
 * device permissions are the same app-enforced ones the boss has. Its conversation lives only in
 * memory; the boss receives the report as the delegate_task result, and every step appears in the
 * live agent tree.
 */
public final class ChildAgent {

    /** Tools a child never gets: no further delegation, no scheduling on the boss's behalf. */
    public static final Set<String> BOSS_ONLY = new HashSet<>(Arrays.asList("delegate_task", "schedule_task"));

    public static final class Outcome {
        public TaskState task;
        public String error = "";
        public String providerName = "";
    }

    private ChildAgent() {
    }

    /** Provider the employee will use: its own, else the boss's (active) provider. */
    public static ProviderConfig providerFor(AppGraph g, EmployeeRepo.Employee e) {
        ProviderConfig p = e.providerId >= 0 ? g.providers.get(e.providerId) : null;
        return p != null && p.enabled ? p : g.providers.active();
    }

    public static Outcome run(AppGraph g, EmployeeRepo.Employee e, String brief, long parentNode,
                              CancellationToken parentCancel, java.util.function.Consumer<String> progress) {
        Outcome out = new Outcome();
        ProviderConfig p = providerFor(g, e);
        if (p == null) {
            out.error = "No LLM provider is set up for " + e.name + " (and no default provider).";
            return out;
        }
        String invalid = p.validate();
        if (invalid != null) {
            out.error = e.name + "'s provider \"" + p.name + "\" is not usable: " + invalid;
            return out;
        }
        out.providerName = p.name;
        LlmClient llm = new OpenAiCompatClient(p);

        ToolRegistry reg = new ToolRegistry();
        for (Tool t : g.tools.all()) {
            String n = t.spec().name;
            if (BOSS_ONLY.contains(n)) continue;
            if (!e.tools.isEmpty() && !e.tools.contains(n) && !"list_tools".equals(n)) continue;
            reg.register(t);
        }
        reg.setDisabledSet(g.tools.disabledNames());
        ToolExecutor exec = new ToolExecutor(reg, g.capabilities, g.policy, g.approvals, g.audit);
        exec.decorator = g.executor.decorator;

        AgentTree.Node node = AgentTree.startChild(parentNode, e.name, e.role.isEmpty() ? "Employee" : e.role, brief);
        CancellationToken cancel = parentCancel.child();
        TaskState task = new TaskState(-1, brief);
        AgentConfig cfg = g.settings.agentConfig();
        // 0 = the employee follows the boss's setting (which may be "no limit").
        if (e.maxSteps > 0) cfg.maxSteps = Math.max(3, Math.min(cfg.maxSteps, e.maxSteps));
        ChildHost host = new ChildHost(g, e, reg, llm.config().supportsVision);
        AgentListener listener = new AgentListener() {
            @Override
            public void onToolExecuting(TaskState t, ToolCall call) {
                AgentTree.toolCall(node, call.name);
                g.agent.externalLog("tool", e.name + " → " + call.name, com.suvo.agent.core.tools.ToolExecutor.redact(call.argumentsJson));
                progress.accept(e.name + ": " + call.name);
            }

            @Override
            public void onToolResult(TaskState t, ToolCall call, ToolResult result) {
                AgentTree.action(node, call.name + " · " + result.status.name().toLowerCase(Locale.ROOT));
                g.agent.externalLog(result.succeeded() ? "result" : "error", e.name + " · " + call.name + " — "
                        + result.status.name().toLowerCase(Locale.ROOT), result.message);
            }

            @Override
            public void onStatusChanged(TaskState t) {
                if (t.status == TaskState.Status.WAITING_APPROVAL) AgentTree.state(node, AgentTree.State.WAITING);
                else if (t.status == TaskState.Status.RUNNING) AgentTree.state(node, AgentTree.State.RUNNING);
            }

            @Override
            public void onToolCallPreparing(TaskState t, String toolName) {
                AgentTree.action(node, toolName + "…");
            }
        };
        try {
            AgentTree.action(node, "thinking");
            new AgentLoop(llm, exec, host, cfg, listener, cancel).run(task, ChatMessage.user(brief));
        } finally {
            exec.shutdown();
        }
        AgentTree.State end;
        switch (task.status) {
            case COMPLETED:
                end = AgentTree.State.DONE;
                break;
            case CANCELLED:
                end = AgentTree.State.STOPPED;
                break;
            default:
                end = AgentTree.State.FAILED;
        }
        String summary = task.status == TaskState.Status.COMPLETED ? task.finalText : task.error;
        AgentTree.finish(node, end, summary.length() > 300 ? summary.substring(0, 300) + "…" : summary);
        out.task = task;
        return out;
    }

    /** In-memory host: the employee's own short conversation for this one assignment. */
    private static final class ChildHost implements AgentHost {
        private final AppGraph g;
        private final EmployeeRepo.Employee e;
        private final ToolRegistry reg;
        private final boolean vision;
        private final List<ChatMessage> history = Collections.synchronizedList(new ArrayList<>());

        ChildHost(AppGraph g, EmployeeRepo.Employee e, ToolRegistry reg, boolean vision) {
            this.g = g;
            this.e = e;
            this.reg = reg;
            this.vision = vision;
        }

        @Override
        public List<ChatMessage> buildMessages(TaskState task) {
            PromptAssembler pa = new PromptAssembler();
            pa.characterName = e.name;
            pa.persona = "You are " + e.name + (e.role.isEmpty() ? "" : ", the team's " + e.role) + ". "
                    + (e.instructions.trim().isEmpty() ? "Work carefully and report clearly." : e.instructions.trim());
            pa.assignment = "The boss agent (the user's main companion) gave you the task in the first user message. "
                    + "You do not talk to the user directly: do the work with your tools, verify it, then answer with a concise report "
                    + "for the boss — what you did, the verified results (paths, numbers, findings) and anything left open. "
                    + "If you are missing information only the user has, stop and say exactly what you need.";
            pa.responseLanguage = "Write the report in the language of the task.";
            pa.capabilitySummary = g.capabilities.summaryForPrompt();
            pa.skillIndex = g.skills.indexForPrompt();
            pa.autonomyDescription = AndroidAgentHost.autonomyText(g.policy.mode);
            pa.lessons = AndroidAgentHost.lessons(g, 8);
            pa.taskProgress = task.toolCalls > 0 ? task.progressSummary(12) : "";
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", Locale.US);
            pa.nowDescription = f.format(new Date()) + " (" + TimeZone.getDefault().getID() + ")";
            List<ChatMessage> out = new ArrayList<>();
            out.add(ChatMessage.system(pa.build()));
            List<ChatMessage> copy;
            synchronized (history) {
                copy = new ArrayList<>(history);
            }
            out.addAll(HistorySanitizer.sanitize(HistorySanitizer.limitImages(copy, 1)));
            return out;
        }

        @Override
        public void persist(TaskState task, ChatMessage message) {
            history.add(message);
        }

        @Override
        public void saveTask(TaskState task) {
        }

        @Override
        public JSONArray toolDefinitions() {
            if (!g.settings.bool(Settings.TOOL_CALLING, true)) return new JSONArray();
            return reg.openAiTools(g.capabilities);
        }

        /** Keeps the brief and the most recent exchange when the provider says the context is full. */
        @Override
        public boolean compactForOverflow(TaskState task) {
            synchronized (history) {
                if (history.size() <= 8) return false;
                ChatMessage first = history.get(0);
                List<ChatMessage> tail = new ArrayList<>(history.subList(history.size() - 6, history.size()));
                history.clear();
                history.add(first);
                history.add(ChatMessage.user("[Older steps were dropped to fit the model's context. Progress so far:\n"
                        + task.progressSummary(20) + "]"));
                history.addAll(tail);
            }
            return true;
        }

        @Override
        public boolean visionEnabled() {
            return vision;
        }
    }
}
