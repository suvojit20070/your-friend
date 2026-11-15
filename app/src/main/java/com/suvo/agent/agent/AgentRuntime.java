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
 * Runs the agent: one agent per chat, working side by side (up to {@link #MAX_PARALLEL}). UI
 * components subscribe as {@link AgentListener}s; callbacks are delivered on the main thread and
 * carry the task, whose conversationId says which chat it is. Writing to a busy chat steers its
 * agent at the next step; Stop in a chat stops only that chat's agent.
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

    /** Messages for a task that is running (taken once by its loop at the next step). */
    public static final class Inbox {
        private final List<ChatMessage> items = new ArrayList<>();

        synchronized void add(ChatMessage m) {
            items.add(m);
        }

        public synchronized List<ChatMessage> take() {
            List<ChatMessage> out = new ArrayList<>(items);
            items.clear();
            return out;
        }

        public synchronized boolean has() {
            return !items.isEmpty();
        }

        synchronized List<String> texts() {
            List<String> out = new ArrayList<>();
            for (ChatMessage m : items) out.add(m.contentOrEmpty());
            return out;
        }

        synchronized void clear() {
            items.clear();
        }
    }

    /**
     * One chat's agent. Every chat has its own: the assistant card's chat, the app's chat and a
     * scheduled run work side by side and never stop each other. Writing to a chat whose agent is
     * busy does not wait or fail — the message goes into its {@link Inbox} and is read at the next
     * step, so the agent can change course mid-task.
     */
    private static final class Session {
        final long conversationId;
        volatile boolean running;
        volatile TaskState task;
        volatile AgentLoop loop;
        volatile CancellationToken token;
        volatile AndroidAgentHost host;
        volatile AgentTree.Node tree;
        final Inbox inbox = new Inbox();

        Session(long conversationId) {
            this.conversationId = conversationId;
        }
    }

    /** At most this many chats work at the same time (each is a model conversation of its own). */
    public static final int MAX_PARALLEL = 4;

    private final AppGraph g;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<AgentListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LogLine> log = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    /** The task started last (any chat): what the app-wide screens (home, live agent) show. */
    private volatile Session latest;
    private volatile long activeConversation = -1;

    public AgentRuntime(AppGraph g) {
        this.g = g;
        g.approvals.addListener(pending -> {
            for (Session s : sessions.values()) {
                TaskState t = s.task;
                if (!s.running || t == null || t.status.isTerminal()) continue;
                TaskState.Status next = pending.isEmpty() ? TaskState.Status.RUNNING : TaskState.Status.WAITING_APPROVAL;
                if (t.status != next && t.status != TaskState.Status.PAUSED) {
                    t.status = next;
                    dispatch(l -> l.onStatusChanged(t));
                }
            }
        });
    }

    public void addListener(AgentListener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(AgentListener l) {
        listeners.remove(l);
    }

    /** The task started last, in any chat (app-wide status). */
    public TaskState current() {
        Session s = latest;
        return s == null ? null : s.task;
    }

    /** The task of this chat (running or the last one), or null. */
    public TaskState current(long conversationId) {
        Session s = sessions.get(conversationId);
        return s == null ? null : s.task;
    }

    /** True when any chat's agent is working. */
    public boolean isRunning() {
        for (Session s : sessions.values()) if (s.running) return true;
        return false;
    }

    /** True when this chat's agent is working (the card, a call and each chat only stop their own). */
    public boolean isRunningIn(long conversationId) {
        Session s = sessions.get(conversationId);
        return s != null && s.running;
    }

    /**
     * What the user wrote to this chat while its agent works, not read yet (the chat shows it as a
     * waiting message until the agent reads it at its next step). Team messages are left out.
     */
    public List<String> waitingMessages(long conversationId) {
        Session s = sessions.get(conversationId);
        List<String> out = new ArrayList<>();
        if (s == null || !s.running) return out;
        for (String t : s.inbox.texts()) {
            if (t.startsWith(Team.REPORT_MARKER) || t.startsWith(Team.UPDATE_MARKER)) continue;
            out.add(t);
        }
        return out;
    }

    private int runningCount() {
        int n = 0;
        for (Session s : sessions.values()) if (s.running) n++;
        return n;
    }

    /** The chat a running task (boss task id) belongs to, or -1. */
    public long conversationOfTask(String taskId) {
        if (taskId == null) return -1;
        for (Session s : sessions.values()) {
            TaskState t = s.task;
            if (t != null && taskId.equals(t.id)) return s.conversationId;
        }
        return -1;
    }

    public List<LogLine> log() {
        return new ArrayList<>(log);
    }

    /** The host of this chat's last task (its memory chip), or null. */
    public AndroidAgentHost host(long conversationId) {
        Session s = sessions.get(conversationId);
        return s == null ? null : s.host;
    }

    /** The host of the task started last (what the memory chips show). */
    public AndroidAgentHost host() {
        Session s = latest;
        return s == null ? null : s.host;
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

    /** Settings → which chat the assistant card and hands-free use: "separate" (default), "new", "current". */
    public static final String ASSIST_CHAT = "assist_chat";
    private static final String ASSIST_CONVERSATION = "assist_conversation_id";

    /**
     * The chat for the assistant card / hands-free. "separate": one chat of their own ("Assistant"),
     * so the main chat stays clean; "new": a fresh chat each time the card opens or hands-free starts
     * ({@code startNew}), then that one until the next time; "current": the chat open in the app.
     * Never changes which chat the app itself shows.
     */
    public long assistConversation(boolean startNew) {
        String mode = g.settings.str(ASSIST_CHAT, "separate");
        if ("current".equals(mode)) return activeConversation();
        long id = g.settings.lng(ASSIST_CONVERSATION, -1L);
        boolean exists = id >= 0 && g.conversations.get(id) != null;
        if (!exists || ("new".equals(mode) && startNew)) {
            id = g.conversations.create("separate".equals(mode) ? g.ctx.getString(com.suvo.agent.R.string.assist_chat_title) : "",
                    g.characters.active().id);
            g.settings.put(ASSIST_CONVERSATION, id);
        }
        return id;
    }

    /**
     * "New chat" for the assistant card / hands-free / call: a fresh chat of its own (the app's
     * chat stays as it is) — or, in "current" mode, a new chat for the app too.
     */
    public long resetAssistConversation() {
        String mode = g.settings.str(ASSIST_CHAT, "separate");
        if ("current".equals(mode)) return newConversation();
        long old = g.settings.lng(ASSIST_CONVERSATION, -1L);
        // The separate Assistant chat starts over (its old messages go away); "new" just starts another.
        if ("separate".equals(mode) && old >= 0 && g.conversations.get(old) != null && !isRunningIn(old)) {
            g.conversations.delete(old);
        }
        long id = g.conversations.create("separate".equals(mode) ? g.ctx.getString(com.suvo.agent.R.string.assist_chat_title) : "",
                g.characters.active().id);
        g.settings.put(ASSIST_CONVERSATION, id);
        return id;
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
            TaskState t = current();
            if (t != null) l.onToolProgress(t, CHILD_CALL, text);
        });
    }

    private static final ToolCall CHILD_CALL = new ToolCall("child", "delegate_task", "{}");

    /**
     * Starts a task the app itself triggered (schedule_task or the self check-in). Check-ins run
     * in their own conversation so they do not fill the main chat.
     */
    public String submitScheduled(String text, boolean checkin) {
        long conv = scheduledConversation(checkin);
        // A scheduled run never slips into a chat that is busy: it waits for its turn (the caller retries).
        if (isRunningIn(conv)) return g.ctx.getString(R.string.agent_busy);
        Start st = new Start();
        st.scheduled = true;
        st.quiet = checkin;
        return start(conv, ChatMessage.user(text), text, st);
    }

    /** True when the chat a scheduled run would use is busy now (then it is retried later). */
    public boolean scheduledBusy(boolean checkin) {
        return isRunningIn(scheduledConversation(checkin));
    }

    private long scheduledConversation(boolean checkin) {
        if (!checkin) return activeConversation();
        long id = g.settings.raw().getLong("checkin_conversation", -1);
        if (id < 0 || g.conversations.get(id) == null) {
            id = g.conversations.create(g.ctx.getString(R.string.checkin_conversation), g.characters.active().id);
            g.settings.raw().edit().putLong("checkin_conversation", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------------ team reports (background members)

    /**
     * A team member finished in the background: its report goes to the boss of the chat that gave
     * the job — read at the next step when that chat's agent is busy, else as a new turn at once.
     */
    public void teamReport(Team.Job job) {
        addLog(Team.DONE.equals(job.status) ? "result" : "error", job.employee + " — job #" + job.id + " " + job.status,
                Team.DONE.equals(job.status) ? job.report : job.error);
        int still = Team.runningCount();
        String text = Team.reportText(job) + "\n\n(" + (still == 0 ? "No team member is working now." : still + " team member(s) are still working; their reports come later.")
                + " Tell the user the result now, in your own words, short and clear, with the facts, paths or links that matter."
                + " If the job failed or more work is needed, assign it again with what was learnt.)";
        main.post(() -> {
            long conv = job.conversationId;
            if (g.conversations.get(conv) == null) conv = activeConversation();
            if (deliver(conv, text, false) != null) {
                // No provider to tell it with: at least the live log has the report.
                teamIdleCheck();
            }
        });
    }

    /**
     * A member needs the boss: it asked a question, keeps failing, repeats itself, or reached a
     * check-in point. The boss looks at its progress and steers it (tell / stop / let it go on).
     * When all is well it answers NOTHING and the chat shows only a short line.
     */
    public void teamUpdate(Team.Job job, String why) {
        addLog("notice", job.employee + " — job #" + job.id + ": " + why, "");
        String text = Team.updateText(job, why);
        main.post(() -> {
            long conv = job.conversationId;
            if (g.conversations.get(conv) == null) conv = activeConversation();
            deliver(conv, text, true);
        });
    }

    /** Into a busy chat's inbox, or a new turn of an idle one. Null = done, else why not. */
    private String deliver(long conv, String text, boolean quiet) {
        Session s = sessions.get(conv);
        if (s != null) {
            synchronized (s) {
                if (s.running) {
                    // It is in the middle of another task: deal with this, then carry on with that task.
                    s.inbox.add(ChatMessage.user(quiet ? text + "\n(You are in the middle of another task: steer the member "
                            + "with a tool call if needed, then continue that task. Do not answer NOTHING to it.)" : text));
                    return null;
                }
            }
        }
        Start st = new Start();
        st.quiet = quiet;
        return start(conv, ChatMessage.user(text), text, st);
    }

    /** No member and no chat at work: the "working" notification goes away. */
    public void teamIdleCheck() {
        if (!isRunning() && Team.runningCount() == 0) AgentForegroundService.stop(g.ctx);
    }

    /** Stops every chat's agent and every team member (the notification's Stop). */
    public void cancelAll() {
        Team.stopAll();
        cancel();
    }

    /** How a turn starts: a scheduled run (its host checks the screen), quiet (NOTHING is not shown). */
    private static final class Start {
        boolean scheduled, quiet;
    }

    /** A line in the live log from elsewhere (team tools). */
    public void addNotice(String text, String detail) {
        addLog("notice", text, detail);
    }

    private void addLog(String kind, String text, String detail) {
        log.add(new LogLine(kind, text, detail));
        while (log.size() > 300) log.remove(0);
    }

    /**
     * Sends a message to a chat's agent. Returns null on success or a user-facing reason (no
     * provider, too many chats at work). When that chat's agent is already working, the message is
     * not refused: it is read at the agent's next step (it may change course), like writing to a
     * person who is busy.
     */
    public String submit(long conversationId, String text, List<ChatMessage.Image> images) {
        ChatMessage user = images == null || images.isEmpty() ? ChatMessage.user(text) : ChatMessage.user(text, images);
        Session s = sessions.get(conversationId);
        if (s != null) {
            synchronized (s) {
                if (s.running) {
                    s.inbox.add(user);
                    addLog("status", g.ctx.getString(R.string.log_message_added), text);
                    return null;
                }
            }
        }
        return start(conversationId, user, text, new Start());
    }

    private String start(long conversationId, ChatMessage user, String text, Start how) {
        ProviderConfig p = g.providers.active();
        if (p == null) return g.ctx.getString(R.string.agent_no_provider);
        String invalid = p.validate();
        if (invalid != null) return invalid;
        Session s = sessions.computeIfAbsent(conversationId, Session::new);
        synchronized (s) {
            if (s.running) {
                s.inbox.add(user);
                return null;
            }
            if (runningCount() >= MAX_PARALLEL) return g.ctx.getString(R.string.agent_too_many, MAX_PARALLEL);
            s.running = true;
        }
        // The assistant card's own chat never becomes the chat the app shows.
        if (conversationId != g.settings.lng(ASSIST_CONVERSATION, -1L)) activeConversation = conversationId;
        final boolean scheduledRun = how.scheduled;
        // Said aloud while it streams in: replies on, a call in this chat, hands-free, or the card asked.
        final com.suvo.agent.voice.SpeechController.Talk talk = !scheduledRun && !how.quiet && speakAloud(conversationId) ? g.speech.startTalk() : null;
        CancellationToken tk = new CancellationToken();
        TaskState task = new TaskState(conversationId, text);
        s.token = tk;
        s.task = task;
        latest = s;
        if (Team.runningCount() == 0 && runningCount() <= 1) log.clear(); // members still at work keep their lines
        addLog("status", g.ctx.getString(R.string.log_task_started), text);
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
            h.inbox = s.inbox;
            s.host = h;
            com.suvo.agent.core.tools.ResourceGuard.SHARED.name(task.id, g.characters.active().name);
            g.settings.applyTo(g.policy);
            AgentConfig cfg = g.settings.agentConfig();
            AgentLoop lp = new AgentLoop(llm, g.executor, h, cfg, new InternalListener(talk, s), tk);
            s.loop = lp;
            AgentForegroundService.start(g.ctx, text);
            s.tree = AgentTree.startRoot(g.characters.active().name, text);
            try {
                lp.run(task, user);
            } finally {
                if (talk != null) talk.finish();
            }
            // Anything written in the last moment (after the final step) starts the next turn now.
            List<ChatMessage> late;
            synchronized (s) {
                late = tk.isCancelled() ? java.util.Collections.emptyList() : s.inbox.take();
                s.running = false;
            }
            if (!late.isEmpty()) {
                StringBuilder all = new StringBuilder();
                List<ChatMessage.Image> imgs = new ArrayList<>();
                for (ChatMessage m : late) {
                    if (all.length() > 0) all.append("\n\n");
                    all.append(m.contentOrEmpty());
                    if (m.images != null) imgs.addAll(m.images);
                }
                String next = all.toString();
                main.post(() -> submit(conversationId, next, imgs.isEmpty() ? null : imgs));
            }
            afterTask(task, llm, conversationId, talk, how.quiet);
        }, "agent-task-" + conversationId);
        t.start();
        return null;
    }

    /** Stops every chat's agent (the app-wide Stop: home screen, live agent, notification). */
    public void cancel() {
        for (Session s : sessions.values()) stop(s);
        g.speech.stop();
    }

    /** Stops only this chat's agent; other chats and team members go on. */
    public void cancel(long conversationId) {
        Session s = sessions.get(conversationId);
        if (s != null) stop(s);
        g.speech.stop();
    }

    private void stop(Session s) {
        s.inbox.clear();
        CancellationToken t = s.token;
        if (t != null && s.running) t.cancel("user");
    }

    public void pause() {
        Session s = latest;
        AgentLoop l = s == null ? null : s.loop;
        if (l != null) l.pause();
    }

    public void resume() {
        Session s = latest;
        AgentLoop l = s == null ? null : s.loop;
        if (l != null) l.resume();
    }

    public boolean isPaused() {
        Session s = latest;
        AgentLoop l = s == null ? null : s.loop;
        return l != null && l.isPaused();
    }

    private void afterTask(TaskState task, LlmClient llm, long conversationId, com.suvo.agent.voice.SpeechController.Talk talk, boolean quietTurn) {
        com.suvo.agent.core.tools.ResourceGuard.SHARED.releaseAll(task.id);
        // Other chats or team members may still be working: the notification then stays.
        int working = Team.runningCount();
        if (working == 0 && !isRunning()) AgentForegroundService.stop(g.ctx);
        else if (working > 0) AgentForegroundService.update(g.ctx, g.ctx.getResources().getQuantityString(R.plurals.team_working, working, working));
        afterTask0(task, llm, conversationId, talk, quietTurn);
    }

    private void afterTask0(TaskState task, LlmClient llm, long conversationId, com.suvo.agent.voice.SpeechController.Talk talk, boolean quietTurn) {
        // NOTHING: a check-in or a look at a member's progress found nothing to say — nothing is shown or said.
        boolean quiet = com.suvo.agent.agent.Scheduler.NOTHING.equals(task.finalText.trim());
        if (task.status == TaskState.Status.COMPLETED && !task.finalText.isEmpty() && !quiet) {
            com.suvo.agent.launch.AssistantWidget.lastReply(g.ctx, task.finalText);
            // Normally said while streaming; a provider that sent no text pieces gets it said now.
            if (talk != null && !talk.spoke() && !talk.cancelled()) g.speech.speak(task.finalText);
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

    private volatile long speakNextFor = Long.MIN_VALUE;

    /** The next answer in this chat is said aloud (the card's voice-only style). */
    public void speakNextReply(long conversationId) {
        speakNextFor = conversationId;
    }

    private boolean speakAloud(long conversationId) {
        boolean once = speakNextFor == conversationId;
        speakNextFor = Long.MIN_VALUE;
        return once || g.settings.bool(Settings.VOICE_OUTPUT, false)
                || (com.suvo.agent.voice.CallService.active() && com.suvo.agent.voice.CallService.conversation() == conversationId)
                || com.suvo.agent.voice.HandsFreeService.speaksFor(conversationId);
    }

    /** Bridges loop callbacks to the live log, the notification, and UI listeners (main thread). */
    private final class InternalListener implements AgentListener {
        /** The answer being said aloud as it streams (null = not spoken). */
        private final com.suvo.agent.voice.SpeechController.Talk talk;
        private final Session session;

        InternalListener(com.suvo.agent.voice.SpeechController.Talk talk, Session session) {
            this.talk = talk;
            this.session = session;
        }

        @Override
        public void onIncoming(TaskState task, ChatMessage message) {
            addLog("status", g.ctx.getString(R.string.log_message_read), message.contentOrEmpty());
            dispatch(l -> l.onIncoming(task, message));
        }

        @Override
        public void onStatusChanged(TaskState task) {
            addLog("status", task.status.name(), task.error);
            if (task.status == TaskState.Status.WAITING_APPROVAL) AgentTree.state(session.tree, AgentTree.State.WAITING);
            else if (task.status == TaskState.Status.RUNNING) AgentTree.state(session.tree, AgentTree.State.RUNNING);
            dispatch(l -> l.onStatusChanged(task));
        }

        @Override
        public void onTextDelta(TaskState task, String delta) {
            if (talk != null) talk.delta(delta);
            dispatch(l -> l.onTextDelta(task, delta));
        }

        @Override
        public void onReasoningDelta(TaskState task, String delta) {
            dispatch(l -> l.onReasoningDelta(task, delta));
        }

        @Override
        public void onToolCallPreparing(TaskState task, String toolName) {
            AgentTree.action(session.tree, toolName + "…");
            dispatch(l -> l.onToolCallPreparing(task, toolName));
        }

        @Override
        public void onAssistantMessage(TaskState task, ChatMessage message) {
            if (talk != null) talk.stepEnd(message.content);
            dispatch(l -> l.onAssistantMessage(task, message));
        }

        @Override
        public void onToolExecuting(TaskState task, ToolCall call) {
            // The assistant card steps aside first when this tool reads or operates the screen.
            ScreenGate.beforeTool(call.name);
            addLog("tool", call.name, com.suvo.agent.core.tools.ToolExecutor.redact(call.argumentsJson));
            AgentTree.toolCall(session.tree, "delegate_task".equals(call.name) ? "delegating" : call.name);
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
            AgentTree.finish(session.tree, end, res.length() > 300 ? res.substring(0, 300) + "…" : res);
            dispatch(l -> l.onFinished(task));
        }
    }
}
