package com.suvo.agent.autoreply;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.suvo.agent.R;
import com.suvo.agent.ui.AutoReplyActivity;
import com.suvo.agent.ui.Notifications;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Your Friend — Original project by Suvo.
 *
 * Draft mode: the agent's reply waits for the owner. A notification shows the incoming message
 * and the draft with Send, Edit (type your own text right in the notification) and Discard; the
 * same draft can be sent from the Auto reply screen. The chat's reply box is kept in memory only
 * while the app runs, so a draft expires when the app process ends or the chat notification is
 * dismissed.
 */
public final class Drafts {

    static final String ACTION_SEND = "com.suvo.agent.autoreply.SEND";
    static final String ACTION_EDIT = "com.suvo.agent.autoreply.EDIT";
    static final String ACTION_DISCARD = "com.suvo.agent.autoreply.DISCARD";
    static final String EXTRA_LOG = "log_id";
    static final String KEY_TEXT = "draft_text";
    private static final long EXPIRE_MS = 6 * 60 * 60_000L;

    static final class Pending {
        final long logId;
        final int notifId;
        final String key, sender;
        final String text;
        final Notification.Action action;
        final RemoteInput input;
        final long time = System.currentTimeMillis();

        Pending(long logId, int notifId, String key, String sender, String text, Notification.Action action, RemoteInput input) {
            this.logId = logId;
            this.notifId = notifId;
            this.key = key;
            this.sender = sender;
            this.text = text;
            this.action = action;
            this.input = input;
        }
    }

    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();
    private static int nextId = 6000;

    private Drafts() {
    }

    public static boolean isPending(long logId) {
        expire();
        return PENDING.containsKey(logId);
    }

    static void post(Context ctx, long logId, String key, String app, String sender, String message, String draft,
                     Notification.Action action, RemoteInput input) {
        expire();
        int id;
        synchronized (Drafts.class) {
            id = nextId++;
            if (nextId > 6900) nextId = 6000;
        }
        PENDING.put(logId, new Pending(logId, id, key, sender, draft, action, input));

        Notification.Builder b = Notifications.builder(ctx, Notifications.CHANNEL_AUTO_REPLY)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(ctx.getString(R.string.ar_draft_title, sender, app))
                .setContentText(draft)
                .setStyle(new Notification.BigTextStyle().bigText(sender + ": " + cut(message, 300) + "\n\n↳ " + draft))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, id, new Intent(ctx, AutoReplyActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.ar_send), broadcast(ctx, ACTION_SEND, logId, id, false)).build());
        RemoteInput ri = new RemoteInput.Builder(KEY_TEXT).setLabel(ctx.getString(R.string.ar_edit_hint)).build();
        b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.ar_edit), broadcast(ctx, ACTION_EDIT, logId, id + 10_000, true))
                .addRemoteInput(ri).build());
        b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.ar_discard), broadcast(ctx, ACTION_DISCARD, logId, id + 20_000, false)).build());

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(id, b.build());
            } catch (SecurityException ignored) {
                // No notification permission: the draft can still be sent from the Auto reply screen.
            }
        }
    }

    private static PendingIntent broadcast(Context ctx, String action, long logId, int req, boolean mutable) {
        Intent i = new Intent(ctx, DraftReceiver.class).setAction(action).putExtra(EXTRA_LOG, logId);
        // The Edit action carries a typed reply, so its intent must be mutable (FLAG_MUTABLE exists from Android 12).
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (mutable ? (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0) : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    /** Sends a draft ({@code text} = edited text, or null for the draft as written). Null on success, else the problem. */
    public static String send(Context ctx, long logId, String text) {
        Pending p = PENDING.get(logId);
        if (p == null) return ctx.getString(R.string.ar_draft_expired);
        String t = text == null || text.trim().isEmpty() ? p.text : text.trim();
        String err = ReplyListener.send(ctx, p.action, p.input, t);
        if (err != null) {
            PENDING.remove(logId);
            cancel(ctx, p.notifId);
            return err;
        }
        PENDING.remove(logId);
        cancel(ctx, p.notifId);
        AutoReplyStore store = AutoReplyStore.get(ctx);
        store.update(logId, AutoReplyStore.S_APPROVED, t);
        store.remember(p.key, "Me: " + t);
        return null;
    }

    public static void discard(Context ctx, long logId) {
        Pending p = PENDING.remove(logId);
        if (p != null) cancel(ctx, p.notifId);
        AutoReplyStore.get(ctx).update(logId, AutoReplyStore.S_DISCARDED, null);
    }

    private static void cancel(Context ctx, int id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }

    private static void expire() {
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) if (now - it.next().time > EXPIRE_MS) it.remove();
    }

    static String editedText(Intent intent) {
        Bundle b = RemoteInput.getResultsFromIntent(intent);
        CharSequence c = b == null ? null : b.getCharSequence(KEY_TEXT);
        return c == null ? null : c.toString();
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
