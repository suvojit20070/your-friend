package com.suvo.agent.agent;

import com.suvo.agent.AppGraph;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.core.agent.AgentHost;
import com.suvo.agent.core.agent.HistorySanitizer;
import com.suvo.agent.core.agent.PromptAssembler;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.memory.ContextCompactor;
import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryExtractor;
import com.suvo.agent.core.memory.MemoryRanker;
import com.suvo.agent.core.memory.TokenEstimator;
import com.suvo.agent.core.tools.ApprovalPolicy;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.ConversationRepo;
import com.suvo.agent.data.EmployeeRepo;
import com.suvo.agent.data.Settings;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds each request's context from SQLite: layered system prompt, ranked memories, the running
 * summary, current task progress and recent raw history. Compacts history before it overflows.
 */
public final class AndroidAgentHost implements AgentHost {

    public interface Notice {
        void notice(String text);
    }

    private final AppGraph g;
    private final long conversationId;
    private final LlmClient llm;
    private final CancellationToken cancel;
    private final Notice notice;
    private final ContextCompactor compactor = new ContextCompactor();
    /** Memories used in the last request and why (shown in the Memory screen's debug view). */
    public volatile List<MemoryRanker.Scored> lastRetrieved = new ArrayList<>();

    public AndroidAgentHost(AppGraph g, long conversationId, LlmClient llm, CancellationToken cancel, Notice notice) {
        this.g = g;
        this.conversationId = conversationId;
        this.llm = llm;
        this.cancel = cancel;
        this.notice = notice == null ? t -> {
        } : notice;
        compactor.triggerFraction = g.settings.contextTriggerFraction();
    }

