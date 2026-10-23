package com.suvo.agent.core.agent;

/** Limits that bound autonomous execution. All are user-configurable in Settings. */
public final class AgentConfig {
    public int maxSteps = 25;
    public int maxToolCalls = 60;
    public long maxDurationMs = 15 * 60_000L;
    public int llmRetries = 3;
    public long retryBaseDelayMs = 1500;
    /** Same tool with identical arguments this many times in one task counts as a loop. */
    public int loopThreshold = 3;
    public int toolResultMaxChars = 12_000;
    /** Execute multiple tool calls from one turn (sequentially, in order). */
    public boolean allowMultipleToolCalls = true;
}
