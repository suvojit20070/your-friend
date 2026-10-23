package com.suvo.agent.core.memory;

import java.util.Locale;

/** Categories of durable memory. */
public enum MemoryCategory {
    PREFERENCE,
    FACT,
    PROJECT,
    DECISION,
    INSTRUCTION,
    CHARACTER,
    TASK_SUMMARY,
    CONVERSATION_SUMMARY,
    WORKFLOW,
    /** A lesson the agent recorded from its own mistake. */
    LESSON;

    public static MemoryCategory parse(String s, MemoryCategory fallback) {
        if (s == null) return fallback;
        String k = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (k.endsWith("S") && !k.equals("PROGRESS")) {
            try {
                return valueOf(k.substring(0, k.length() - 1));
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            return valueOf(k);
        } catch (IllegalArgumentException e) {
            switch (k) {
                case "PREF":
                case "LIKE":
                    return PREFERENCE;
                case "INFO":
                case "PERSONAL":
                    return FACT;
                case "RULE":
                    return INSTRUCTION;
                case "MISTAKE":
                    return LESSON;
                default:
                    return fallback;
            }
        }
    }
}
