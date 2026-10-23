package com.suvo.agent.core.agent;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmConfig;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.memory.MemoryManager;
import com.suvo.agent.core.persona.Persona;
import com.suvo.agent.core.persona.SystemPromptBuilder;
import com.suvo.agent.core.tools.Tool;
import com.suvo.agent.core.tools.ToolRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The brain. One loop:
 *   build messages -> LLM (stream, tools) -> if tool_calls: run them, feed back -> repeat
 *                   -> else: final text.
 * Sentences are emitted as they stream so the voice layer can speak early.
 *
 * Long-term memory: when the running history grows past a threshold, the oldest
 * turns are compressed into persistent memory (via {@link MemoryManager}) before
 * being dropped — so nothing old is forgotten, just condensed.
 *
 * Run {@link #send} on a background thread.
 */
public class AgentCore {

    private static final int MAX_TOOL_STEPS = 12;

    private final LlmClient llm = new LlmClient();
    private final ToolRegistry tools;
    private final MemoryManager memory;

    private LlmConfig config;
    private Persona persona;

    private final List<ChatMessage> history = new ArrayList<>();
    private final int maxHistory;          // turns (user+assistant pairs counted loosely)

    public AgentCore(ToolRegistry tools, MemoryManager memory, int maxHistory) {
        this.tools = tools;
        this.memory = memory;
        this.maxHistory = Math.max(6, maxHistory);
    }

    public void setConfig(LlmConfig c) { this.config = c; }
    public void setPersona(Persona p) { this.persona = p; }
    public synchronized void clearHistory() { history.clear(); }

    public String send(String userText, AgentListener cb) {
        if (config == null || !config.isValid()) {
            cb.onError("LLM provider not set. Add base URL, key and model in Settings.");
            return "";
        }

        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system(
                SystemPromptBuilder.build(persona, tools.activeSchemas(), memory.block())));
        synchronized (this) {
            for (ChatMessage m : history) msgs.add(m);
        }
        ChatMessage userMsg = ChatMessage.user(userText);
        msgs.add(userMsg);

        String finalText = "";

        try {
            for (int step = 0; step < MAX_TOOL_STEPS; step++) {
                final SentenceSplitter splitter = new SentenceSplitter(cb);

                LlmClient.Turn turn = llm.chat(config, msgs, tools.activeSchemas(),
                        delta -> { cb.onReplyDelta(delta); splitter.feed(delta); });
                splitter.flush();

                ChatMessage assistant = ChatMessage.assistant(turn.text);
                assistant.toolCalls = turn.toolCalls;
                msgs.add(assistant);

                if (turn.toolCalls.isEmpty()) {
                    finalText = turn.text;
                    cb.onReplyDone(finalText);
                    break;
                }
                for (ToolCall call : turn.toolCalls) {
                    String result = runTool(call, cb);
                    msgs.add(ChatMessage.toolResult(call.id, result));
                }
            }
        } catch (Exception e) {
            cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            return "";
        }

        // persist + compress
        synchronized (this) {
            history.add(userMsg);
            history.add(ChatMessage.assistant(finalText));
            maybeCompress();
        }
        return finalText;
    }

    /** When history is too long, compress the oldest half into long-term memory, then drop it. */
    private void maybeCompress() {
        int limit = maxHistory * 2;          // messages
        if (history.size() <= limit) return;

        int dropCount = history.size() - maxHistory;   // keep newest maxHistory messages
        if (dropCount < 2) return;
        List<ChatMessage> old = new ArrayList<>(history.subList(0, dropCount));

        // compress synchronously (we're already on a background thread in send())
        memory.compressAndStore(config, old);

        for (int i = 0; i < dropCount; i++) history.remove(0);
    }

    private String runTool(ToolCall call, AgentListener cb) {
        Tool t = tools.get(call.name);
        if (t == null) return "Error: no tool named '" + call.name + "'";
        if (!tools.isEnabled(call.name) || !t.isAvailable())
            return "Error: tool '" + call.name + "' is not available right now.";

        if ("high".equals(t.schema().danger)) {
            boolean ok = cb.confirmDangerous(call.name, call.argumentsJson);
            if (!ok) return "User declined to run '" + call.name + "'.";
        }

        cb.onToolStart(call.name);
        String result;
        try {
            JSONObject args = call.argsAsJson();
            result = t.run(args);
        } catch (Exception e) {
            result = "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (result == null) result = "(no result)";
        cb.onToolResult(call.name, result);
        return result;
    }

    /** Emits complete sentences from a stream so the voice layer can speak early. */
    private static class SentenceSplitter {
        private final AgentListener cb;
        private final StringBuilder buf = new StringBuilder();
        SentenceSplitter(AgentListener cb) { this.cb = cb; }

        void feed(String delta) {
            buf.append(delta);
            int cut;
            while ((cut = endOfSentence(buf)) > 0) {
                String s = buf.substring(0, cut).trim();
                buf.delete(0, cut);
                if (s.length() >= 2) cb.onSentence(s);
            }
        }
        void flush() {
            String s = buf.toString().trim();
            buf.setLength(0);
            if (!s.isEmpty()) cb.onSentence(s);
        }
        private int endOfSentence(StringBuilder b) {
            for (int i = 0; i < b.length(); i++) {
                char c = b.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') {
                    if (i + 1 >= b.length() || Character.isWhitespace(b.charAt(i + 1)))
                        return i + 1;
                }
            }
            return -1;
        }
    }
}
