package com.suvo.agent.autoreply;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Your Friend — Original project by Suvo.
 *
 * Reads a chat notification: who wrote, which chat, group or not, the messages it carries
 * (MessagingStyle thread with senders; the owner's own lines become "Me"), the newest message,
 * the inline reply action, and a raw dump of the rest for context.
 */
public final class NotifParser {

    public static final class Msg {
        public final String sender;
        public final String text;
        public final boolean self;
        public final long time;

        Msg(String sender, String text, boolean self, long time) {
            this.sender = sender;
            this.text = text;
            this.self = self;
            this.time = time;
        }

        public String line() {
            return (self ? "Me" : (sender.isEmpty() ? "?" : sender)) + ": " + text;
        }
    }

    public static final class Parsed {
        public String pkg = "";
        public String title = "";
        public String text = "";
        public String chat = "";
        public boolean group;
        public String selfName = "";
        public final List<Msg> thread = new ArrayList<>();
        public Msg latest;
        public Notification.Action replyAction;
        public RemoteInput replyInput;
        public String raw = "";

        /** The sender of the newest message (not the owner). */
        public String sender() {
            return latest == null || latest.sender.isEmpty() ? title : latest.sender;
        }

        /** Which chat this is: conversation title, else the notification title. */
        public String chatName() {
            return chat.isEmpty() ? title : chat;
        }
    }

    private NotifParser() {
    }

    public static Parsed parse(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras == null ? new Bundle() : n.extras;
        Parsed p = new Parsed();
        p.pkg = sbn.getPackageName();
        p.title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        p.text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (p.text.isEmpty()) p.text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        p.chat = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        p.group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        p.selfName = str(x.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME));
        if (p.selfName.isEmpty() && Build.VERSION.SDK_INT >= 28) {
            Person me = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
            if (me != null) p.selfName = str(me.getName());
        }

        // MessagingStyle thread.
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null) {
            for (Parcelable o : msgs) {
                if (!(o instanceof Bundle)) continue;
                Bundle b = (Bundle) o;
                String text = str(b.getCharSequence("text"));
                if (text.isEmpty()) continue;
                String sender = str(b.getCharSequence("sender"));
                if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                    Person who = b.getParcelable("sender_person");
                    if (who != null) sender = str(who.getName());
                }
                boolean self = sender.isEmpty() || isSelf(sender, p.selfName);
                p.thread.add(new Msg(self ? "" : sender, text, self, b.getLong("time", 0)));
            }
        }
        if (!p.thread.isEmpty()) {
            p.latest = p.thread.get(p.thread.size() - 1);
        } else if (!p.text.isEmpty()) {
            // No thread: "Name: text" in group summaries, else the title is the sender.
            String sender = p.title, text = p.text;
            int c = text.indexOf(": ");
            if (p.group && c > 0 && c < 40) {
                sender = text.substring(0, c);
                text = text.substring(c + 2);
            }
            p.latest = new Msg(sender, text, isSelf(sender, p.selfName), n.when);
        }
        if (!p.group && !p.chat.isEmpty() && !p.chat.equals(p.title) && senders(p) > 1) p.group = true;

        findReply(n, p);
        p.raw = raw(sbn, x);
        return p;
    }

    private static int senders(Parsed p) {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        for (Msg m : p.thread) if (!m.self) s.add(m.sender);
        return s.size();
    }

    static boolean isSelf(String sender, String selfName) {
        String s = sender.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("you") || s.equals("me") || s.equals("আপনি") || s.equals("आप") || s.equals("あなた")) return true;
        return !selfName.isEmpty() && s.equals(selfName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** The inline reply action (a RemoteInput that takes free text); prefers the semantic REPLY action. */
    private static void findReply(Notification n, Parsed p) {
        List<Notification.Action> all = new ArrayList<>();
        if (n.actions != null) for (Notification.Action a : n.actions) all.add(a);
        try {
            all.addAll(new Notification.WearableExtender(n).getActions());
        } catch (RuntimeException ignored) {
        }
        Notification.Action best = null;
        RemoteInput bestInput = null;
        for (Notification.Action a : all) {
            if (a == null || a.getRemoteInputs() == null || a.actionIntent == null) continue;
            for (RemoteInput ri : a.getRemoteInputs()) {
                if (!ri.getAllowFreeFormInput()) continue;
                boolean semantic = Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY;
                if (best == null || semantic) {
                    best = a;
                    bestInput = ri;
                }
                break;
            }
            if (best != null && Build.VERSION.SDK_INT >= 28 && best.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) break;
        }
        p.replyAction = best;
        p.replyInput = bestInput;
    }

    private static String raw(StatusBarNotification sbn, Bundle x) {
        Notification n = sbn.getNotification();
        StringBuilder sb = new StringBuilder();
        sb.append("package=").append(sbn.getPackageName()).append(" id=").append(sbn.getId())
                .append(" tag=").append(sbn.getTag()).append(" category=").append(n.category)
                .append(" channel=").append(Build.VERSION.SDK_INT >= 26 ? n.getChannelId() : "")
                .append(" group=").append(n.getGroup());
        if (n.contentIntent != null) sb.append(" contentIntent=").append(n.contentIntent);
        for (String k : x.keySet()) {
            if (k.equals(Notification.EXTRA_MESSAGES) || k.equals("android.messages.historic")) continue;
            Object v;
            try {
                v = x.get(k);
            } catch (RuntimeException e) {
                continue;
            }
            if (v == null || v instanceof Bitmap || v instanceof Icon || v instanceof Parcelable[] && k.contains("picture")) continue;
            String s = String.valueOf(v);
            if (s.isEmpty() || s.startsWith("android.graphics")) continue;
            sb.append(' ').append(k.replace("android.", "")).append('=').append(s.length() > 300 ? s.substring(0, 300) + "…" : s);
            if (sb.length() > 3500) break;
        }
        return sb.length() > 3500 ? sb.substring(0, 3500) : sb.toString();
    }

    static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }
}
