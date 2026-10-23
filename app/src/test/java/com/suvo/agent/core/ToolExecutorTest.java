package com.suvo.agent.core;

import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.ApprovalPolicy;
import com.suvo.agent.core.tools.Approver;
import com.suvo.agent.core.tools.Capability;
import com.suvo.agent.core.tools.CapabilityProvider;
import com.suvo.agent.core.tools.CapabilityState;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolEvent;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolExecutorTest {

    private ToolRegistry registry;
    private ApprovalPolicy policy;
    private final List<ToolEvent> events = new ArrayList<>();
    private final AtomicInteger approvals = new AtomicInteger();
    private Approver.Outcome approverAnswer = Approver.Outcome.APPROVED;
    private CapabilityState accessibility = CapabilityState.AVAILABLE;
    private final AtomicInteger executions = new AtomicInteger();

    private final CapabilityProvider caps = new CapabilityProvider() {
        @Override
        public CapabilityState state(Capability c) {
            return c == Capability.ACCESSIBILITY ? accessibility : CapabilityState.AVAILABLE;
        }

        @Override
        public String detail(Capability c) {
            return "Enable Your Friend in Settings > Accessibility";
        }
    };

    private Tool tool(String name, RiskLevel risk, long timeout, Capability... req) {
        ToolSpec spec = ToolSpec.builder(name).description(name)
                .schema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},\"sleep\":{\"type\":\"integer\"},\"boom\":{\"type\":\"boolean\"}},\"additionalProperties\":false}")
                .risk(risk).timeoutMs(timeout).requires(req).build();
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolInvocation inv) throws Exception {
                executions.incrementAndGet();
                if (inv.bool("boom", false)) throw new IllegalStateException("backend exploded");
                long sleep = inv.num("sleep", 0);
                long end = System.currentTimeMillis() + sleep;
                while (System.currentTimeMillis() < end) {
                    if (inv.cancel.isCancelled()) return ToolResult.of(ToolResult.Status.CANCELLED, "stopped");
                    Thread.sleep(10);
                }
                return ToolResult.ok("echo " + inv.str("text"), new JSONObject().put("text", inv.str("text")));
            }
        };
    }

    private ToolExecutor executor() {
        return new ToolExecutor(registry, caps, policy, (r, t, c) -> {
            approvals.incrementAndGet();
            return approverAnswer;
        }, events::add);
    }

    @Before
    public void setUp() {
        registry = new ToolRegistry();
        policy = new ApprovalPolicy();
        policy.mode = ApprovalPolicy.AutonomyMode.AUTONOMOUS;
        registry.register(tool("echo", RiskLevel.LOW, 2000));
        registry.register(tool("read", RiskLevel.READ_ONLY, 2000));
        registry.register(tool("tap", RiskLevel.MEDIUM, 2000, Capability.ACCESSIBILITY));
        registry.register(tool("wipe", RiskLevel.HIGH, 2000));
    }

    private ToolResult run(String name, String args) {
        return executor().execute(new ToolCall("id1", name, args), "task", new CancellationToken(), null);
    }

    @Test
    public void successfulLifecycleEmitsAllPhases() {
        ToolResult r = run("echo", "{\"text\":\"hi\"}");
        assertTrue(r.succeeded());
        assertEquals("echo hi", r.message);
        List<ToolEvent.Phase> phases = new ArrayList<>();
        for (ToolEvent e : events) phases.add(e.phase);
        assertEquals(ToolEvent.Phase.REQUESTED, phases.get(0));
        assertTrue(phases.contains(ToolEvent.Phase.VALIDATED));
        assertTrue(phases.contains(ToolEvent.Phase.AUTHORIZED));
        assertTrue(phases.contains(ToolEvent.Phase.EXECUTING));
        assertEquals(ToolEvent.Phase.SUCCEEDED, phases.get(phases.size() - 1));
    }

    @Test
    public void unknownToolAndBadArguments() {
        assertEquals(ToolResult.Status.FAILED, run("nope", "{}").status);
        assertEquals(ToolResult.Status.INVALID_ARGUMENTS, run("echo", "{oops").status);
        assertEquals(ToolResult.Status.INVALID_ARGUMENTS, run("echo", "{\"text\":5}").status);
        assertEquals(ToolResult.Status.INVALID_ARGUMENTS, run("echo", "{\"other\":1}").status);
        assertEquals(0, executions.get());
    }

    @Test
    public void missingCapabilityReportsRealReason() {
        accessibility = CapabilityState.AUTHORIZATION_REQUIRED;
        ToolResult r = run("tap", "{}");
        assertEquals(ToolResult.Status.UNAVAILABLE, r.status);
        assertTrue(r.message.contains("ACCESSIBILITY"));
        assertTrue(r.message.contains("Settings > Accessibility"));
        assertEquals(0, executions.get());
        assertFalse(registry.openAiTools(caps).toString().contains("\"tap\""));
    }

    @Test
    public void assistModeOnlyRunsReadOnlyTools() {
        policy.mode = ApprovalPolicy.AutonomyMode.ASSIST;
        assertEquals(ToolResult.Status.DENIED, run("echo", "{}").status);
        assertTrue(run("read", "{}").succeeded());
    }

    @Test
    public void confirmModeAsksForMediumAndHigh() {
        policy.mode = ApprovalPolicy.AutonomyMode.CONFIRM;
        assertTrue(run("echo", "{}").succeeded());
        assertEquals(0, approvals.get());
        assertTrue(run("tap", "{}").succeeded());
        assertEquals(1, approvals.get());
        approverAnswer = Approver.Outcome.DENIED;
        assertEquals(ToolResult.Status.DENIED, run("wipe", "{}").status);
        assertEquals(2, approvals.get());
    }

    @Test
    public void autonomousStillAsksForHighRisk() {
        approverAnswer = Approver.Outcome.TIMED_OUT;
        ToolResult r = run("wipe", "{}");
        assertEquals(ToolResult.Status.DENIED, r.status);
        assertEquals(1, approvals.get());
        assertTrue(run("tap", "{}").succeeded());
        assertEquals(1, approvals.get());
    }

    @Test
    public void blockedAndDisabledTools() {
        policy.neverAllow.add("echo");
        assertEquals(ToolResult.Status.DENIED, run("echo", "{}").status);
        registry.setEnabled("read", false);
        assertEquals(ToolResult.Status.UNAVAILABLE, run("read", "{}").status);
        assertFalse(registry.openAiTools(caps).toString().contains("\"read\""));
    }

    @Test
    public void timeoutAndExceptionsBecomeResults() {
        registry.register(tool("slow", RiskLevel.LOW, 300));
        ToolResult r = run("slow", "{\"sleep\":5000}");
        assertEquals(ToolResult.Status.TIMED_OUT, r.status);
        ToolResult boom = run("echo", "{\"boom\":true}");
        assertEquals(ToolResult.Status.FAILED, boom.status);
        assertTrue(boom.message.contains("backend exploded"));
    }

    @Test
    public void cancellationStopsRunningTool() throws Exception {
        CancellationToken token = new CancellationToken();
        new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            token.cancel("user");
        }).start();
        long t0 = System.currentTimeMillis();
        ToolResult r = executor().execute(new ToolCall("x", "echo", "{\"sleep\":1500}"), "t", token, null);
        assertEquals(ToolResult.Status.CANCELLED, r.status);
        assertTrue(System.currentTimeMillis() - t0 < 1200);
    }

    @Test
    public void customToolCannotShadowBuiltin() {
        ToolSpec spec = ToolSpec.builder("echo").builtin(false).build();
        boolean threw = false;
        try {
            registry.register(new Tool() {
                @Override
                public ToolSpec spec() {
                    return spec;
                }

                @Override
                public ToolResult execute(ToolInvocation inv) {
                    return ToolResult.ok("fake");
                }
            });
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void auditRedactsSecrets() {
        String red = ToolExecutor.redact("{\"user\":\"suvo\",\"password\":\"hunter2\",\"cfg\":{\"api_key\":\"sk-1\"}}");
        assertFalse(red.contains("hunter2"));
        assertFalse(red.contains("sk-1"));
        assertTrue(red.contains("suvo"));
    }

    @Test
    public void resultSerializationIsBoundedJson() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        ToolResult r = ToolResult.ok("done", new JSONObject().put("out", big.toString()));
        String s = r.toModelContent(2000);
        assertTrue(s.length() <= 2600);
        JSONObject parsed = new JSONObject(s);
        assertEquals("succeeded", parsed.getString("status"));
        assertTrue(parsed.has("data_truncated"));
    }
}
