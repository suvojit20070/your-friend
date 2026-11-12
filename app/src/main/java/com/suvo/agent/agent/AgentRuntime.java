package com.suvo.agent.agent;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.suvo.agent.App;
import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.core.agent.AgentConfig;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.AgentLoop;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.OpenAiCompatClient;
import com.suvo.agent.core.llm.ProviderConfig;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.memory.MemoryExtractor;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.ConversationRepo;
import com.suvo.agent.data.Settings;
import com.suvo.agent.ui.ChatActivity;
import com.suvo.agent.ui.Notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the single running agent task. UI components subscribe as {@link AgentListener}s; callbacks
 * are delivered on the main thread. Only one task runs at a time; Stop cancels it immediately.
 */
public final class AgentRuntime {

    /** Live log line for the Live Agent screen. */
    public static final class LogLine {
        public final long time = System.currentTimeMillis();
        public final String kind;   // status | tool | result | notice | error
        public final String text;
        public final String detail;

        LogLine(String kind, String text, String detail) {
            this.kind = kind;
            this.text = text;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final AppGraph g;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LogLine> log = new CopyOnWriteArrayList<>();
    private volatile TaskState current;
    private volatile AgentLoop loop;
    private volatile CancellationToken token;
    private volatile AndroidAgentHost host;
    private volatile long activeConversation = -1;
    private volatile AgentTree.Node treeRoot;

    public AgentRuntime(AppGraph g) {
        this.g = g;
        g.approvals.addListener(pending -> {
            TaskState t = current;
            if (t == null || t.status.isTerminal()) return;
            TaskState.Status next = pending.isEmpty() ? TaskState.Status.RUNNING : TaskState.Status.WAITING_APPROVAL;
            if (t.status != next && t.status != TaskState.Status.PAUSED) {
                t.status = next;
                dispatch(l -> l.onStatusChanged(t));
            }
        });
    }

    public void addListener(AgentListener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(AgentListener l) {
        listeners.remove(l);
    }

    public TaskState current() {
        return current;
    }

    public boolean isRunning() {
        TaskState t = current;
        return t != null && !t.status.isTerminal();
    }

    public List<LogLine> log() {
        return new ArrayList<>(log);
    }

    public AndroidAgentHost host() {
        return host;
    }

    public long activeConversation() {
        if (activeConversation < 0) {
            List<ConversationRepo.Conversation> recent = g.conversations.list(null, 1);
            activeConversation = recent.isEmpty() ? g.conversations.create("", g.characters.active().id) : recent.get(0).id;
        }
        return activeConversation;
    }

    public void setActiveConversation(long id) {
        activeConversation = id;
    }

    public long newConversation() {
        activeConversation = g.conversations.create("", g.characters.active().id);
        return activeConversation;
    }

    private interface Dispatch {
        void run(AgentListener l);
    }

    private void dispatch(Dispatch d) {
        main.post(() -> {
            for (AgentListener l : listeners) {
                try {
                    d.run(l);
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    /** Lines from employees (child agents) running inside delegate_task. */
    public void externalLog(String kind, String text, String detail) {
        addLog(kind, text, detail);
        dispatch(l -> {
            TaskState t = current;
            if (t != null) l.onToolProgress(t, CHILD_CALL, text);
        });
    }

    private static final ToolCall CHILD_CALL = new ToolCall("child", "delegate_task", "{}");

    /**
     * Starts a task the app itself triggered (schedule_task or the self check-in). Check-ins run
     * in their own conversation so they do not fill the main chat.
     */
    public String submitScheduled(String text, boolean checkin) {
        long conv;
        if (checkin) {
            long id = g.settings.raw().getLong("checkin_conversation", -1);
            if (id < 0 || g.conversations.get(id) == null) {
                id = g.conversations.create(g.ctx.getString(R.string.checkin_conversation), g.characters.active().id);
                g.settings.raw().edit().putLong("checkin_conversation", id).apply();
            }
            conv = id;
        } else {
            conv = activeConversation();
        }
        quietResult = checkin;
        scheduledNext = true;
        String why = submit(conv, text, null);
        scheduledNext = false;
        if (why != null) quietResult = false;
        return why;
    }

    // ------------------------------------------------------------------ team reports (background members)

    private final java.util.ArrayList<Team.Job> pendingReports = new java.util.ArrayList<>();

    /**
     * A team member finished in the background: its report goes to the boss as a message, at once
     * when the boss is free, else right after the turn it is in. The boss then tells the user.
     */
    public void teamReport(Team.Job job) {
        synchronized (pendingReports) {
            pendingReports.add(job);
        }
        addLog(Team.DONE.equals(job.status) ? "result" : "error", job.employee + " — job #" + job.id + " " + job.status,
                Team.DONE.equals(job.status) ? job.report : job.error);
        main.post(this::flushTeamReports);
    }

    /** No member and no boss turn left: the "working" notification goes away. */
    public void teamIdleCheck() {
        if (!isRunning() && Team.runningCount() == 0) AgentForegroundService.stop(g.ctx);
    }

    private void flushTeamReports() {
        if (isRunning()) return; // afterTask() calls this again
        java.util.List<Team.Job> batch;
        synchronized (pendingReports) {
            if (pendingReports.isEmpty()) {
                teamIdleCheck();
                return;
            }
            batch = new java.util.ArrayList<>(pendingReports);
            pendingReports.clear();
        }
        // One conversation per turn: the reports of the first job's chat now, the others next.
        long conv = batch.get(0).conversationId;
        StringBuilder sb = new StringBuilder();
        java.util.List<Team.Job> later = new java.util.ArrayList<>();
        for (Team.Job j : batch) {
            if (j.conversationId != conv) {
                later.add(j);
                continue;
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(Team.reportText(j));
        }
        int still = Team.runningCount();
        sb.append("\n\n(").append(still == 0 ? "No team member is working now." : still + " team member(s) are still working; their reports come later.")
                .append(" Tell the user the result now, in your own words, short and clear, with the facts, paths or links that matter."
                        + " If the job failed or more work is needed, assign it again with what was learnt.)");
        if (g.conversations.get(conv) == null) conv = activeConversation();
        String why = submit(conv, sb.toString(), null);
        synchronized (pendingReports) {
            pendingReports.addAll(later);
            if (why != null) {
                // Could not start (busy again, or no provider): keep them for the next chance.
                for (Team.Job j : batch) if (!later.contains(j)) pendingReports.add(0, j);
            }
        }
        if (why != null && !isRunning()) {
            // No provider to tell it with: at least the live log has the report.
            synchronized (pendingReports) {
                pendingReports.clear();
            }
            teamIdleCheck();
        }
    }

    /** Stops the boss's turn and every team member (the notification's Stop). */
    public void cancelAll() {
        Team.stopAll();
        cancel();
    }

    /** The next submit() is a scheduled run (its host then checks the screen). */
    private volatile boolean scheduledNext;

    /** Set for a self check-in: a reply of exactly NOTHING is not spoken or notified. */
    private volatile boolean quietResult;

    /** A line in the live log from elsewhere (team tools). */
    public void addNotice(String text, String detail) {
        addLog("notice", text, detail);
    }

    private void addLog(String kind, String text, String detail) {
        log.add(new LogLine(kind, text, detail));
        while (log.size() > 300) log.remove(0);
    }

    /**
     * Starts a task. Returns null on success or a user-facing reason (no provider, already busy).
     */
    public String submit(long conversationId, String text, List<ChatMessage.Image> images) {
        if (isRunning()) return g.ctx.getString(R.string.agent_busy);
        ProviderConfig p = g.providers.active();
        if (p == null) return g.ctx.getString(R.string.agent_no_provider);
        String invalid = p.validate();
        if (invalid != null) return invalid;
        activeConversation = conversationId;
        g.speech.stop();
        CancellationToken tk = new CancellationToken();
        token = tk;
        TaskState task = new TaskState(conversationId, text);
        current = task;
        if (Team.runningCount() == 0) log.clear(); // members still at work keep their lines
        addLog("status", g.ctx.getString(R.string.log_task_started), text);
        ChatMessage user = images == null || images.isEmpty() ? ChatMessage.user(text) : ChatMessage.user(text, images);
        final boolean scheduledRun = scheduledNext;
        // Everything heavier (database, prompt host, foreground service) happens on the task's own
        // thread, so the send button's press animation never stutters.
        Thread t = new Thread(() -> {
            ConversationRepo.Conversation conv = g.conversations.get(conversationId);
            if (conv != null && (conv.title == null || conv.title.isEmpty())) {
                String title = com.suvo.agent.core.util.Attachments.forDisplay(text, "📎 %s", "📁 %s").replace('\n', ' ').trim();
                g.conversations.rename(conversationId, title.length() > 48 ? title.substring(0, 48) + "…" : title);
            }
            // Usage (own token count, errors, speed) is recorded per provider.
            LlmClient llm = TrackedLlm.wrap(g.ctx, new OpenAiCompatClient(p));
            AndroidAgentHost h = new AndroidAgentHost(g, conversationId, llm, tk, n -> {
                addLog("notice", n, "");
                dispatch(l -> l.onNotice(task, n));
            });
            h.scheduled = scheduledRun;
            host = h;
            com.suvo.agent.core.tools.ResourceGuard.SHARED.name(task.id, g.characters.active().name);
            g.settings.applyTo(g.policy);
            AgentConfig cfg = g.settings.agentConfig();
            AgentLoop lp = new AgentLoop(llm, g.executor, h, cfg, new InternalListener(), tk);
            loop = lp;
            AgentForegroundService.start(g.ctx, text);
            treeRoot = AgentTree.startRoot(g.characters.active().name, text);
            lp.run(task, user);
            afterTask(task, llm, conversationId);
        }, "agent-task");
        t.start();
        return null;
    }

    public void cancel() {
        CancellationToken t = token;
        if (t != null) t.cancel("user");
        g.speech.stop();
    }

    public void pause() {
        AgentLoop l = loop;
        if (l != null) l.pause();
    }

    public void resume() {
        AgentLoop l = loop;
        if (l != null) l.resume();
    }

    public boolean isPaused() {
        AgentLoop l = loop;
        return l != null && l.isPaused();
    }

    private void afterTask(TaskState task, LlmClient llm, long conversationId) {
        com.suvo.agent.core.tools.ResourceGuard.SHARED.releaseAll(task.id);
        // Team members may still be working in the background: the notification then stays.
        int working = Team.runningCount();
        if (working == 0) AgentForegroundService.stop(g.ctx);
        else AgentForegroundService.update(g.ctx, g.ctx.getResources().getQuantityString(R.plurals.team_working, working, working));
        try {
            afterTask0(task, llm, conversationId);
        } finally {
            main.post(this::flushTeamReports);
        }
    }

    private void afterTask0(TaskState task, LlmClient llm, long conversationId) {
        boolean quiet = quietResult && com.suvo.agent.agent.Scheduler.NOTHING.equals(task.finalText.trim());
        quietResult = false;
        if (task.status == TaskState.Status.COMPLETED && !task.finalText.isEmpty() && !quiet) {
            com.suvo.agent.launch.AssistantWidget.lastReply(g.ctx, task.finalText);
            if (g.settings.bool(Settings.VOICE_OUTPUT, false)) g.speech.speak(task.finalText);
            if (!App.isInForeground()) notifyDone(task);
        }
        if (g.settings.bool(Settings.AUTO_MEMORY, true) && task.status != TaskState.Status.CANCELLED) {
            try {
                List<ChatMessage> recent = new ArrayList<>();
                for (ConversationRepo.StoredMessage sm : g.conversations.messages(conversationId, false)) {
                    if (task.id.equals(sm.taskId)) recent.add(sm.message);
                }
                LlmClient memLlm = RoleModels.clientOr(g, RoleModels.MEMORY, llm);
                List<MemoryExtractor.Action> acts = new MemoryExtractor().extract(recent, g.memory, memLlm, "conversation:" + conversationId, new CancellationToken());
                int added = 0;
                for (MemoryExtractor.Action a : acts) if (a.kind == MemoryExtractor.Action.Kind.ADDED || a.kind == MemoryExtractor.Action.Kind.UPDATED) added++;
                if (added > 0) {
                    String n = g.ctx.getResources().getQuantityString(R.plurals.memories_saved, added, added);
                    addLog("notice", n, "");
                    dispatch(l -> l.onNotice(task, n));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void notifyDone(TaskState task) {
        NotificationManager nm = g.ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(g.ctx, 20, new Intent(g.ctx, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Notifications.builder(g.ctx, Notifications.CHANNEL_GENERAL)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(g.characters.active().name)
                .setContentText(task.finalText)
                .setStyle(new Notification.BigTextStyle().bigText(task.finalText))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(com.suvo.agent.launch.ReplyReceiver.action(g.ctx, task.conversationId))
                .build();
        nm.notify(Notifications.ID_TASK_DONE, n);
    }

    /** Bridges loop callbacks to the live log, the notification, and UI listeners (main thread). */
    private final class InternalListener implements AgentListener {
        @Override
        public void onStatusChanged(TaskState task) {
            addLog("status", task.status.name(), task.error);
            if (task.status == TaskState.Status.WAITING_APPROVAL) AgentTree.state(treeRoot, AgentTree.State.WAITING);
            else if (task.status == TaskState.Status.RUNNING) AgentTree.state(treeRoot, AgentTree.State.RUNNING);
            dispatch(l -> l.onStatusChanged(task));
        }

        @Override
        public void onTextDelta(TaskState task, String delta) {
            dispatch(l -> l.onTextDelta(task, delta));
        }

        @Override
        public void onReasoningDelta(TaskState task, String delta) {
            dispatch(l -> l.onReasoningDelta(task, delta));
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            AgentTree.action(treeRoot, toolName + "…");
            dispatch(l -> l.onToolCallPreparing(task, toolName));
        }

        @Override
        public void onAssistantMessage(TaskState task, ChatMessage message) {
            dispatch(l -> l.onAssistantMessage(task, message));
        }

        @Override
        public void onToolExecuting(TaskState task, ToolCall call) {
            // The assistant card steps aside first when this tool reads or operates the screen.
            ScreenGate.beforeTool(call.name);
            addLog("tool", call.name, com.suvo.agent.core.tools.ToolExecutor.redact(call.argumentsJson));
            AgentTree.toolCall(treeRoot, "delegate_task".equals(call.name) ? "delegating" : call.name);
            AgentForegroundService.update(g.ctx, g.ctx.getString(R.string.agent_running_tool, call.name));
            dispatch(l -> l.onToolExecuting(task, call));
        }

        @Override
        public void onToolProgress(TaskState task, ToolCall call, String message) {
            addLog("notice", call.name + ": " + message, "");
            dispatch(l -> l.onToolProgress(task, call, message));
        }

        @Override
        public void onToolResult(TaskState task, ToolCall call, ToolResult result) {
            addLog(result.succeeded() ? "result" : "error", call.name + " — " + result.status.name().toLowerCase(), result.message);
            dispatch(l -> l.onToolResult(task, call, result));
        }

        @Override
        public void onNotice(TaskState task, String notice) {
            addLog("notice", notice, "");
            dispatch(l -> l.onNotice(task, notice));
        }

        @Override
        public void onFinished(TaskState task) {
            addLog(task.status == TaskState.Status.COMPLETED ? "status" : "error", task.status.name(), task.error);
            AgentTree.State end = task.status == TaskState.Status.COMPLETED ? AgentTree.State.DONE
                    : task.status == TaskState.Status.CANCELLED ? AgentTree.State.STOPPED : AgentTree.State.FAILED;
            String res = task.status == TaskState.Status.COMPLETED ? task.finalText : task.error;
            AgentTree.finish(treeRoot, end, res.length() > 300 ? res.substring(0, 300) + "…" : res);
            dispatch(l -> l.onFinished(task));
        }
    }
}
