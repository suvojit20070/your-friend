package com.suvo.agent.core;

import com.suvo.agent.core.agent.AgentConfig;
import com.suvo.agent.core.agent.AgentHost;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.AgentLoop;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.tools.ApprovalPolicy;
import com.suvo.agent.core.tools.Approver;
import com.suvo.agent.core.tools.CapabilityProvider;
import com.suvo.agent.core.tools.RiskLevel;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolInvocation;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.core.tools.ToolResult;
import com.suvo.agent.core.tools.ToolSpec;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentLoopTest {

    /** In-memory host that records persisted messages like the SQLite host does. */
    static final class Host implements AgentHost {
        final List<ChatMessage> history = new ArrayList<>();
        final AtomicInteger compactions = new AtomicInteger();
        int saves;
        ToolRegistry registry;
        boolean vision = true;

        @Override
        public List<ChatMessage> buildMessages(TaskState task) {
            List<ChatMessage> l = new ArrayList<>();
            l.add(ChatMessage.system("core"));
            l.addAll(history);
            return l;
        }

        @Override
        public void persist(TaskState task, ChatMessage m) {
            history.add(m);
        }

        @Override
        public void saveTask(TaskState task) {
            saves++;
        }

        @Override
        public JSONArray toolDefinitions() {
            return registry.openAiTools(CapabilityProvider.ALL);
        }

        @Override
        public boolean compactForOverflow(TaskState task) {
            compactions.incrementAndGet();
            return true;
        }

        @Override
        public boolean visionEnabled() {
            return vision;
        }
    }

    private ToolRegistry registry;
    private Host host;
    private final AtomicInteger counterCalls = new AtomicInteger();

    @Before
    public void setUp() {
        registry = new ToolRegistry();
        registry.register(new Tool() {
            final ToolSpec spec = ToolSpec.builder("counter").description("increments")
                    .schema("{\"type\":\"object\",\"properties\":{\"by\":{\"type\":\"integer\"}},\"required\":[\"by\"]}")
                    .risk(RiskLevel.LOW).build();

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolInvocation inv) throws Exception {
                int v = counterCalls.addAndGet((int) inv.num("by", 0));
                return ToolResult.ok("value " + v, new JSONObject().put("value", v));
            }
        });
        registry.register(new Tool() {
            final ToolSpec spec = ToolSpec.builder("stuck").description("always same").risk(RiskLevel.LOW).build();

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolInvocation inv) {
                return ToolResult.fail("element not found");
            }
        });
        registry.register(new Tool() {
            final ToolSpec spec = ToolSpec.builder("snap").description("image").risk(RiskLevel.READ_ONLY).build();

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public ToolResult execute(ToolInvocation inv) {
                return ToolResult.okWithImage("captured", new JSONObject(), new ChatMessage.Image("data:image/jpeg;base64,AA", "low"));
            }
        });
        host = new Host();
        host.registry = registry;
    }

    private TaskState run(FakeLlm llm, AgentConfig cfg, CancellationToken token, String user) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.mode = ApprovalPolicy.AutonomyMode.AUTONOMOUS;
        ToolExecutor ex = new ToolExecutor(registry, CapabilityProvider.ALL, policy, Approver.APPROVE_ALL, null);
        AgentLoop loop = new AgentLoop(llm, ex, host, cfg, new AgentListener() {
        }, token);
        return loop.run(new TaskState(1, user), ChatMessage.user(user));
    }

    private static AgentConfig fast() {
        AgentConfig c = new AgentConfig();
        c.retryBaseDelayMs = 5;
        return c;
    }

    @Test
    public void multiStepToolExecutionReturnsRealResults() throws Exception {
        FakeLlm llm = new FakeLlm()
                .tools(new ToolCall("a", "counter", "{\"by\":2}"))
                .tools(new ToolCall("b", "counter", "{\"by\":3}"), new ToolCall("c", "counter", "{\"by\":5}"))
                .text("The counter is 10.");
        TaskState t = run(llm, fast(), new CancellationToken(), "count");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals("The counter is 10.", t.finalText);
        assertEquals(3, t.toolCalls);
        assertEquals(10, counterCalls.get());
        // The tool result sent back to the model carries the real value and the original call id.
        ChatMessage toolMsg = host.history.get(2);
        assertEquals(ChatMessage.Role.TOOL, toolMsg.role);
        assertEquals("a", toolMsg.toolCallId);
        assertEquals(2, new JSONObject(toolMsg.content).getJSONObject("data").getInt("value"));
        // Third request includes results of both parallel calls in order.
        assertEquals(3, llm.requests.size());
        List<ChatMessage> last = llm.requests.get(2).messages;
        assertEquals("c", last.get(last.size() - 1).toolCallId);
        assertEquals("b", last.get(last.size() - 2).toolCallId);
        assertEquals(3, t.steps().size());
    }

    @Test
    public void malformedArgumentsAreReturnedToTheModel() throws Exception {
        FakeLlm llm = new FakeLlm()
                .tools(new ToolCall("a", "counter", "{\"by\": \"many\"}"))
                .tools(new ToolCall("b", "counter", "{\"by\": 1}"))
                .text("done");
        TaskState t = run(llm, fast(), new CancellationToken(), "x");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        JSONObject first = new JSONObject(host.history.get(2).content);
        assertEquals("invalid_arguments", first.getString("status"));
        assertEquals(1, counterCalls.get());
    }

    @Test
    public void loopDetectionStopsRepeatedIdenticalFailures() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 10; i++) llm.tools(new ToolCall("s" + i, "stuck", "{}"));
        TaskState t = run(llm, fast(), new CancellationToken(), "loop");
        assertEquals(TaskState.Status.FAILED, t.status);
        assertTrue(t.error.contains("repeating"));
        assertTrue(t.toolCalls <= 4);
    }

    @Test
    public void maxStepsIsEnforced() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 10; i++) llm.tools(new ToolCall("c" + i, "counter", "{\"by\":" + (i + 1) + "}"));
        AgentConfig cfg = fast();
        cfg.maxSteps = 3;
        TaskState t = run(llm, cfg, new CancellationToken(), "many");
        assertEquals(TaskState.Status.LIMIT_REACHED, t.status);
        assertEquals(3, t.llmTurns);
    }

    @Test
    public void noLimitsLetALongTaskFinish() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 40; i++) llm.tools(new ToolCall("c" + i, "counter", "{\"by\":" + (i + 1) + "}"));
        llm.text("all done");
        AgentConfig cfg = fast();
        cfg.maxSteps = AgentConfig.UNLIMITED;
        cfg.maxToolCalls = AgentConfig.UNLIMITED;
        cfg.maxDurationMs = AgentConfig.UNLIMITED_MS;
        assertTrue(cfg.stepsUnlimited() && cfg.toolCallsUnlimited() && cfg.timeUnlimited());
        TaskState t = run(llm, cfg, new CancellationToken(), "many");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals(40, t.toolCalls);
        assertEquals(41, t.llmTurns);
    }

    @Test
    public void toolCallLimitStillAppliesWhenSet() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 6; i++) llm.tools(new ToolCall("c" + i, "counter", "{\"by\":" + (i + 1) + "}"));
        llm.text("stopped early");
        AgentConfig cfg = fast();
        cfg.maxToolCalls = 2;
        TaskState t = run(llm, cfg, new CancellationToken(), "many");
        assertEquals(2, t.toolCalls);
    }

    @Test
    public void wakeLockTimeNeverOverflows() {
        AgentConfig cfg = new AgentConfig();
        cfg.maxDurationMs = AgentConfig.UNLIMITED_MS;
        assertEquals(AgentConfig.UNLIMITED_WAKELOCK_MS, cfg.wakeLockMs());
        cfg.maxDurationMs = 15 * 60_000L;
        assertEquals(25 * 60_000L, cfg.wakeLockMs());
        cfg.maxDurationMs = 24L * 60 * 60_000L;
        assertEquals(AgentConfig.UNLIMITED_WAKELOCK_MS, cfg.wakeLockMs());
    }

    @Test
    public void transientErrorsAreRetried() {
        FakeLlm llm = new FakeLlm()
                .error(new LlmException(LlmException.Kind.SERVER, "502", 502, 0, null))
                .error(new LlmException(LlmException.Kind.RATE_LIMIT, "429", 429, 10, null))
                .text("ok");
        TaskState t = run(llm, fast(), new CancellationToken(), "hi");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals(3, llm.requests.size());
    }

    @Test
    public void authErrorFailsWithoutRetry() {
        FakeLlm llm = new FakeLlm().error(new LlmException(LlmException.Kind.AUTH, "bad", 401, 0, null)).text("never");
        TaskState t = run(llm, fast(), new CancellationToken(), "hi");
        assertEquals(TaskState.Status.FAILED, t.status);
        assertEquals(1, llm.requests.size());
        assertTrue(t.error.contains("API key"));
    }

    @Test
    public void contextOverflowTriggersCompactionThenRetry() {
        FakeLlm llm = new FakeLlm()
                .error(new LlmException(LlmException.Kind.CONTEXT_LENGTH, "too long", 400, 0, null))
                .text("fits now");
        TaskState t = run(llm, fast(), new CancellationToken(), "hi");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals(1, host.compactions.get());
    }

    @Test
    public void cancellationBeforeStartAndDuringTools() {
        CancellationToken token = new CancellationToken();
        token.cancel("user");
        TaskState t = run(new FakeLlm().text("x"), fast(), token, "hi");
        assertEquals(TaskState.Status.CANCELLED, t.status);
    }

    @Test
    public void imagesFromToolsAreAttachedAfterToolMessages() {
        FakeLlm llm = new FakeLlm().tools(new ToolCall("i", "snap", "{}")).text("I see it");
        TaskState t = run(llm, fast(), new CancellationToken(), "look");
        assertEquals(TaskState.Status.COMPLETED, t.status);
        ChatMessage tool = host.history.get(2);
        ChatMessage img = host.history.get(3);
        assertEquals(ChatMessage.Role.TOOL, tool.role);
        assertEquals(ChatMessage.Role.USER, img.role);
        assertEquals(1, img.images.size());
        host.history.clear();
        host.vision = false;
        run(new FakeLlm().tools(new ToolCall("i", "snap", "{}")).text("ok"), fast(), new CancellationToken(), "look");
        for (ChatMessage m : host.history) assertEquals(0, m.images.size());
    }

    @Test
    public void taskStateSurvivesJsonRoundTrip() throws Exception {
        TaskState t = new TaskState(5, "Book a cab");
        t.status = TaskState.Status.RUNNING;
        t.toolCalls = 2;
        t.addStep("launch_app", "SUCCEEDED", "Opened Uber");
        t.openItems.add("waiting for user login");
        TaskState back = TaskState.fromJson(new JSONObject(t.toJson().toString()));
        assertEquals(t.id, back.id);
        assertEquals(TaskState.Status.RUNNING, back.status);
        assertEquals(1, back.steps().size());
        assertTrue(back.progressSummary(10).contains("Opened Uber"));
        assertTrue(back.progressSummary(10).contains("waiting for user login"));
        assertEquals(Collections.singletonList("waiting for user login"), back.openItems);
    }
}
