package com.suvo.agent.core.agent;

/**
 * Limits that bound autonomous execution. All are user-configurable in Settings, and each one can
 * be switched to "no limit" (the value becomes {@link #UNLIMITED} / {@link #UNLIMITED_MS}).
 * Loop detection stays on even without limits, so an agent that repeats the same failing call
 * is still stopped.
 */
public final class AgentConfig {
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final long UNLIMITED_MS = Long.MAX_VALUE;
    /** Longest a wake lock is held in one go when the task has no time limit (it is renewed while running). */
    public static final long UNLIMITED_WAKELOCK_MS = 12L * 60 * 60_000L;

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
    /** Times per task the loop reminds the model to act when it only announced work (0 = off). */
    public int followThroughReminders = 2;

    public boolean stepsUnlimited() {
        return maxSteps == UNLIMITED;
    }

    public boolean toolCallsUnlimited() {
        return maxToolCalls == UNLIMITED;
    }

    public boolean timeUnlimited() {
        return maxDurationMs == UNLIMITED_MS;
    }

    /** How long a wake lock for one task may be held: the task limit plus 10 minutes, never overflowing. */
    public long wakeLockMs() {
        if (timeUnlimited() || maxDurationMs > UNLIMITED_WAKELOCK_MS - 10 * 60_000L) return UNLIMITED_WAKELOCK_MS;
        return maxDurationMs + 10 * 60_000L;
    }
}
