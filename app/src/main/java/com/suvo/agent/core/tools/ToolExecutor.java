package com.suvo.agent.core.tools;

import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a model-requested tool call through the full lifecycle:
 * Requested → Validated → Authorized → Executing → Succeeded / Failed / Cancelled.
 * Validation, capability checks and approval all happen here, outside the language model.
 */
public final class ToolExecutor {

    private final ToolRegistry registry;
    private final CapabilityProvider capabilities;
    private final ApprovalPolicy policy;
    private final Approver approver;
    private final ToolEvent.Sink sink;
    private final ExecutorService pool;
    public long approvalTimeoutMs = 5 * 60_000L;

    public ToolExecutor(ToolRegistry registry, CapabilityProvider capabilities, ApprovalPolicy policy,
                        Approver approver, ToolEvent.Sink sink) {
        this.registry = registry;
        this.capabilities = capabilities;
        this.policy = policy;
        this.approver = approver;
        this.sink = sink == null ? ToolEvent.Sink.NONE : sink;
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public ToolRegistry registry() {
        return registry;
    }

    public ApprovalPolicy policy() {
        return policy;
    }

    public ToolResult execute(ToolCall call, String taskId, CancellationToken cancel, ToolInvocation.Progress progress) {
        String name = call.name;
        emit(taskId, call.id, name, ToolEvent.Phase.REQUESTED, "", redact(call.argumentsJson));

        Tool tool = registry.get(name);
        if (tool == null) {
            return finish(taskId, call, ToolResult.of(ToolResult.Status.FAILED,
                    "Unknown tool \"" + name + "\". Use list_tools to see available tools."));
        }
        JSONObject args;
        try {
            args = call.parseArguments();
        } catch (JSONException e) {
            return finish(taskId, call, ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS,
                    "Arguments are not valid JSON: " + e.getMessage() + ". Send a JSON object matching the schema."));
        }
        SchemaValidator.Result vr = SchemaValidator.validate(tool.spec().inputSchema, args);
        if (!vr.ok()) {
            return finish(taskId, call, ToolResult.of(ToolResult.Status.INVALID_ARGUMENTS, vr.message()));
        }
        args = vr.value;
        emit(taskId, call.id, name, ToolEvent.Phase.VALIDATED, "", redact(args.toString()));

        String unavailable = registry.unavailableReason(tool, capabilities);
        if (unavailable != null) {
            return finish(taskId, call, ToolResult.of(ToolResult.Status.UNAVAILABLE,
                    "Tool \"" + name + "\" is not available: " + unavailable));
        }

        RiskLevel risk;
        try {
            risk = tool.riskFor(args);
        } catch (RuntimeException e) {
            risk = RiskLevel.HIGH;
        }
        ApprovalPolicy.Decision decision = policy.decide(name, risk);
        if (decision == ApprovalPolicy.Decision.BLOCK) {
            String why = policy.neverAllow.contains(name)
                    ? "The user has blocked this tool."
                    : "Assist mode is active: the agent may only suggest this action. Describe what you would do and let the user decide.";
            return finish(taskId, call, ToolResult.of(ToolResult.Status.DENIED, why));
        }
        if (decision == ApprovalPolicy.Decision.ASK) {
            String summary;
            try {
                summary = tool.describeCall(args);
            } catch (RuntimeException e) {
                summary = name;
            }
            Approver.Outcome outcome = approver.request(new Approver.Request(taskId, call.id, name, risk, summary),
                    approvalTimeoutMs, cancel);
            if (cancel.isCancelled()) {
                return finish(taskId, call, ToolResult.of(ToolResult.Status.CANCELLED, "Task cancelled while waiting for approval"));
            }
            if (outcome != Approver.Outcome.APPROVED) {
                return finish(taskId, call, ToolResult.of(ToolResult.Status.DENIED,
                        outcome == Approver.Outcome.TIMED_OUT ? "The user did not respond to the approval request." : "The user declined this action."));
            }
        }
        emit(taskId, call.id, name, ToolEvent.Phase.AUTHORIZED, risk.name() + " " + decision.name(), "");

        if (cancel.isCancelled()) {
            return finish(taskId, call, ToolResult.of(ToolResult.Status.CANCELLED, "Cancelled before execution"));
        }
        emit(taskId, call.id, name, ToolEvent.Phase.EXECUTING, "", "");
        CancellationToken child = cancel.child();
        final ToolInvocation inv = new ToolInvocation(call.id, taskId, args, child, progress);
        Future<ToolResult> future = pool.submit(() -> tool.execute(inv));
        ToolResult result;
        try {
            result = waitFor(future, tool.spec().timeoutMs, cancel);
            if (result == null) result = ToolResult.fail("Tool returned no result");
        } catch (TimeoutException e) {
            child.cancel("timeout");
            future.cancel(true);
            result = ToolResult.of(ToolResult.Status.TIMED_OUT, "Timed out after " + tool.spec().timeoutMs / 1000 + " s");
        } catch (CancellationToken.CancelledException e) {
            child.cancel("task cancelled");
            future.cancel(true);
            result = ToolResult.of(ToolResult.Status.CANCELLED, "Cancelled");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (child.isCancelled()) result = ToolResult.of(ToolResult.Status.CANCELLED, "Cancelled");
            else result = ToolResult.fail(c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.cancel("interrupted");
            future.cancel(true);
            result = ToolResult.of(ToolResult.Status.CANCELLED, "Interrupted");
        }
        return finish(taskId, call, result);
    }

    /** Waits in short slices so task cancellation is noticed promptly. */
    private static ToolResult waitFor(Future<ToolResult> f, long timeoutMs, CancellationToken cancel)
            throws TimeoutException, ExecutionException, InterruptedException, CancellationToken.CancelledException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            cancel.throwIfCancelled();
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new TimeoutException();
            try {
                return f.get(Math.min(left, 200), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // loop
            }
        }
    }

