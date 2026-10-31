package com.suvo.agent.autoreply;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.suvo.agent.AppGraph;
import com.suvo.agent.agent.RoleModels;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.core.autoreply.ReplyPrompt;
import com.suvo.agent.core.llm.ChatMessage;
import com.suvo.agent.core.llm.ChatRequest;
import com.suvo.agent.core.llm.ChatResponse;
import com.suvo.agent.core.llm.LlmClient;
import com.suvo.agent.core.llm.StreamListener;
import com.suvo.agent.core.memory.MemoryEntry;
import com.suvo.agent.core.memory.MemoryRanker;
import com.suvo.agent.core.util.CancellationToken;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Your Friend — Original project by Suvo.
 *
 * Auto reply engine. Android tells this service about every notification (once the user allows
 * notification access). For a chat notification from an app the user picked, with an inline reply
 * box, it:
 * <ol>
 *   <li>filters: master switch, app rule not Off, not a group summary, has a reply action, newest
 *       message not the owner's own, not media / reaction only, no blocklist word, not handled before;</li>
 *   <li>waits a moment so a burst of messages in one chat is answered once;</li>
 *   <li>asks the reply model (Settings: auto reply model) with the agent's persona, the owner's
 *       instructions, the app's context key, the notification thread, the saved chat history, memories
 *       and the raw notification — the model decides to reply or skip;</li>
 *   <li>Auto: sends through the notification's own reply box after a short human-like delay.
 *       Draft: shows a notification with Send / Edit / Discard.</li>
 * </ol>
 * Every outcome is logged for the Auto reply screen.
 */
public final class ReplyListener extends NotificationListenerService {

    private static final long DEBOUNCE_MS = 2_500, COOLDOWN_MS = 6_000, SEEN_MS = 15 * 60_000L;

