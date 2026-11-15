package com.suvo.agent.agent;

import com.suvo.agent.AppGraph;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.tools.ResourceGuard;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.data.EmployeeRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Your Friend — Original project by Suvo.
 *
 * The team at work. The boss only hands out jobs: {@link #assign} starts the member on a thread of
 * its own and returns at once, so the boss's turn ends and the user can keep talking to it. Each
 * member works in the background with its own model and tools; when it is done its report goes
 * back to the boss as a message ({@link AgentRuntime#teamReport}), and the boss tells the user.
 * Members never use the screen, camera or browser at the same time ({@link ResourceGuard}).
 */
public final class Team {

    /** Setting: big jobs go to the team in the background; the boss does small things itself (default on). */
    public static final String DELEGATE_ONLY = "team_boss_delegates";
    /** Start of the message that carries a member's report to the boss. */
    public static final String REPORT_MARKER = "[Team report]";
    /** Start of the message that tells the boss how a working member is doing (it may steer it). */
    public static final String UPDATE_MARKER = "[Team update]";

    public static final String RUNNING = "running", DONE = "done", FAILED = "failed", STOPPED = "stopped";

    /** One assignment of one member. */
    public static final class Job {
        public final int id;
        public final long employeeId;
        public final String employee, role, brief;
        public final long conversationId;
        public final long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public volatile String status = RUNNING;
        public volatile String report = "", error = "";
        /** What it is doing right now (a tool name, "thinking"…). */
        public volatile String action = "thinking";
        public volatile int toolCalls;
        /** The thing it is waiting for another member to free ("screen"), with who has it; "" = not waiting. */
        public volatile String waitingFor = "", waitingOn = "";
        public volatile boolean waitingApproval;
        /** The member's own conversation for this job (its steps, live while it works). */
        public volatile List<com.suvo.agent.core.llm.ChatMessage> transcript = java.util.Collections.emptyList();
        /** The agent task id of this run (the owner in the resource guard). */
        public volatile String taskId = "";
        /** The member's recent steps (tool, result), for the boss's check-ins. */
        public volatile String progress = "";
        // Supervision: failed tool results in a row, and when / at how many calls the boss last looked.
        volatile int failsInRow;
        volatile long lastUpdateAt = System.currentTimeMillis();
        volatile int lastUpdateCalls;
        public volatile int updates;
        final CancellationToken cancel = new CancellationToken();
        private final List<String> inbox = new ArrayList<>();

        Job(int id, EmployeeRepo.Employee e, String brief, long conversationId) {
            this.id = id;
            this.employeeId = e.id;
            this.employee = e.name;
            this.role = e.role;
            this.brief = brief;
            this.conversationId = conversationId;
        }

        public boolean running() {
            return RUNNING.equals(status);
        }

        /** Messages the boss sent while the member works (taken once). */
        List<String> takeInbox() {
            synchronized (inbox) {
                List<String> out = new ArrayList<>(inbox);
                inbox.clear();
                return out;
            }
        }

        public String briefShort(int max) {
            String b = brief.replace('\n', ' ').trim();
            return b.length() > max ? b.substring(0, max) + "…" : b;
        }
    }

    public interface Listener {
        /** Something about a job changed (any thread). */
        void onTeamChanged();
    }

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final CopyOnWriteArrayList<Job> JOBS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** Ids of the jobs a team report message is about ("job #12"). */
    public static List<Integer> reportJobs(String message) {
        List<Integer> out = new ArrayList<>();
        if (message == null || !message.startsWith(REPORT_MARKER)) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("finish(?:ed)? job #(\\d+)\\.").matcher(message);
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }

    /** Running agent task → the tool executor it may use (so scripts keep to the member's own tools). */
    private static final ConcurrentHashMap<String, ToolExecutor> EXECUTORS = new ConcurrentHashMap<>();

    static {
        ResourceGuard.SHARED.listener = (owner, resource, holder, waiting) -> {
            for (Job j : JOBS) {
                if (j.taskId.equals(owner)) {
                    j.waitingFor = waiting ? resource : "";
                    j.waitingOn = waiting ? holder : "";
                }
            }
            changed();
        };
    }

    private Team() {
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void changed() {
        for (Listener l : LISTENERS) {
            try {
                l.onTeamChanged();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** True when big jobs go to the team: the switch is on and there is somebody to delegate to. */
    public static boolean bossDelegatesOnly(AppGraph g) {
        return g.settings.bool(DELEGATE_ONLY, true) && !g.employees.enabled().isEmpty();
    }

    public static List<Job> jobs() {
        return new ArrayList<>(JOBS);
    }

    public static int runningCount() {
        int n = 0;
        for (Job j : JOBS) if (j.running()) n++;
        return n;
    }

    public static Job get(int id) {
        for (Job j : JOBS) if (j.id == id) return j;
        return null;
    }

    /** The job this member is working on right now, or null. */
    public static Job runningFor(long employeeId) {
        for (Job j : JOBS) if (j.employeeId == employeeId && j.running()) return j;
        return null;
    }

    /** The member's newest job (running or not), or null. */
    public static Job latestFor(long employeeId) {
        Job last = null;
        for (Job j : JOBS) if (j.employeeId == employeeId) last = j;
        return last;
    }

    static void bind(String taskId, ToolExecutor exec) {
        EXECUTORS.put(taskId, exec);
    }

    static void unbind(String taskId) {
        EXECUTORS.remove(taskId);
    }

    /** The executor of the agent run {@code taskId} (a member's own), else the boss's. */
    public static ToolExecutor executorFor(AppGraph g, String taskId) {
        ToolExecutor e = taskId == null ? null : EXECUTORS.get(taskId);
        return e == null ? g.executor : e;
    }

    /**
     * Starts {@code e} on {@code brief} in the background and returns at once.
     * The caller checks first that the member is free ({@link #runningFor}).
     */
    public static Job assign(AppGraph g, EmployeeRepo.Employee e, String brief, long conversationId) {
        final Job job = new Job(IDS.incrementAndGet(), e, brief, conversationId);
        JOBS.add(job);
        while (JOBS.size() > 40) {
            Job old = null;
            for (Job j : JOBS) {
                if (!j.running()) {
                    old = j;
                    break;
                }
            }
            if (old == null) break;
            JOBS.remove(old);
        }
        AgentForegroundService.start(g.ctx, e.name + ": " + job.briefShort(80));
        changed();
        Thread t = new Thread(() -> {
            ChildAgent.Outcome o;
            try {
                AgentTree.Node root = AgentTree.root();
                o = ChildAgent.run(g, e, brief, root == null ? -1 : root.id, job.cancel, a -> {
                }, job);
            } catch (RuntimeException ex) {
                o = new ChildAgent.Outcome();
                o.error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
            }
            TaskState ts = o.task;
            if (ts == null) {
                job.status = FAILED;
                job.error = o.error;
            } else if (ts.status == TaskState.Status.COMPLETED) {
                job.status = DONE;
                job.report = ts.finalText;
            } else if (ts.status == TaskState.Status.CANCELLED) {
                job.status = STOPPED;
                job.error = "stopped";
            } else {
                job.status = FAILED;
                job.error = ts.error == null || ts.error.isEmpty() ? ts.status.name().toLowerCase(Locale.ROOT) : ts.error;
                job.report = ts.progressSummary(12);
            }
            job.finishedAt = System.currentTimeMillis();
            job.action = "";
            job.waitingFor = "";
            job.waitingApproval = false;
            changed();
            // A job the user (or the boss) stopped is not reported back: they already know.
            if (!STOPPED.equals(job.status)) g.agent.teamReport(job);
            else g.agent.teamIdleCheck();
        }, "team-" + e.name);
        t.setDaemon(true);
        t.start();
        return job;
    }

    /** Sends the running member one more instruction from the boss. */
    public static boolean tell(Job j, String message) {
        if (j == null || !j.running()) return false;
        synchronized (j.inbox) {
            j.inbox.add(message);
        }
        return true;
    }

    /** The running job whose member runs as agent task {@code taskId}, or null (the boss, or not a member). */
    public static Job jobForTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) return null;
        for (Job j : JOBS) if (j.running() && taskId.equals(j.taskId)) return j;
        return null;
    }

    /** Running jobs given from this chat. */
    public static int runningIn(long conversationId) {
        int n = 0;
        for (Job j : JOBS) if (j.running() && j.conversationId == conversationId) n++;
        return n;
    }

    // ---- Supervision: the boss is told when a member needs it, and can steer it ----------------

    private static final long MIN_GAP_MS = 90_000, CHECKIN_MS = 5 * 60_000L;
    private static final int CHECKIN_CALLS = 15, FAILS_TO_REPORT = 3;

    /**
     * After each of a member's tool results: tells the boss when the member seems to be in trouble
     * (several failures in a row, repeating itself) or at a check-in point (every
     * {@value #CHECKIN_CALLS} tool calls or {@code 5} minutes), so a member going the wrong way is
     * noticed and redirected instead of running to the end.
     */
    static void afterStep(AppGraph g, Job job, String tool, com.suvo.agent.core.tools.ToolResult r, String progress) {
        if (job == null || !job.running()) return;
        job.progress = progress;
        job.failsInRow = r.succeeded() ? 0 : job.failsInRow + 1;
        long now = System.currentTimeMillis();
        String why = null;
        boolean loop = r.status == com.suvo.agent.core.tools.ToolResult.Status.DENIED && r.message != null && r.message.startsWith("Loop detected");
        if (loop) why = "it is repeating the same action without progress (" + tool + ")";
        else if (job.failsInRow == FAILS_TO_REPORT) why = FAILS_TO_REPORT + " tool calls failed in a row (last: " + tool + ": "
                + shorten(r.message, 160) + ")";
        else if (job.toolCalls - job.lastUpdateCalls >= CHECKIN_CALLS) why = "check-in: " + (job.toolCalls - job.lastUpdateCalls) + " more tool calls since you last looked";
        else if (now - job.lastUpdateAt >= CHECKIN_MS && job.toolCalls - job.lastUpdateCalls >= 3) why = "check-in: "
                + (now - job.lastUpdateAt) / 60_000 + " minutes since you last looked";
        if (why == null) return;
        // Not too often (a problem is still reported, after a short pause).
        if (now - job.lastUpdateAt < MIN_GAP_MS && job.updates > 0) return;
        job.lastUpdateAt = now;
        job.lastUpdateCalls = job.toolCalls;
        job.updates++;
        g.agent.teamUpdate(job, why);
    }

    /**
     * The member asks the boss (unclear brief, a decision, a plan that will not work). The boss is
     * told at once; the answer comes back through the member's inbox. Waits up to {@code waitMs}.
     */
    public static List<String> ask(AppGraph g, Job job, String question, long waitMs, CancellationToken cancel) throws InterruptedException {
        job.lastUpdateAt = System.currentTimeMillis();
        job.lastUpdateCalls = job.toolCalls;
        job.updates++;
        g.agent.teamUpdate(job, "it asks you: " + question);
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end && (cancel == null || !cancel.isCancelled()) && job.running()) {
            List<String> got = job.takeInbox();
            if (!got.isEmpty()) return got;
            Thread.sleep(400);
        }
        return new ArrayList<>();
    }

    /** The text that tells the boss how a working member is doing. */
    static String updateText(Job j, String why) {
        StringBuilder sb = new StringBuilder(UPDATE_MARKER);
        sb.append(" This message is from the app, not typed by the user. ").append(j.employee);
        if (!j.role.isEmpty()) sb.append(" (").append(j.role).append(')');
        long min = (System.currentTimeMillis() - j.startedAt) / 60_000;
        sb.append(" is working on job #").append(j.id).append(" (").append(min).append(" min, ").append(j.toolCalls).append(" tool calls).\n");
        sb.append("Why you are told: ").append(why).append('\n');
        sb.append("Task: ").append(j.briefShort(400)).append('\n');
        if (!j.progress.trim().isEmpty()) sb.append("Recent steps:\n").append(j.progress.trim()).append('\n');
        sb.append("Look at it like a manager. On track: reply with exactly NOTHING. Going the wrong way, stuck, or wasting steps: "
                + "delegate_task action=tell job=").append(j.id).append(" with a clear correction (what to do instead), or action=stop "
                + "and assign it again with a better brief. It asked a question: answer it with action=tell job=").append(j.id)
                .append(". Tell the user something only if they must know or decide.");
        return sb.toString();
    }

    /** The member's name inside an update message (for the chat's short line), or "". */
    public static String updateSender(String message) {
        if (message == null || !message.startsWith(UPDATE_MARKER)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("typed by the user\\. (.+?)(?: \\(| is working)").matcher(message);
        return m.find() ? m.group(1) : "";
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public static boolean stop(int id) {
        Job j = get(id);
        if (j == null || !j.running()) return false;
        j.cancel.cancel("stopped");
        return true;
    }

    /** Stops every running member. @return how many */
    public static int stopAll() {
        int n = 0;
        for (Job j : JOBS) {
            if (j.running()) {
                j.cancel.cancel("stopped");
                n++;
            }
        }
        return n;
    }

    /** The text that carries a finished job to the boss. */
    static String reportText(Job j) {
        StringBuilder sb = new StringBuilder(REPORT_MARKER);
        sb.append(" This message is from the app, not typed by the user. ").append(j.employee);
        if (!j.role.isEmpty()) sb.append(" (").append(j.role).append(')');
        sb.append(DONE.equals(j.status) ? " finished job #" : " could NOT finish job #").append(j.id).append(".\n");
        sb.append("Task: ").append(j.briefShort(400)).append('\n');
        if (DONE.equals(j.status)) {
            sb.append("Report:\n").append(j.report.trim());
        } else {
            sb.append("Problem: ").append(j.error);
            if (!j.report.trim().isEmpty()) sb.append("\nSteps done before that:\n").append(j.report.trim());
        }
        return sb.toString();
    }

    /** The member's name inside a report message (for the chat's short notice line), or "". */
    public static String reportSender(String message) {
        if (message == null || !message.startsWith(REPORT_MARKER)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("typed by the user\\. (.+?)(?: \\(| finished| could NOT)").matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.indexOf(m.group(1)) >= 0) continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(m.group(1));
        }
        return sb.toString();
    }

    public static boolean reportFailed(String message) {
        return message != null && message.contains(" could NOT finish job #");
    }
}