    private ToolResult finish(String taskId, ToolCall call, ToolResult r) {
        ToolEvent.Phase phase;
        switch (r.status) {
            case SUCCEEDED:
                phase = ToolEvent.Phase.SUCCEEDED;
                break;
            case CANCELLED:
                phase = ToolEvent.Phase.CANCELLED;
                break;
            case DENIED:
                phase = ToolEvent.Phase.DENIED;
                break;
            default:
                phase = ToolEvent.Phase.FAILED;
        }
        emit(taskId, call.id, call.name, phase, r.status.name() + ": " + r.message, "");
        return r;
    }

    private void emit(String taskId, String callId, String name, ToolEvent.Phase phase, String detail, String args) {
        try {
            sink.onEvent(new ToolEvent(taskId, callId, name, phase, detail, args));
        } catch (RuntimeException ignored) {
            // Audit failures never break execution.
        }
    }

    /** Redacts values of keys that look like secrets before they reach logs or the audit trail. */
    public static String redact(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            Object v = new org.json.JSONTokener(json).nextValue();
            if (v instanceof JSONObject) {
                redactObject((JSONObject) v);
                String s = v.toString();
                return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
            }
        } catch (JSONException ignored) {
        }
        return json.length() > 200 ? json.substring(0, 200) + "…" : json;
    }

    private static void redactObject(JSONObject o) throws JSONException {
        Iterator<String> it = o.keys();
        java.util.List<String> keys = new java.util.ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) {
            String lk = k.toLowerCase(Locale.ROOT);
            Object val = o.get(k);
            if (lk.contains("password") || lk.contains("secret") || lk.contains("token") || lk.equals("key")
                    || lk.contains("api_key") || lk.contains("apikey") || lk.contains("passphrase") || lk.contains("private_key")) {
                o.put(k, "***");
            } else if (val instanceof JSONObject) {
                redactObject((JSONObject) val);
            } else if (val instanceof String && ((String) val).length() > 500) {
                o.put(k, ((String) val).substring(0, 500) + "…");
            }
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
