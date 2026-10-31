package com.suvo.agent.core.autoreply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your Friend — Original project by Suvo.
 *
 * Auto reply: what the model is told about one incoming chat notification, and how its answer is
 * read. The model decides whether to answer at all; it answers inside {@code <reply>…</reply>}
 * (empty = no reply). Anything else is never sent ("fail closed").
 *
 * Pure Java (unit-tested).
 */
public final class ReplyPrompt {

    private ReplyPrompt() {
    }

    /** Everything known about one message, filled by the listener. */
    public static final class Input {
        public String agentName = "";
        public String personality = "";
        public String ownerName = "";
        public String ownerInstructions = "";
        public String appLabel = "";
        public String pkg = "";
        public String appContext = "";
        public int maxWords = 40;

        public String sender = "";
        public String chat = "";
        public boolean group;
        public String clock = "";
        /** New messages since the last one handled in this chat (oldest first), each "Name: text". */
        public List<String> latest = new ArrayList<>();
        /** The thread the notification carries (oldest first), "Name: text" / "Me: text". */
        public List<String> thread = new ArrayList<>();
        /** Saved history of this chat (oldest first), "Name: text" / "Me: text". */
        public List<String> history = new ArrayList<>();
        /** Facts from the agent's memory that may matter here. */
        public List<String> memories = new ArrayList<>();
        public String raw = "";
    }

    public static String system(Input in) {
        StringBuilder sb = new StringBuilder();
        String owner = in.ownerName.trim().isEmpty() ? "the phone's owner" : in.ownerName.trim();
        sb.append("You are ").append(in.agentName.trim().isEmpty() ? "an AI assistant" : in.agentName.trim())
                .append(", the personal AI agent of ").append(owner)
                .append(". You answer chat messages that arrive as notifications, on ").append(owner).append("'s behalf.\n");
        if (!in.personality.trim().isEmpty()) sb.append("PERSONA: ").append(in.personality.trim()).append('\n');
        if (!in.ownerInstructions.trim().isEmpty()) {
            sb.append("HOW ").append(owner.toUpperCase(Locale.ROOT)).append(" WANTS YOU TO TALK (follow this first):\n")
                    .append(in.ownerInstructions.trim()).append('\n');
        }
        sb.append("APP CONTEXT (").append(in.appLabel.isEmpty() ? in.pkg : in.appLabel + ", " + in.pkg).append("): ")
                .append(in.appContext.trim().isEmpty() ? "a messaging app" : in.appContext.trim()).append('\n');
        sb.append("DECIDE first whether this message needs an answer at all. Do NOT reply (empty reply) when:\n"
                + "- it is a group chat and the message is not addressed to ").append(owner).append(" (or the app context says so);\n"
                + "- it is automated: OTP, bank, payment, delivery, order, promotion, newsletter, system or app message;\n"
                + "- it only closes the talk (ok, 👍, bye, gn) or is a reaction, sticker or media without text, and nothing is asked;\n"
                + "- you would have to decide something only ").append(owner).append(" can decide (money, meeting, promise) and a short holding answer is not useful;\n"
                + "- the chat history shows you already answered the same thing.\n");
        sb.append("When you DO reply:\n"
                + "- Write in the same language and script as the sender (Hinglish stays Hinglish, Bangla stays Bangla).\n"
                + "- At most ").append(Math.max(5, in.maxWords)).append(" words, natural chat style, no markdown, no signature.\n"
                + "- Never share OTPs, passwords, codes, payment or bank details, addresses or private information, and never make "
                + "promises or commitments for ").append(owner).append(".\n"
                + "- Never pretend to be ").append(owner).append(" in a way that misleads; if someone asks whether this is a bot or AI, "
                + "say honestly that you are ").append(owner).append("'s AI assistant.\n"
                + "- Use the earlier messages so you do not repeat yourself.\n");
        sb.append("OUTPUT FORMAT (nothing else): <reply>your message</reply> — or <reply></reply> when no reply is needed. "
                + "You may put one short reason before it as <why>…</why>.");
        return sb.toString();
    }

