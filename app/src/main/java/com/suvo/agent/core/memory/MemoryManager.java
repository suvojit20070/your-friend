package com.suvo.agent.core.memory;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.LlmConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-term memory with automatic compression.
 *
 * Context gets cut as it grows, so BEFORE old turns are dropped, we ask the model
 * to compress them into a few durable fact-lines and append to persistent memory.
 * That compressed block is injected into every future prompt, so even the oldest
 * things are remembered (in condensed form). When memory itself grows large, it is
 * re-compressed so it never bloats the prompt.
 */
public class MemoryManager {

    private final Memory store;
    private final LlmClient llm;
    private int maxMemoryChars = 6000;   // keep the injected block small

    public MemoryManager(Memory store, LlmClient llm) {
        this.store = store;
        this.llm = llm;
    }

    public String block() { return store.read(); }

    public void remember(String fact) {
        if (fact != null && !fact.trim().isEmpty()) store.append("- " + fact.trim());
    }

    public void clear() { store.clear(); }

    /**
     * Compress the given old messages into durable facts and merge into memory.
     * Runs synchronously (call off the UI thread). Safe: on any error it does nothing.
     */
    public void compressAndStore(LlmConfig cfg, List<ChatMessage> oldTurns) {
        if (cfg == null || !cfg.isValid() || oldTurns == null || oldTurns.isEmpty()) return;
        try {
            StringBuilder convo = new StringBuilder();
            for (ChatMessage m : oldTurns) {
                if (m.content == null || m.content.isEmpty()) continue;
                convo.append(m.role).append(": ").append(m.content).append('\n');
            }
            String existing = store.read();

            List<ChatMessage> req = new ArrayList<>();
            req.add(ChatMessage.system(
                    "You maintain a compact long-term memory of a user for an assistant. "
                  + "Merge the NEW conversation into the EXISTING memory. Keep durable facts only "
                  + "(who the user is, preferences, ongoing projects, decisions, promises, important "
                  + "events). Drop small talk. Keep it as short bullet lines, newest kept, no duplicates. "
                  + "Output ONLY the updated memory, nothing else."));
            req.add(ChatMessage.user(
                    "EXISTING MEMORY:\n" + (existing.isEmpty() ? "(empty)" : existing)
                  + "\n\nNEW CONVERSATION:\n" + convo));

            // non-streaming, no tools
            LlmConfig c = shallowNoStream(cfg);
            LlmClient.Turn turn = llm.chat(c, req, null, null);
            String merged = turn.text == null ? "" : turn.text.trim();
            if (!merged.isEmpty()) {
                if (merged.length() > maxMemoryChars) merged = merged.substring(0, maxMemoryChars);
                store.overwrite(merged);
            }
        } catch (Exception ignore) {
            // memory is best-effort; never break the turn
        }
    }

    private static LlmConfig shallowNoStream(LlmConfig c) {
        LlmConfig n = new LlmConfig(c.name, c.baseUrl, c.apiKey, c.model);
        n.stream = false;
        n.temperature = 0.3;
        n.maxTokens = 0;
        return n;
    }
}
