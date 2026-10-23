package com.suvo.agent.core.agent;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.util.CancellationToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent execution cycle: build context → call model → validate/execute requested tools →
 * return real results → repeat until the model answers, a limit is hit, or the user cancels.
 */
public final class AgentLoop {

    private final LlmClient llm;
    private final ToolExecutor tools;
    private final AgentHost host;
    private final AgentConfig config;
    private final AgentListener listener;
    private final CancellationToken cancel;
    private final Object pauseLock = new Object();
    private volatile boolean paused;

    public AgentLoop(LlmClient llm, ToolExecutor tools, AgentHost host, AgentConfig config,
                     AgentListener listener, CancellationToken cancel) {
        this.llm = llm;
        this.tools = tools;
        this.host = host;
        this.config = config;
        this.listener = listener == null ? new AgentListener() {
        } : listener;
        this.cancel = cancel;
        cancel.onCancel(this::resume);
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Runs a task to completion. {@code userMessage} is persisted first; pass null to continue an
     * existing conversation (e.g. resuming after an approval or a restart).
     */
    public TaskState run(TaskState task, ChatMessage userMessage) {
        task.startedAt = task.startedAt == 0 ? System.currentTimeMillis() : task.startedAt;
        setStatus(task, TaskState.Status.RUNNING);
        Map<String, Integer> signatures = new HashMap<>();
        Map<String, String> lastResultBySig = new HashMap<>();
        int loopStrikes = 0;
        int steps = 0;
        try {
            if (userMessage != null) host.persist(task, userMessage);
            while (true) {
                if (cancel.isCancelled()) {
                    return end(task, TaskState.Status.CANCELLED, "", "Stopped by the user");
                }
                waitIfPaused(task);
                if (cancel.isCancelled()) {
                    return end(task, TaskState.Status.CANCELLED, "", "Stopped by the user");
                }
                if (steps >= config.maxSteps) {
                    return end(task, TaskState.Status.LIMIT_REACHED, "",
                            "Reached the maximum of " + config.maxSteps + " steps. You can continue the task with a new message.");
                }
                if (System.currentTimeMillis() - task.startedAt > config.maxDurationMs) {
                    return end(task, TaskState.Status.LIMIT_REACHED, "",
                            "Reached the maximum task duration (" + config.maxDurationMs / 60000 + " min).");
                }

                ChatResponse resp = callModel(task);
                task.llmTurns++;
                steps++;
                ChatMessage assistant = resp.toMessage();
                host.persist(task, assistant);
                listener.onAssistantMessage(task, assistant);

                if (!resp.hasToolCalls()) {
                    return end(task, TaskState.Status.COMPLETED, resp.content, "");
                }

                List<ToolCall> calls = resp.toolCalls;
                if (!config.allowMultipleToolCalls && calls.size() > 1) calls = calls.subList(0, 1);
                List<ChatMessage.Image> pendingImages = new ArrayList<>();
                List<String> imageSources = new ArrayList<>();
                boolean stopForLoop = false;

                for (ToolCall call : resp.toolCalls) {
                    ToolResult result;
                    if (!calls.contains(call)) {
                        result = ToolResult.of(ToolResult.Status.DENIED, "Only one tool call per turn is allowed; call it again in the next turn.");
                    } else if (cancel.isCancelled()) {
                        result = ToolResult.of(ToolResult.Status.CANCELLED, "Task cancelled");
                    } else if (task.toolCalls >= config.maxToolCalls) {
                        result = ToolResult.of(ToolResult.Status.DENIED, "Tool-call limit reached for this task");
                    } else {
                        // A loop is the same call returning the same result again and again. Repeating a
                        // call whose result changes (e.g. reading a screen that updates) is legitimate.
                        String sig = call.signature();
                        int repeats = signatures.getOrDefault(sig, 0);
                        if (repeats >= config.loopThreshold - 1) {
                            loopStrikes++;
                            result = ToolResult.of(ToolResult.Status.DENIED,
                                    "Loop detected: " + call.name + " returned the same result " + (repeats + 1)
                                            + " times for identical arguments. Last result: " + abbreviate(lastResultBySig.getOrDefault(sig, ""), 160)
                                            + ". Change approach or report the problem to the user.");
                            if (loopStrikes >= 2) stopForLoop = true;
                        } else {
                            task.toolCalls++;
                            listener.onToolExecuting(task, call);
                            final ToolCall c = call;
                            result = tools.execute(call, task.id, cancel, m -> listener.onToolProgress(task, c, m));
                            String fingerprint = result.status + "|" + result.message + "|" + result.data;
                            String previous = lastResultBySig.get(sig);
                            signatures.put(sig, fingerprint.equals(previous) ? repeats + 1 : 0);
                            lastResultBySig.put(sig, fingerprint);
                        }
                    }
                    task.addStep(call.name, result.status.name(), result.message);
                    ChatMessage toolMsg = ChatMessage.tool(call.id, call.name, result.toModelContent(config.toolResultMaxChars));
                    host.persist(task, toolMsg);
                    listener.onToolResult(task, call, result);
                    if (!result.images.isEmpty() && host.visionEnabled()) {
                        pendingImages.addAll(result.images);
                        imageSources.add(call.name);
                    }
                    host.saveTask(task);
                }
                if (!pendingImages.isEmpty()) {
                    // Tool messages cannot carry images in the chat-completions format, so images are
                    // attached in a follow-up user message right after the tool results.
                    ChatMessage imgMsg = ChatMessage.user("[Image output from " + String.join(", ", imageSources) + "]", pendingImages);
                    host.persist(task, imgMsg);
                }
                if (stopForLoop) {
                    return end(task, TaskState.Status.FAILED, "",
                            "Stopped: the agent kept repeating the same action without progress.");
                }
                if (cancel.isCancelled()) {
                    return end(task, TaskState.Status.CANCELLED, "", "Stopped by the user");
                }
            }
        } catch (LlmException e) {
            if (e.kind == LlmException.Kind.CANCELLED || cancel.isCancelled()) {
                return end(task, TaskState.Status.CANCELLED, "", "Stopped by the user");
            }
            return end(task, TaskState.Status.FAILED, "", e.userMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return end(task, TaskState.Status.CANCELLED, "", "Interrupted");
        } catch (Exception e) {
            return end(task, TaskState.Status.FAILED, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ChatResponse callModel(TaskState task) throws Exception {
        boolean compacted = false;
        int attempt = 0;
        while (true) {
            ChatRequest req = new ChatRequest(host.buildMessages(task));
            req.tools = host.toolDefinitions();
            if (req.tools.length() == 0) req.toolChoice = null;
            try {
                return llm.chat(req, new StreamListener() {
                    @Override
                    public void onTextDelta(String delta) {
                        listener.onTextDelta(task, delta);
                    }

                    @Override
                    public void onToolCallStarted(int index, String name) {
                        listener.onToolCallPreparing(task, name);
                    }
                }, cancel);
            } catch (LlmException e) {
                if (e.kind == LlmException.Kind.CONTEXT_LENGTH && !compacted) {
                    listener.onNotice(task, "Context too long — summarizing older messages");
                    compacted = true;
                    if (host.compactForOverflow(task)) continue;
                    throw e;
                }
                if (e.isRetryable() && attempt < config.llmRetries && !cancel.isCancelled()) {
                    attempt++;
                    long delay = e.retryAfterMs > 0 ? Math.min(e.retryAfterMs, 60_000) : config.retryBaseDelayMs * (1L << (attempt - 1));
                    listener.onNotice(task, e.userMessage() + " — retrying (" + attempt + "/" + config.llmRetries + ")");
                    sleepCancellable(delay);
                    continue;
                }
                throw e;
            }
        }
    }

    private void sleepCancellable(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!cancel.isCancelled() && System.currentTimeMillis() < end) {
            Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private void waitIfPaused(TaskState task) throws InterruptedException {
        if (!paused) return;
        setStatus(task, TaskState.Status.PAUSED);
        synchronized (pauseLock) {
            while (paused && !cancel.isCancelled()) pauseLock.wait(500);
        }
        if (!cancel.isCancelled()) setStatus(task, TaskState.Status.RUNNING);
    }

    private void setStatus(TaskState task, TaskState.Status s) {
        task.status = s;
        host.saveTask(task);
        listener.onStatusChanged(task);
    }

    private TaskState end(TaskState task, TaskState.Status status, String finalText, String error) {
        task.status = status;
        task.finalText = finalText == null ? "" : finalText;
        task.error = error == null ? "" : error;
        task.finishedAt = System.currentTimeMillis();
        host.saveTask(task);
        listener.onStatusChanged(task);
        listener.onFinished(task);
        return task;
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