    public static String user(Input in) {
        StringBuilder sb = new StringBuilder();
        sb.append("APP: ").append(in.appLabel.isEmpty() ? in.pkg : in.appLabel).append('\n');
        sb.append("FROM: ").append(in.sender.isEmpty() ? "?" : in.sender).append('\n');
        sb.append("CHAT: ").append(in.chat.isEmpty() ? in.sender : in.chat).append(in.group ? " (group)" : " (direct)").append('\n');
        if (!in.clock.isEmpty()) sb.append("TIME NOW: ").append(in.clock).append('\n');
        sb.append("NEW MESSAGE").append(in.latest.size() > 1 ? "S" : "").append(":\n");
        for (String l : in.latest) sb.append("  ").append(cut(l, 1200)).append('\n');
        if (!in.thread.isEmpty()) {
            sb.append("THREAD IN THE NOTIFICATION (oldest first):\n");
            for (String l : in.thread) sb.append("  ").append(cut(l, 500)).append('\n');
        }
        if (!in.history.isEmpty()) {
            sb.append("EARLIER IN THIS CHAT (saved, oldest first; \"Me\" = you / ").append(in.ownerName.isEmpty() ? "the owner" : in.ownerName).append("):\n");
            for (String l : in.history) sb.append("  ").append(cut(l, 500)).append('\n');
        }
        if (!in.memories.isEmpty()) {
            sb.append("WHAT YOU REMEMBER THAT MAY MATTER:\n");
            for (String m : in.memories) sb.append("  - ").append(cut(m, 300)).append('\n');
        }
        if (!in.raw.isEmpty()) sb.append("RAW NOTIFICATION DATA: ").append(cut(in.raw, 2500)).append('\n');
        sb.append("Answer now in the <reply></reply> format.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ reading the answer

    public enum Kind {REPLY, SKIP, BAD_FORMAT, TOO_LONG}

    public static final class Result {
        public final Kind kind;
        public final String text;
        public final String why;

        Result(Kind kind, String text, String why) {
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.why = why == null ? "" : why;
        }
    }

    private static final Pattern REPLY = Pattern.compile("<reply>(.*?)</reply>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WHY = Pattern.compile("<why>(.*?)</why>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SKIP_WORD = Pattern.compile("^[\\[(<{]?\\s*(s\\W*k\\W*i\\W*p\\W*(ped)?|no\\W*reply|none|null|ignore|n/?a|empty|nothing)\\s*[\\])>}]?\\.?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile("^[\\[(][^\\])]{0,40}[\\])]$");

    public static Result parse(String raw, int maxWords) {
        if (raw == null) return new Result(Kind.BAD_FORMAT, "", "");
        String s = THINK.matcher(raw).replaceAll("");
        Matcher w = WHY.matcher(s);
        String why = w.find() ? w.group(1).trim() : "";
        Matcher m = REPLY.matcher(s);
        if (!m.find()) return new Result(Kind.BAD_FORMAT, "", why);
        String t = m.group(1).trim();
        // Quotes the model sometimes wraps around the message.
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("“") && t.endsWith("”")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.isEmpty() || SKIP_WORD.matcher(t).matches() || CONTROL.matcher(t).matches()) return new Result(Kind.SKIP, "", why);
        if (words(t) > Math.max(5, maxWords) * 3) return new Result(Kind.TOO_LONG, t, why);
        return new Result(Kind.REPLY, t, why);
    }

    static int words(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    // ------------------------------------------------------------------ filters before any AI call

    private static final Pattern NO_CONTENT = Pattern.compile(
            "^(.{0,60}\\s)?(sent|shared) (you )?(a|an|\\d+) ?(new )?(message|messages|attachment|photo|photos|video|videos|reel|post|story|voice message|audio|sticker|gif|file|document|location|contact)s?\\.?$"
                    + "|^(.{0,60}\\s)?(reacted|liked|loved) .{0,40}(to )?(your |a )?(message|photo|story|reel|post|comment)\\.?$"
                    + "|^\\d+ new messages?.*$|^new messages?$|^(missed|incoming|ongoing) (voice |video )?call.*$"
                    + "|^(📷|🎥|🎤|🎵|📎|📄|📍|👤|🖼️?|🎞️?)?\\s*(photo|video|voice message|audio|sticker|gif|document|file|location|contact|image|album)(\\s*\\(\\d+\\))?$"
                    + "|^this message was deleted\\.?$|^waiting for this message.*$",
            Pattern.CASE_INSENSITIVE);

    /** True for messages with nothing to answer: media only, reactions, "sent a message", call notices… */
    public static boolean noContent(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;
        // Prefixed by "(chat): " in some apps.
        t = t.replaceFirst("^\\([^)]{1,60}\\):\\s*", "");
        return NO_CONTENT.matcher(t).matches();
    }

    /** The first blocklist word found in any of {@code texts}, or null. Words are comma separated. */
    public static String blocked(String blocklist, String... texts) {
        if (blocklist == null || blocklist.trim().isEmpty()) return null;
        StringBuilder all = new StringBuilder();
        for (String t : texts) if (t != null) all.append(' ').append(t.toLowerCase(Locale.ROOT));
        String hay = all.toString();
        for (String w : blocklist.split(",")) {
            String k = w.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && hay.contains(k)) return k;
        }
        return null;
    }

    static String cut(String s, int n) {
        String t = s == null ? "" : s.trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
