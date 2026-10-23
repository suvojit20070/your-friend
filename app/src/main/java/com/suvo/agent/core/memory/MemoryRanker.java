package com.suvo.agent.core.memory;

import com.suvo.agent.core.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores memories for a query using keyword overlap, importance, recency, category and pinning,
 * and records why each memory was picked (shown in the Memory screen's debug view).
 */
public final class MemoryRanker {

    public static final class Scored {
        public final MemoryEntry entry;
        public final double score;
        public final String why;

        Scored(MemoryEntry entry, double score, String why) {
            this.entry = entry;
            this.score = score;
            this.why = why;
        }
    }

    /** Optional semantic similarity (e.g. local embeddings). Returns -1 when unavailable. */
    public interface SemanticScorer {
        double similarity(String query, MemoryEntry entry);
    }

    private SemanticScorer semantic;
    public double minScore = 0.18;

    public void setSemanticScorer(SemanticScorer s) {
        this.semantic = s;
    }

    public List<Scored> rank(String query, List<MemoryEntry> candidates, int limit, long now) {
        Set<String> q = TextUtil.tokens(query);
        List<Scored> out = new ArrayList<>();
        for (MemoryEntry e : candidates) {
            if (e.isExpired(now)) continue;
            Set<String> t = TextUtil.tokens(e.content + " " + e.tagsCsv());
            int overlap = 0;
            List<String> matched = new ArrayList<>();
            for (String s : q) {
                if (t.contains(s)) {
                    overlap++;
                    if (matched.size() < 5) matched.add(s);
                }
            }
            double keyword = q.isEmpty() ? 0 : (double) overlap / Math.min(q.size(), Math.max(1, t.size()));
            keyword = Math.min(1.0, keyword);
            double importance = (e.importance - 1) / 4.0;
            double ageDays = Math.max(0, (now - Math.max(e.updatedAt, e.lastUsedAt)) / 86_400_000.0);
            double recency = Math.exp(-ageDays / 45.0);
            double sem = semantic == null ? -1 : semantic.similarity(query, e);
            double catBoost = 0;
            if (e.category == MemoryCategory.INSTRUCTION || e.category == MemoryCategory.PREFERENCE) catBoost = 0.08;
            if (e.pinned) catBoost += 0.1;

            double score = sem >= 0
                    ? 0.35 * keyword + 0.35 * sem + 0.15 * importance + 0.07 * recency + catBoost
                    : 0.6 * keyword + 0.2 * importance + 0.1 * recency + catBoost;

            StringBuilder why = new StringBuilder();
            why.append(String.format(Locale.ROOT, "score %.2f: ", score));
            if (!matched.isEmpty()) why.append("keywords ").append(matched).append("; ");
            if (sem >= 0) why.append(String.format(Locale.ROOT, "semantic %.2f; ", sem));
            why.append("importance ").append(e.importance).append("; ");
            why.append(String.format(Locale.ROOT, "age %.0f d", ageDays));
            if (e.pinned) why.append("; pinned");
            out.add(new Scored(e, score, why.toString()));
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        List<Scored> res = new ArrayList<>();
        for (Scored s : out) {
            if (res.size() >= limit) break;
            // Instructions and pinned memories are always eligible; others need some relevance.
            boolean always = s.entry.pinned || s.entry.category == MemoryCategory.INSTRUCTION;
            if (always || s.score >= minScore) res.add(s);
        }
        return res;
    }
}
