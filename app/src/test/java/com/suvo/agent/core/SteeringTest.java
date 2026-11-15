package com.suvo.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.suvo.agent.core.agent.AgentConfig;
import com.suvo.agent.core.agent.AgentHost;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.AgentLoop;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.tools.ApprovalPolicy;
import com.suvo.agent.core.tools.Approver;
import com.suvo.agent.core.tools.CapabilityProvider;
import com.suvo.agent.core.tools.ToolExecutor;
import com.suvo.agent.core.tools.ToolRegistry;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Messages written while the agent works are read at its next step, never lost or refused. */
public class SteeringTest {

    static final class Host implements AgentHost {
        final List<ChatMessage> history = new ArrayList<>();
        final List<ChatMessage> incoming = new ArrayList<>();

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
        }

        @Override
        public JSONArray toolDefinitions() {
            return new JSONArray();
        }

        @Override
        public boolean compactForOverflow(TaskState task) {
            return false;
        }

        @Override
        public boolean visionEnabled() {
            return false;
        }

        @Override
        public synchronized List<ChatMessage> takeIncoming() {
            List<ChatMessage> out = new ArrayList<>(incoming);
            incoming.clear();
            return out;
        }

        @Override
        public synchronized boolean hasIncoming() {
            return !incoming.isEmpty();
        }
    }

    private static AgentConfig cfg() {
        AgentConfig c = new AgentConfig();
        c.followThroughReminders = 0;
        c.completionChecks = 0;
        return c;
    }

    @Test
    public void messageDuringTheAnswerIsAnsweredInTheSameTask() {
        Host host = new Host();
        FakeLlm llm = new FakeLlm().text("Building the page now.").text("OK, making it blue instead.");
        ApprovalPolicy policy = new ApprovalPolicy();
        ToolExecutor ex = new ToolExecutor(new ToolRegistry(), CapabilityProvider.ALL, policy, Approver.APPROVE_ALL, null);
        final int[] incomingSeen = {0};
        AgentLoop loop = new AgentLoop(llm, ex, host, cfg(), new AgentListener() {
            boolean once;

            @Override
            public void onAssistantMessage(TaskState task, ChatMessage message) {
                // The user writes again while the first answer is being given.
                if (!once) {
                    once = true;
                    synchronized (host) {
                        host.incoming.add(ChatMessage.user("make it blue"));
                    }
                }
            }

            @Override
            public void onIncoming(TaskState task, ChatMessage message) {
                incomingSeen[0]++;
            }
        }, new CancellationToken());
        TaskState t = loop.run(new TaskState(1, "make a page"), ChatMessage.user("make a page"));
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals("OK, making it blue instead.", t.finalText);
        assertEquals(1, incomingSeen[0]);
        // Order in the conversation: user, answer, the new message, the second answer.
        assertEquals(4, host.history.size());
        assertEquals("make it blue", host.history.get(2).contentOrEmpty());
        assertEquals(2, llm.requests.size());
        assertTrue(llm.requests.get(1).messages.toString().contains("make it blue")
                || llm.requests.get(1).messages.size() == 4);
    }

    @Test
    public void nothingWaitingEndsNormally() {
        Host host = new Host();
        FakeLlm llm = new FakeLlm().text("done");
        ToolExecutor ex = new ToolExecutor(new ToolRegistry(), CapabilityProvider.ALL, new ApprovalPolicy(), Approver.APPROVE_ALL, null);
        TaskState t = new AgentLoop(llm, ex, host, cfg(), null, new CancellationToken()).run(new TaskState(1, "x"), ChatMessage.user("x"));
        assertEquals(TaskState.Status.COMPLETED, t.status);
        assertEquals(1, llm.requests.size());
    }
}
