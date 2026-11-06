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
            + "if something must finish first, call wait (seconds) and then check again; "
            + "continue until the task is done. If you are truly blocked, say exactly what blocks you and what the user can do — "
            + "do not just say you are working on it. Before saying something is impossible, check the device "
            + "(e.g. shell: which <tool>, pkg install -y <package> in Termux) and the installed skills. "
            + "If your reply was in fact a complete answer, an offer or a question for the user (no action is needed), do NOT "
            + "run any tool because of this note: just repeat your final answer briefly.";

    private static final Pattern PROMISE = Pattern.compile(
            "(\\bi('ll| will| am going to|'m going to| am now|'m now)\\b"
                    + "|\\blet me\\b|\\bworking on it\\b|\\bi'?m (doing|creating|building|starting|working|writing|setting up|installing|checking)\\b"
                    + "|\\bi am (doing|creating|building|starting|working|writing|setting up|installing|checking)\\b"
                    + "|\\b(give me|wait) a (moment|minute|second|sec)\\b|\\bplease wait\\b|\\bcheck (again|back) (in|after|later)\\b|\\bstarting now\\b|\\bon it\\b"
                    // Hinglish / Hindi (Latin)
                    + "|kar raha|kar rahi|kar rhi|kar rha|karta hu|karti hu|karta hoon|karti hoon|karta hun|check karta|dekhta hoon|dekhti hoon|"
                    + "wait karke|thoda wait|baad mein check|phir check|karunga|karungi|kar deta|kar deti|bana raha|bana rahi|"
                    + "banata hu|banati hu|banaunga|banaungi|shuru kar|abhi karta|abhi karti|ruko|ek minute|wait karo"
                    // Bengali (Latin)
                    + "|korchi|korbo|banacchi|banachhi|baniye dichhi|shuru korchi|ekhuni korchi"
                    + ")");
    private static final Pattern PROMISE_NATIVE = Pattern.compile(
            "(कर रहा|कर रही|करता हूँ|करती हूँ|करूँगा|करूंगा|करूँगी|बना रहा|बना रही|शुरू कर|करছি|করছি|করব|বানাচ্ছি|শুরু করছি|এখনই)");
    private static final Pattern DONE = Pattern.compile(
            "(\\b(done|finished|completed|all set|here is|here's|saved to|installed|created)\\b|ho gaya|ho gya|bana diya|kar diya|"
                    + "हो गया|बना दिया|कर दिया|হয়ে গেছে|করে দিয়েছি|বানিয়ে দিয়েছি|hoye geche|kore diyechi)");

    private static final Pattern OFFER = Pattern.compile(
            "(\\b(if you('d)? (want|like|prefer)|would you like|do you want|let me know|just (say|tell me)|tell me (how|what|which|if)"
            + "|happy to|i can (also|write|make|do|expand|go deeper))\\b|chaho to|chahiye to|batao|bolo to|bolo|chaile)");

    public static final String CHECK_FIRST = MARKER + " You told the user the device / your tools cannot do this, but in this "
            + "task you have not checked yet. If the task really needs a tool, check before refusing: load the matching skill if "
            + "one is listed, or use the shell tool to see what is installed (which <tool>) and install what is missing "
            + "(pkg install -y), then continue. Only check what THIS task needs. If the task does not need any tool at all "
            + "(writing, explaining, chatting, an opinion, a limit of the text itself), ignore this note completely: do not run "
            + "anything, just give your final answer again briefly.";

    private static final Pattern REFUSAL = Pattern.compile(
            "(\\bi (can ?not|can't|am unable to|am not able to) (run|execute|install|build|compile|access|open|download|control|"
                    + "create (an? )?(app|apk|file|folder|project)|make (an? )?(app|apk)|use (the )?(terminal|shell|termux|phone|device))\\b"
                    + "|\\bi (don't|do not) have (the )?(ability|tools|access|environment|a (terminal|shell|computer|device))\\b"
                    + "|\\b(my|this) environment (does not|doesn't) (include|have|support)\\b|\\bnot possible (for me|here)\\b"
                    + "|\\bi'?m (unable|not able) to (run|execute|install|build|compile|access|open|download|control)\\b"
                    + "|(install|run|build|apk|app|access) nahi kar (sakta|sakti)|(app|apk) nahi bana (sakta|sakti)"
                    + "|mere paas (terminal|shell|tool|access) nahi"
                    + "|(install|run|build|access) korte parbo na|(app|apk) banate parbo na"
                    + "|इंस्टॉल नहीं कर सकता|ऐप नहीं बना सकता|অ্যাপ বানাতে পারব না)");

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
        if (OFFER.matcher(low).find()) return false; // "if you want, I can… just tell me" waits for the user
        return PROMISE.matcher(low).find() || PROMISE_NATIVE.matcher(tail).find();
    }
}
