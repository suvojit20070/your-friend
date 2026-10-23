package com.suvo.agent.core.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps credentials and highly sensitive identifiers out of ordinary memory. Applied to every
 * memory write (manual, tool-driven and automatic extraction).
 */
public final class SecretFilter {

    private SecretFilter() {
    }

    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),                 // OpenAI-style keys
            Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}"),                // Google API keys
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}"),            // GitHub tokens
            Pattern.compile("\\bxox[abpr]-[A-Za-z0-9-]{10,}"),           // Slack tokens
            Pattern.compile("\\b\\d{6,12}:[A-Za-z0-9_-]{30,}"),          // Telegram bot tokens
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), // JWT
            Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"),                // card-like numbers
            Pattern.compile("(?i)\\b(password|passwd|pwd|passcode|pin|otp|cvv|api[_ ]?key|secret|token)\\s*(is|=|:)\\s*\\S+"),
            Pattern.compile("(?i)\\b(पासवर्ड|パスワード|পাসওয়ার্ড)\\s*\\S+"),
    };

    /** Returns a reason when the text must not be stored, otherwise null. */
    public static String rejectReason(String text) {
        if (text == null) return null;
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(text).find()) return "looks like a credential or sensitive number";
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("my password") || low.contains("mera password") || low.contains("seed phrase") || low.contains("recovery phrase")) {
            return "mentions a password or recovery phrase";
        }
        return null;
    }

    /** Redacts secrets in free text (for logs and summaries). */
    public static String redact(String text) {
        if (text == null) return "";
        String out = text;
        for (Pattern p : SECRET_PATTERNS) out = p.matcher(out).replaceAll("[redacted]");
        return out;
    }
}
