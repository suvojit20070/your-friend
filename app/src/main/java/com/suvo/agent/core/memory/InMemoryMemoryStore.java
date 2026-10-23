package com.suvo.agent.core.memory;

import com.suvo.agent.core.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Simple store for tests and as a fallback if the database cannot be opened. */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<Long, MemoryEntry> map = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);

    @Override
    public MemoryEntry add(MemoryEntry e) {
        long now = System.currentTimeMillis();
        e.id = ids.getAndIncrement();
        if (e.createdAt == 0) e.createdAt = now;
        e.updatedAt = now;
        map.put(e.id, copy(e));
        return e;
    }

    @Override
    public boolean update(MemoryEntry e) {
        if (!map.containsKey(e.id)) return false;
        e.updatedAt = System.currentTimeMillis();
        map.put(e.id, copy(e));
        return true;
    }

    @Override
    public boolean delete(long id) {
        return map.remove(id) != null;
    }

    @Override
    public MemoryEntry get(long id) {
        MemoryEntry e = map.get(id);
        return e == null ? null : copy(e);
    }

    @Override
    public List<MemoryEntry> searchCandidates(String query, MemoryCategory cat, int limit) {
        Set<String> q = TextUtil.tokens(query);
        long now = System.currentTimeMillis();
        List<MemoryEntry> out = new ArrayList<>();
        for (MemoryEntry e : map.values()) {
            if (e.isExpired(now)) continue;
            if (cat != null && e.category != cat) continue;
            Set<String> t = TextUtil.tokens(e.content + " " + e.tagsCsv());
            boolean hit = q.isEmpty();
            for (String s : q) if (t.contains(s)) hit = true;
            if (hit) out.add(copy(e));
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public List<MemoryEntry> all(MemoryCategory cat, int limit) {
        List<MemoryEntry> out = new ArrayList<>();
        for (MemoryEntry e : map.values()) if (cat == null || e.category == cat) out.add(copy(e));
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    @Override
    public void markUsed(List<Long> idList, long when) {
        for (Long id : idList) {
            MemoryEntry e = map.get(id);
            if (e != null) {
                e.useCount++;
                e.lastUsedAt = when;
            }
        }
    }

    @Override
    public int count() {
        return map.size();
    }

    private static MemoryEntry copy(MemoryEntry e) {
        return MemoryEntry.fromJson(e.toJson());
    }
}