    private HandlerThread thread;
    private Handler handler;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yf-autoreply");
        t.setDaemon(true);
        return t;
    });

    /** Latest notification per chat, waiting for the debounce. */
    private final Map<String, NotifParser.Parsed> pending = new HashMap<>();
    private final Map<String, Runnable> timers = new HashMap<>();
    /** Fingerprints of messages already handled (chat + sender + text) → time. */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    /** Last text answered per chat, to find which messages are new. */
    private final Map<String, String> lastHandled = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> lastSent = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("yf-autoreply-timer");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (thread != null) thread.quitSafely();
        work.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        connected = true;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        try {
            requestRebind(new ComponentName(this, ReplyListener.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (RuntimeException ignored) {
            // A strange notification must never crash the listener.
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
        AppGraph g = AppGraph.get();
        if (!g.settings.bool(AutoReplyStore.ENABLED, false)) return;
        AutoReplyStore store = AutoReplyStore.get(this);
        AutoReplyStore.Rule rule = store.rule(sbn.getPackageName());
        if (rule == null || AutoReplyStore.OFF.equals(rule.mode)) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        NotifParser.Parsed p = NotifParser.parse(sbn);
        if (p.replyAction == null || p.replyInput == null) return;
        if (p.latest == null || p.latest.self || p.latest.text.isEmpty()) return;

        String key = AutoReplyStore.chatKey(p.pkg, p.chatName());
        String fp = key + "\n" + p.latest.sender + "\n" + p.latest.text;
        synchronized (seen) {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                if (now - it.next().getValue() > SEEN_MS) it.remove();
                else break;
            }
            if (seen.containsKey(fp)) return;
            seen.put(fp, now);
        }

        String app = rule.label.isEmpty() ? p.pkg : rule.label;
        if (ReplyPrompt.noContent(p.latest.text)) {
            log(store, p, app, AutoReplyStore.S_IGNORED, "", "No text to answer (media, reaction or notice).");
            return;
        }
        String bad = ReplyPrompt.blocked(g.settings.str(AutoReplyStore.BLOCKLIST, AutoReplyStore.DEFAULT_BLOCKLIST),
                p.latest.text, p.title, p.chat, p.sender());
        if (bad != null) {
            log(store, p, app, AutoReplyStore.S_BLOCKED, "", "Blocklist word: " + bad);
            return;
        }

        // Debounce per chat: answer a burst once, with the newest notification.
        synchronized (pending) {
            pending.put(key, p);
            Runnable old = timers.remove(key);
            if (old != null) handler.removeCallbacks(old);
            long wait = DEBOUNCE_MS;
            Long sent = lastSent.get(key);
            if (sent != null) wait = Math.max(wait, sent + COOLDOWN_MS - System.currentTimeMillis());
            Runnable r = () -> {
                NotifParser.Parsed latest;
                synchronized (pending) {
                    timers.remove(key);
                    latest = pending.remove(key);
                }
                if (latest != null) work.execute(() -> process(key, latest));
            };
            timers.put(key, r);
            handler.postDelayed(r, wait);
        }
    }

    // ------------------------------------------------------------------ deciding and replying

    private void process(String key, NotifParser.Parsed p) {
        AppGraph g = AppGraph.get();
        AutoReplyStore store = AutoReplyStore.get(this);
        AutoReplyStore.Rule rule = store.rule(p.pkg);
        if (rule == null || AutoReplyStore.OFF.equals(rule.mode) || !g.settings.bool(AutoReplyStore.ENABLED, false)) return;
        String app = rule.label.isEmpty() ? p.pkg : rule.label;

        // New messages since the last one handled in this chat.
        List<NotifParser.Msg> fresh = new ArrayList<>();
        String last = lastHandled.get(key);
        if (!p.thread.isEmpty()) {
            int from = 0;
            if (last != null) {
                for (int i = p.thread.size() - 1; i >= 0; i--) {
                    if (p.thread.get(i).text.equals(last)) {
                        from = i + 1;
                        break;
                    }
                }
            }
            for (int i = from; i < p.thread.size(); i++) if (!p.thread.get(i).self) fresh.add(p.thread.get(i));
            // Keep only messages after the owner's last line.
            for (int i = p.thread.size() - 1; i >= from; i--) {
                if (p.thread.get(i).self) {
                    fresh.clear();
                    for (int j = i + 1; j < p.thread.size(); j++) if (!p.thread.get(j).self) fresh.add(p.thread.get(j));
                    break;
                }
            }
        }
        if (fresh.isEmpty()) fresh.add(p.latest);
        if (fresh.size() > 8) fresh = fresh.subList(fresh.size() - 8, fresh.size());
        lastHandled.put(key, p.latest.text);

        ReplyPrompt.Input in = new ReplyPrompt.Input();
        CharacterSpec spec = g.characters.active();
        if (spec != null) {
            in.agentName = spec.name;
            in.personality = spec.personality;
        }
        in.ownerName = g.settings.str(AutoReplyStore.OWNER, "");
        in.ownerInstructions = g.settings.str(AutoReplyStore.INSTRUCTIONS, "");
        in.appLabel = rule.label;
        in.pkg = p.pkg;
        in.appContext = rule.context;
        in.maxWords = g.settings.integer(AutoReplyStore.MAX_WORDS, 40);
        in.sender = p.sender();
        in.chat = p.chatName();
        in.group = p.group;
        Calendar c = Calendar.getInstance();
        in.clock = String.format(Locale.ROOT, "%1$tA %1$tH:%1$tM", c);
        List<String> newLines = new ArrayList<>();
        for (NotifParser.Msg m : fresh) newLines.add(m.line());
        in.latest = newLines;
        for (NotifParser.Msg m : p.thread) in.thread.add(m.line());
        if (in.thread.size() > 12) in.thread = in.thread.subList(in.thread.size() - 12, in.thread.size());
        in.history = store.history(key);
        if (g.settings.bool(AutoReplyStore.USE_MEMORY, true)) in.memories = memories(g, in.sender + " " + p.latest.text);
        in.raw = p.raw;

        String message = joinTexts(fresh);
        LlmClient llm = RoleModels.client(g, RoleModels.REPLY);
        if (llm == null) {
            log(store, p, app, AutoReplyStore.S_NO_PROVIDER, "", "No AI provider is set up (Settings → Providers).", message);
            return;
        }
        ChatRequest req = new ChatRequest();
        req.forceNoStream = true;
        req.tools = new JSONArray();
        req.toolChoice = null;
        req.temperatureOverride = 0.8;
        req.maxTokensOverride = 1200;
        req.messages.add(ChatMessage.system(ReplyPrompt.system(in)));
        req.messages.add(ChatMessage.user(ReplyPrompt.user(in)));

        ReplyPrompt.Result res;
        String raw;
        try {
            ChatResponse r = llm.chat(req, StreamListener.NONE, new CancellationToken());
            raw = r == null ? null : r.content;
            res = ReplyPrompt.parse(raw, in.maxWords);
        } catch (Exception e) {
            log(store, p, app, AutoReplyStore.S_ERROR, "", "AI request failed: " + e.getMessage(), message);
            return;
        }
        store.remember(key, newLines.toArray(new String[0]));

        switch (res.kind) {
            case SKIP:
                log(store, p, app, AutoReplyStore.S_SKIPPED, "", res.why.isEmpty() ? "The agent chose not to reply." : res.why, message);
                return;
            case BAD_FORMAT:
                log(store, p, app, AutoReplyStore.S_BAD, "", "The model did not answer in <reply> format:\n" + cut(raw, 600), message);
                return;
            case TOO_LONG:
                log(store, p, app, AutoReplyStore.S_LONG, res.text, "Longer than allowed; not sent.", message);
                return;
            default:
                break;
        }

        if (AutoReplyStore.DRAFT.equals(rule.mode)) {
            AutoReplyStore.Entry e = log(store, p, app, AutoReplyStore.S_DRAFT, res.text, res.why, message);
            Drafts.post(this, e.id, key, app, p.sender(), message, res.text, p.replyAction, p.replyInput);
            return;
        }
        // Auto: a short, human-like pause, then send.
        long delay = 2_000 + (long) (Math.random() * 3_000);
        String text = res.text, why = res.why;
        handler.postDelayed(() -> work.execute(() -> {
            String err = send(this, p.replyAction, p.replyInput, text);
            if (err == null) {
                lastSent.put(key, System.currentTimeMillis());
                store.remember(key, "Me: " + text);
                log(store, p, app, AutoReplyStore.S_SENT, text, why, message);
            } else {
                log(store, p, app, AutoReplyStore.S_ERROR, text, err, message);
            }
        }), delay);
    }

    private static List<String> memories(AppGraph g, String query) {
        List<String> out = new ArrayList<>();
        try {
            List<MemoryEntry> cands = g.memory.searchCandidates(query, null, 40);
            MemoryRanker ranker = new MemoryRanker();
            ranker.minScore = 0.12;
            for (MemoryRanker.Scored s : ranker.rank(query, cands, 4, System.currentTimeMillis())) out.add(s.entry.content);
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** Sends {@code text} through a notification's inline reply box. Null on success, else the problem. */
    public static String send(Context ctx, Notification.Action action, RemoteInput input, String text) {
        try {
            Intent intent = new Intent();
            Bundle results = new Bundle();
            results.putCharSequence(input.getResultKey(), text);
            RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, results);
            action.actionIntent.send(ctx, 0, intent);
            return null;
        } catch (PendingIntent.CanceledException e) {
            return "The chat's reply box expired (the notification was dismissed or the app restarted).";
        } catch (RuntimeException e) {
            return "Could not send: " + e.getMessage();
        }
    }

    private static String joinTexts(List<NotifParser.Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (NotifParser.Msg m : msgs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.text);
        }
        return sb.toString();
    }

    private static AutoReplyStore.Entry log(AutoReplyStore store, NotifParser.Parsed p, String app, String status, String reply, String detail) {
        return log(store, p, app, status, reply, detail, p.latest == null ? p.text : p.latest.text);
    }

    private static AutoReplyStore.Entry log(AutoReplyStore store, NotifParser.Parsed p, String app, String status, String reply,
                                            String detail, String message) {
        AutoReplyStore.Entry e = new AutoReplyStore.Entry();
        e.pkg = p.pkg;
        e.app = app;
        e.sender = p.sender();
        e.chat = p.chatName();
        e.message = cut(message, 1000);
        e.reply = reply == null ? "" : reply;
        e.status = status;
        e.detail = detail == null ? "" : detail;
        return store.log(e);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
