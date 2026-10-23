package com.suvo.agent.core;

import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.LlmException;
import com.suvo.agent.core.llm.ToolCall;
import com.suvo.agent.core.memory.ContextCompactor;
import com.suvo.agent.core.memory.InMemoryMemoryStore;
import com.suvo.agent.core.memory.MemoryCategory;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryExtractor;
import com.suvo.agent.core.memory.MemoryRanker;
import com.suvo.agent.core.memory.SecretFilter;
import com.suvo.agent.core.memory.TokenEstimator;
import com.suvo.agent.core.util.CancellationToken;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MemoryAndContextTest {

    private static MemoryEntry mem(String text, MemoryCategory c, int importance) {
        MemoryEntry e = new MemoryEntry();
        e.content = text;
        e.category = c;
        e.importance = importance;
        return e;
    }

    @Test
    public void storeAddSearchUpdateDelete() {
        InMemoryMemoryStore s = new InMemoryMemoryStore();
        MemoryEntry a = s.add(mem("User prefers tea over coffee", MemoryCategory.PREFERENCE, 3));
        s.add(mem("User is building an Android app called Your Friend", MemoryCategory.PROJECT, 4));
        assertEquals(2, s.count());
        assertEquals(1, s.searchCandidates("coffee", null, 10).size());
        a.content = "User prefers green tea";
        assertTrue(s.update(a));
        assertEquals("User prefers green tea", s.get(a.id).content);
        assertTrue(s.delete(a.id));
        assertNull(s.get(a.id));
        assertEquals(0, s.searchCandidates("tea", null, 10).size());
    }

    @Test
    public void rankerPrefersRelevantAndExplains() {
        long now = System.currentTimeMillis();
        List<MemoryEntry> c = new ArrayList<>();
        MemoryEntry app = mem("User is building an Android app called Your Friend", MemoryCategory.PROJECT, 4);
        app.id = 1;
        app.updatedAt = now;
        MemoryEntry tea = mem("User prefers tea", MemoryCategory.PREFERENCE, 2);
        tea.id = 2;
        tea.updatedAt = now - 200L * 86_400_000L;
        MemoryEntry rule = mem("Always answer in Hinglish", MemoryCategory.INSTRUCTION, 5);
        rule.id = 3;
        rule.updatedAt = now;
        c.add(tea);
        c.add(app);
        c.add(rule);
        List<MemoryRanker.Scored> r = new MemoryRanker().rank("how is my android app going", c, 5, now);
        assertEquals(1, r.get(0).entry.id);
        assertTrue(r.get(0).why.contains("android"));
        boolean hasRule = false;
        boolean hasTea = false;
        for (MemoryRanker.Scored s : r) {
            if (s.entry.id == 3) hasRule = true;
            if (s.entry.id == 2) hasTea = true;
        }
        assertTrue("instructions are always included", hasRule);
        assertFalse("irrelevant old memory is filtered", hasTea);
    }

    @Test
    public void secretsNeverEnterMemory() {
        assertNotNull(SecretFilter.rejectReason("my api key is sk-abcdefghijklmnopqrstuvwx"));
        assertNotNull(SecretFilter.rejectReason("password: hunter22"));
        assertNotNull(SecretFilter.rejectReason("card 4111 1111 1111 1111"));
        assertNull(SecretFilter.rejectReason("User likes biryani"));
        InMemoryMemoryStore s = new InMemoryMemoryStore();
        MemoryExtractor.Action a = new MemoryExtractor().save(mem("My password is hunter22", MemoryCategory.FACT, 3), s);
        assertEquals(MemoryExtractor.Action.Kind.REJECTED, a.kind);
        assertEquals(0, s.count());
        assertFalse(SecretFilter.redact("token ghp_abcdefghijklmnopqrstuvwxyz0123456789").contains("ghp_"));
    }

    @Test
    public void extractorDeduplicatesAndUpdates() {
        InMemoryMemoryStore s = new InMemoryMemoryStore();
        MemoryExtractor x = new MemoryExtractor();
        assertEquals(MemoryExtractor.Action.Kind.ADDED, x.save(mem("User lives in Kolkata", MemoryCategory.FACT, 3), s).kind);
        assertEquals(MemoryExtractor.Action.Kind.SKIPPED_DUPLICATE, x.save(mem("User lives in Kolkata.", MemoryCategory.FACT, 3), s).kind);
        assertEquals(MemoryExtractor.Action.Kind.UPDATED, x.save(mem("User lives in Kolkata, near Salt Lake", MemoryCategory.FACT, 3), s).kind);
        assertEquals(1, s.count());
        assertTrue(s.all(null, 10).get(0).content.contains("Salt Lake"));
    }

    @Test
    public void extractorUsesModelJson() {
        FakeLlm llm = new FakeLlm().text("Sure! {\"memories\":[{\"content\":\"User prefers dark mode\",\"category\":\"preference\",\"importance\":4,\"tags\":[\"ui\"]},"
                + "{\"content\":\"User's bank PIN: 1234\",\"category\":\"fact\"}]}");
        InMemoryMemoryStore s = new InMemoryMemoryStore();
        List<MemoryExtractor.Action> acts = new MemoryExtractor().extract(
                Collections.singletonList(ChatMessage.user("I always use dark mode. my pin: 1234")), s, llm, "conv:1", new CancellationToken());
        assertEquals(2, acts.size());
        assertEquals(MemoryExtractor.Action.Kind.ADDED, acts.get(0).kind);
        assertEquals(MemoryExtractor.Action.Kind.REJECTED, acts.get(1).kind);
        assertEquals(1, s.count());
        assertEquals(MemoryCategory.PREFERENCE, s.all(null, 5).get(0).category);
        assertEquals("conv:1", s.all(null, 5).get(0).source);
    }

    private static List<ChatMessage> longHistory(int turns) {
        List<ChatMessage> h = new ArrayList<>();
        long id = 1;
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 60; i++) filler.append("some words of conversation ");
        for (int i = 0; i < turns; i++) {
            ChatMessage u = ChatMessage.user("turn " + i + " " + filler);
            u.localId = id++;
            h.add(u);
            ChatMessage a = ChatMessage.assistant("", Collections.singletonList(new ToolCall("c" + i, "notify", "{}")));
            a.localId = id++;
            h.add(a);
            ChatMessage t = ChatMessage.tool("c" + i, "notify", "{\"status\":\"succeeded\"}");
            t.localId = id++;
            h.add(t);
            ChatMessage f = ChatMessage.assistant("answer " + i + " " + filler);
            f.localId = id++;
            h.add(f);
        }
        return h;
    }

    @Test
    public void compactionPlanKeepsToolGroupsIntact() {
        List<ChatMessage> h = longHistory(20);
        ContextCompactor cc = new ContextCompactor();
        int total = TokenEstimator.estimate(h);
        ContextCompactor.Plan p = cc.plan(h, total, 0, false); // history fills the whole window
        assertFalse(p.isEmpty());
        assertEquals(ChatMessage.Role.USER, p.toKeep.get(0).role);
        assertTrue(p.toKeep.size() >= cc.minKeepMessages);
        assertEquals(h.size(), p.toCompact.size() + p.toKeep.size());
        assertTrue(TokenEstimator.estimate(p.toKeep) < total);
        // Under the trigger nothing happens unless forced.
        assertTrue(cc.plan(h, total * 10, 0, false).isEmpty());
        assertFalse(cc.plan(h, total * 10, 0, true).isEmpty());
    }

    @Test
    public void summaryUsesModelOrFallsBack() {
        List<ChatMessage> h = longHistory(12);
        ContextCompactor cc = new ContextCompactor();
        ContextCompactor.Plan p = cc.plan(h, TokenEstimator.estimate(h), 0, false);
        FakeLlm ok = new FakeLlm().text("Facts & preferences: user is testing compaction thoroughly with many turns.");
        ContextCompactor.Result r = cc.summarize(p, "old summary", "Goal: test", ok, new CancellationToken());
        assertTrue(r.usedModel);
        assertEquals(p.compactedIds().size(), r.compactedIds.size());
        String prompt = ok.requests.get(0).messages.get(1).content;
        assertTrue(prompt.contains("old summary"));
        assertTrue(prompt.contains("Goal: test"));

        FakeLlm broken = new FakeLlm().error(new LlmException(LlmException.Kind.NETWORK, "down"));
        ContextCompactor.Result fb = cc.summarize(p, "old summary", "", broken, new CancellationToken());
        assertFalse(fb.usedModel);
        assertTrue(fb.summary.contains("old summary"));
        assertTrue(fb.summary.contains("User said: turn 0"));
    }

    @Test
    public void tokenEstimateCountsScriptsConservatively() {
        int en = TokenEstimator.estimate("hello world, how are you today?");
        int hi = TokenEstimator.estimate("नमस्ते दुनिया, आज आप कैसे हैं?");
        int ja = TokenEstimator.estimate("こんにちは世界、今日はお元気ですか");
        assertTrue(en > 5 && en < 15);
        assertTrue(hi > en);
        assertTrue(ja > en);
    }
}
