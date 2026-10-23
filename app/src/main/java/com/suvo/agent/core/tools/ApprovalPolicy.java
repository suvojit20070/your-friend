package com.suvo.agent.core.tools;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Decides whether a call may run, needs the user's approval, or is blocked. This runs outside the
 * model: nothing in a prompt, skill or web page can change it.
 */
public final class ApprovalPolicy {

    public enum AutonomyMode {
        /** The agent explains and suggests; only read-only tools run. */
        ASSIST,
        /** Asks before anything beyond low-risk actions. */
        CONFIRM,
        /** Runs permitted actions up to MEDIUM risk; HIGH risk always asks. */
        AUTONOMOUS
    }

    public enum Decision {
        ALLOW, ASK, BLOCK
    }

    public AutonomyMode mode = AutonomyMode.CONFIRM;
    /** Global switch from Privacy settings: when false every non-read-only call asks. */
    public boolean autoExecutionEnabled = true;
    /** Tools the user marked "always ask". */
    public final Set<String> alwaysAsk = Collections.synchronizedSet(new HashSet<>());
    /** Tools the user marked "never allow". */
    public final Set<String> neverAllow = Collections.synchronizedSet(new HashSet<>());

    public Decision decide(String toolName, RiskLevel risk) {
        if (neverAllow.contains(toolName)) return Decision.BLOCK;
        if (risk == RiskLevel.READ_ONLY) {
            return alwaysAsk.contains(toolName) ? Decision.ASK : Decision.ALLOW;
        }
        if (mode == AutonomyMode.ASSIST) return Decision.BLOCK;
        if (risk == RiskLevel.HIGH) return Decision.ASK;
        if (alwaysAsk.contains(toolName) || !autoExecutionEnabled) return Decision.ASK;
        switch (mode) {
            case CONFIRM:
                return risk == RiskLevel.LOW ? Decision.ALLOW : Decision.ASK;
            case AUTONOMOUS:
                return Decision.ALLOW;
            default:
                return Decision.ASK;
        }
    }
}
