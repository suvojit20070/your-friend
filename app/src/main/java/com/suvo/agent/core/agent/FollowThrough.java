package com.suvo.agent.core.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Catches the "I'm doing it now…" reply that ends a turn without doing anything. Models sometimes
 * announce an action ("I'll create the project", "bana raha hoon", "করছি") and stop without calling
 * a tool, so the task silently ends. When the final reply reads like such a promise (and not like a
 * question to the user or a finished report), the loop sends one short reminder and continues.
 * The reminder is app text, clearly marked, never presented as the user's words.
 */
public final class FollowThrough {

    /** Marker prefix of the reminder message (the chat shows it as a small notice). */
    public static final String MARKER = "[App check]";

    public static final String REMINDER = MARKER + " Your last reply announced work but called no tool, so nothing happened yet. "
            + "Do it now: call the tools needed for the next step (shell / execute_* / file_write / ...), check the result, and "
            + "continue until the task is done. If you are truly blocked, say exactly what blocks you and what the user can do — "
            + "do not just say you are working on it. Before saying something is impossible, check the device "
            + "(e.g. shell: which <tool>, pkg install -y <package> in Termux) and the installed skills.";

    private static final Pattern PROMISE = Pattern.compile(
            "(\\bi('ll| will| am going to|'m going to| am now|'m now)\\b"
                    + "|\\blet me\\b|\\bworking on it\\b|\\bi'?m (doing|creating|building|starting|working|writing|setting up|installing|checking)\\b"
                    + "|\\bi am (doing|creating|building|starting|working|writing|setting up|installing|checking)\\b"
                    + "|\\b(give me|wait) a (moment|minute|second|sec)\\b|\\bplease wait\\b|\\bstarting now\\b|\\bon it\\b"
                    // Hinglish / Hindi (Latin)
                    + "|kar raha|kar rahi|kar rhi|kar rha|karta hu|karti hu|karunga|karungi|kar deta|kar deti|bana raha|bana rahi|"
                    + "banata hu|banati hu|banaunga|banaungi|shuru kar|abhi karta|abhi karti|ruko|ek minute|wait karo"
                    // Bengali (Latin)
                    + "|korchi|korbo|banacchi|banachhi|baniye dichhi|shuru korchi|ekhuni korchi"
                    + ")");
    private static final Pattern PROMISE_NATIVE = Pattern.compile(
            "(कर रहा|कर रही|करता हूँ|करती हूँ|करूँगा|करूंगा|करूँगी|बना रहा|बना रही|शुरू कर|करছি|করছি|করব|বানাচ্ছি|শুরু করছি|এখনই)");
    private static final Pattern DONE = Pattern.compile(
            "(\\b(done|finished|completed|all set|here is|here's|saved to|installed|created)\\b|ho gaya|ho gya|bana diya|kar diya|"
                    + "हो गया|बना दिया|कर दिया|হয়ে গেছে|করে দিয়েছি|বানিয়ে দিয়েছি|hoye geche|kore diyechi)");

    public static final String CHECK_FIRST = MARKER + " You told the user you cannot do this, but in this task you have not "
            + "checked the device yet. Do not refuse without trying. Now: (1) load the matching skill if one is listed (for "
            + "Android apps: load_skill android-app-builder), (2) use the shell tool (Termux backend) to see what is installed "
            + "(which javac aapt2 d8 apksigner python clang) and install what is missing with pkg install -y, (3) continue the "
            + "task. Only if a real tool call fails and cannot be fixed, report that exact error and what the user can do.";

    private static final Pattern REFUSAL = Pattern.compile(
            "(\\bi (can ?not|can't|am unable to|am not able to|don't have (the )?(ability|tools|access|environment))\\b"
                    + "|\\b(my|this) environment (does not|doesn't) (include|have|support)\\b|\\bnot possible (for me|here)\\b"
                    + "|\\bi'?m (unable|not able) to\\b|\\bbeyond my (capabilities|abilities)\\b"
                    + "|nahi kar sakta|nahi kar sakti|nhi kar skta|nahi bana sakta|nahi bana sakti|mere paas .{0,30}nahi hai"
                    + "|korte parbo na|banate parbo na|amar kache .{0,30}nei"
                    + "|नहीं कर सकता|नहीं कर सकती|नहीं बना सकता|করতে পারব না|বানাতে পারব না)");

    private FollowThrough() {
    }

    /**
     * True when {@code reply} declines the task as impossible although the agent has not used any
     * device-checking tool (shell, code runners, skills, device_admin) in this task.
     */
    public static boolean isUncheckedRefusal(String reply, TaskState task) {
        if (reply == null || reply.trim().isEmpty()) return false;
        String low = reply.toLowerCase(Locale.ROOT);
        if (!REFUSAL.matcher(low).find() && !REFUSAL.matcher(reply).find()) return false;
        if (task != null) {
            for (TaskState.Step st : task.steps()) {
                String t = st.tool == null ? "" : st.tool;
                if (t.equals("shell") || t.startsWith("execute_") || t.equals("load_skill") || t.equals("device_admin")
                        || t.equals("ssh") || t.equals("delegate_task")) {
                    return false; // it did check; the refusal is based on a real result
                }
            }
        }
        return true;
    }

    /** True when {@code reply} announces work but neither asks the user nor reports a result. */
    public static boolean isEmptyPromise(String reply) {
        if (reply == null) return false;
        String t = reply.trim();
        if (t.isEmpty() || t.length() > 900) return false; // long answers are real content
        if (t.endsWith("?") || t.endsWith("？")) return false; // asking the user is legitimate
        String tail = t.length() > 320 ? t.substring(t.length() - 320) : t;
        String low = tail.toLowerCase(Locale.ROOT);
        if (DONE.matcher(low).find()) return false;
        return PROMISE.matcher(low).find() || PROMISE_NATIVE.matcher(tail).find();
    }
}