    @Override
    public List<ChatMessage> buildMessages(TaskState task) {
        List<ChatMessage> history = loadHistory();
        String system = systemPrompt(task, history);
        int fixed = TokenEstimator.estimate(system) + TokenEstimator.estimate(toolDefinitions().toString());
        ContextCompactor.Plan plan = compactor.plan(history, llm.config().contextWindowTokens, fixed, false);
        if (!plan.isEmpty() && g.settings.bool(Settings.SUMMARIES, true)) {
            if (compact(plan, task)) {
                history = loadHistory();
                system = systemPrompt(task, history);
            }
        }
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(system));
        out.addAll(HistorySanitizer.sanitize(HistorySanitizer.limitImages(history, 2)));
        // Optional: the current screen (tree and/or screenshot) goes along with every request.
        ChatMessage screen = screenOffNow() ? null : AutoScreenContext.build(g, llm.config().supportsVision);
        if (screen != null) out.add(screen);
        return out;
    }

    private List<ChatMessage> loadHistory() {
        List<ChatMessage> h = new ArrayList<>();
        for (ConversationRepo.StoredMessage sm : g.conversations.messages(conversationId, false)) h.add(sm.message);
        return h;
    }

    private String systemPrompt(TaskState task, List<ChatMessage> history) {
        PromptAssembler pa = new PromptAssembler();
        CharacterSpec ch = g.characters.active();
        pa.characterName = ch.name;
        pa.persona = ch.personaPrompt();
        applyUserPrompt(g, pa);
        String lang = g.settings.str(Settings.RESPONSE_LANGUAGE, "").trim();
        String tone = g.settings.str(Settings.TONE, "").trim();
        StringBuilder langText = new StringBuilder();
        langText.append(lang.isEmpty() ? "Reply in the language the user writes in (English, Hinglish, Bengali, Hindi, Japanese or any other)." : "Reply in: " + lang + ".");
        if (!tone.isEmpty()) langText.append(" Tone: ").append(tone).append('.');
        if (g.settings.bool(Settings.VOICE_OUTPUT, false)) langText.append(" Replies are also spoken aloud, so keep them easy to listen to.");
        pa.responseLanguage = langText.toString();
        pa.capabilitySummary = g.capabilities.summaryForPrompt();
        String accounts = g.connectors.summaryForPrompt();
        if (!accounts.isEmpty()) pa.capabilitySummary = pa.capabilitySummary + "\n" + accounts;
        pa.capabilitySummary = pa.capabilitySummary + "\n" + workFolderRule();
        pa.skillIndex = g.skills.indexForPrompt();
        pa.conversationSummary = g.conversations.summary(conversationId);
        pa.taskProgress = task.toolCalls > 0 || !task.openItems.isEmpty() ? task.progressSummary(12) : "";
        pa.autonomyDescription = autonomyText(g.policy.mode);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", Locale.US);
        pa.nowDescription = f.format(new Date()) + " (" + TimeZone.getDefault().getID() + "), device locale " + Locale.getDefault().toLanguageTag();
        if (g.settings.bool(Settings.MEMORY_RETRIEVAL, true)) {
            String query = task.goal;
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage m = history.get(i);
                if (m.role == ChatMessage.Role.USER && m.content != null) {
                    query = m.content + " " + task.goal;
                    break;
                }
            }
            List<MemoryEntry> cands = new ArrayList<>(g.memory.searchCandidates(query, null, 80));
            // Lessons have their own section below, so they are not counted against the memory budget.
            for (int i = cands.size() - 1; i >= 0; i--) if (cands.get(i).category == MemoryCategory.LESSON) cands.remove(i);
            List<MemoryRanker.Scored> ranked = new MemoryRanker().rank(query, cands, g.settings.integer(Settings.MEMORY_MAX_INJECTED, 8), System.currentTimeMillis());
            pa.memories = ranked;
            lastRetrieved = ranked;
            List<Long> ids = new ArrayList<>();
            for (MemoryRanker.Scored s : ranked) ids.add(s.entry.id);
            if (!ids.isEmpty()) g.memory.markUsed(ids, System.currentTimeMillis());
        }
        pa.lessons = lessons(g, 12);
        pa.teamRoster = teamRoster(g);
        String built = pa.build();
        if (screenOffNow()) {
            built += "\n\n# Screen is OFF\nThis run started by itself (a schedule) and the phone's screen is off right now, so the "
                    + "screen tools (screen control, screenshot, opening apps or links, camera) are not available. Do only background "
                    + "work: files, terminal, web requests, your browser, memory, notifications, messages. If the task really needs the "
                    + "screen, do the rest and say what is left for when the user is back.";
        }
        return built;
    }

    /** Set for a run started by a schedule / self check-in (no one is looking at the phone). */
    public volatile boolean scheduled;

    /** Tools that need the screen on (UI control, screen pictures, opening apps, camera). */
    private static final java.util.Set<String> SCREEN_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "screen_operation_accessibility", "screen_operation_shell", "screenshot", "launch_app", "open_uri", "camera_capture"));

    /** A scheduled run while the screen is off: no screen tools, no screen context. */
    private boolean screenOffNow() {
        if (!scheduled) return false;
        android.os.PowerManager pm = (android.os.PowerManager) g.ctx.getSystemService(android.content.Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    /**
     * Where the agent keeps its work: in a folder the user granted when there is one (the user can
     * see and use those files), otherwise in the private workspace; in Termux only inside ~/yf.
     */
    private String workFolderRule() {
        StringBuilder sb = new StringBuilder("Where to work: ");
        java.util.List<com.suvo.agent.files.SafStore.Grant> grants = g.saf.grants();
        if (!grants.isEmpty()) {
            sb.append("the user granted external folder(s) ");
            for (int i = 0; i < grants.size(); i++) sb.append(i == 0 ? "" : ", ").append("ext:").append(grants.get(i).name);
            sb.append(". ALWAYS create and save your work there (e.g. ext:").append(grants.get(0).name)
                    .append("/<project>/…) so the user can open it — not in the private workspace (use that only for scratch files).");
        } else {
            sb.append("no external folder is granted, so use the private workspace (the user can add a folder in Files).");
        }
        boolean jail = g.settings.bool(com.suvo.agent.terminal.TermuxBackend.JAIL_SETTING, true);
        sb.append(" In Termux your folder is ~/yf").append(jail ? " — for your commands it IS ~ (your home and root: pwd shows the "
                + "Termux home path, 'cd ..' / cd outside is refused, the user's other Termux files do not exist for you; /sdcard and "
                + "$PREFIX/tmp are reachable). Never try to get out of it" : "")
                .append("; use file_transfer to move files between the workspace / ext: folders and termux:<path in ~/yf>.");
        return sb.toString();
    }

    /** The parts of the system prompt the user edits in Settings → System prompt. */
    public static void applyUserPrompt(AppGraph g, PromptAssembler pa) {
        pa.userInstructions = g.settings.str(Settings.SYSTEM_INSTRUCTIONS, "");
        pa.workGuideOverride = g.settings.str(Settings.WORK_GUIDE_TEXT, "");
        pa.includeWorkGuide = g.settings.bool(Settings.INCLUDE_WORK_GUIDE, true);
    }

    /** Most recent lessons from record_mistake, newest first. */
    static List<String> lessons(AppGraph g, int max) {
        List<String> out = new ArrayList<>();
        try {
            for (MemoryEntry e : g.memory.all(MemoryCategory.LESSON, max)) out.add(e.content);
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** One line per enabled employee: name, role, model and what they are told to do. */
    static String teamRoster(AppGraph g) {
        List<EmployeeRepo.Employee> team = g.employees.enabled();
        if (team.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (EmployeeRepo.Employee e : team) {
            com.suvo.agent.core.llm.ProviderConfig p = ChildAgent.providerFor(g, e);
            sb.append("- ").append(e.name);
            if (!e.role.isEmpty()) sb.append(" (").append(e.role).append(')');
            if (p != null) sb.append(" · model ").append(p.model.isEmpty() ? p.name : p.model);
            String ins = e.instructions.replace('\n', ' ').trim();
            if (!ins.isEmpty()) sb.append(" · ").append(ins.length() > 160 ? ins.substring(0, 160) + "…" : ins);
            if (!e.tools.isEmpty()) sb.append(" · tools: ").append(String.join(", ", e.tools));
            sb.append('\n');
        }
        sb.append("Delegate when a member's role fits; otherwise do it yourself. You stay responsible for the final answer.");
        return sb.toString();
    }

    static String autonomyText(ApprovalPolicy.AutonomyMode m) {
        switch (m) {
            case ASSIST:
                return "Assist mode: only read-only tools run. For anything else, explain what you would do and let the user act.";
            case AUTONOMOUS:
                return "Autonomous mode: permitted actions run without asking, except high-risk ones (deleting, installing, sending, paying), which ask the user.";
            default:
                return "Confirm mode: low-risk actions run directly; the app asks the user before anything consequential. A denied action means the user said no.";
        }
    }

    /** Compacts in one durable step: summary saved and messages flagged in the same transaction. */
    private boolean compact(ContextCompactor.Plan plan, TaskState task) {
        notice.notice("Summarizing older messages to stay within the model's context");
        String previous = g.conversations.summary(conversationId);
        // Summaries and memories can use the cheaper "memory" model chosen in Settings → AI.
        LlmClient memLlm = RoleModels.clientOr(g, RoleModels.MEMORY, llm);
        ContextCompactor.Result r = compactor.summarize(plan, previous, task.progressSummary(12), memLlm, cancel);
        if (r.compactedIds.isEmpty()) return false;
        g.conversations.applyCompaction(conversationId, r.summary, r.compactedIds);
        if (g.settings.bool(Settings.AUTO_MEMORY, true)) {
            try {
                new MemoryExtractor().extract(plan.toCompact, g.memory, memLlm, "conversation:" + conversationId, cancel);
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    @Override
    public void persist(TaskState task, ChatMessage message) {
        long id = g.conversations.append(conversationId, message, task.id);
        if (message.role == ChatMessage.Role.ASSISTANT) {
            com.suvo.agent.data.MessageStats.Entry e = TrackedLlm.takeLast();
            if (e != null) com.suvo.agent.data.MessageStats.get(g.ctx).put(id, e);
        }
    }

    @Override
    public void saveTask(TaskState task) {
        g.tasks.save(task);
    }

    @Override
    public JSONArray toolDefinitions() {
        if (!g.settings.bool(Settings.TOOL_CALLING, true)) return new JSONArray();
        JSONArray all = g.tools.openAiTools(g.capabilities);
        if (screenOffNow()) {
            // Checked before every request: when the screen turns on during the run, the tools come back.
            JSONArray keep = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                org.json.JSONObject t = all.optJSONObject(i);
                org.json.JSONObject fn = t == null ? null : t.optJSONObject("function");
                if (fn != null && SCREEN_TOOLS.contains(fn.optString("name"))) continue;
                keep.put(t);
            }
            all = keep;
        }
        if (!g.employees.enabled().isEmpty()) return all;
        // No employees: do not offer delegation at all.
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            org.json.JSONObject t = all.optJSONObject(i);
            org.json.JSONObject fn = t == null ? null : t.optJSONObject("function");
            if (fn != null && "delegate_task".equals(fn.optString("name"))) continue;
            out.put(t);
        }
        return out;
    }

    @Override
    public boolean compactForOverflow(TaskState task) {
        List<ChatMessage> history = loadHistory();
        ContextCompactor.Plan plan = compactor.plan(history, llm.config().contextWindowTokens, 0, true);
        return !plan.isEmpty() && compact(plan, task);
    }

    @Override
    public boolean visionEnabled() {
        return llm.config().supportsVision;
    }
}
