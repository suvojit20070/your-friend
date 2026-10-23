package com.suvo.agent.core.tools;

/** How consequential a specific tool invocation is. Used by {@link ApprovalPolicy}. */
public enum RiskLevel {
    /** Reads information only (list apps, read a file, search memory). */
    READ_ONLY,
    /** Small reversible effects (post a notification, open an app, write in the private workspace). */
    LOW,
    /** Acts on other apps or the device (tap, type, run code, edit files outside the workspace). */
    MEDIUM,
    /** Destructive, external communication, installs, purchases, security-sensitive changes. */
    HIGH
}
