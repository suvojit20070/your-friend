package com.suvo.agent.core.memory;

import java.util.List;

/** Persistent memory storage. Implemented with SQLite + FTS on Android, in-memory in tests. */
public interface MemoryStore {

    MemoryEntry add(MemoryEntry entry);

    boolean update(MemoryEntry entry);

    boolean delete(long id);

    MemoryEntry get(long id);

    /** Full-text / keyword candidates (unranked), excluding expired entries. */
    List<MemoryEntry> searchCandidates(String query, MemoryCategory categoryOrNull, int limit);

    List<MemoryEntry> all(MemoryCategory categoryOrNull, int limit);

    void markUsed(List<Long> ids, long when);

    int count();
}
