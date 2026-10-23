package com.suvo.agent.agent;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.suvo.agent.R;
import com.suvo.agent.core.tools.Approver;
import com.suvo.agent.core.util.CancellationToken;
import com.suvo.agent.ui.LiveAgentActivity;
import com.suvo.agent.ui.Notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The approval queue. A request blocks the agent thread until the user answers in the app's dialog
 * or from the notification's Approve/Deny buttons. Nothing the model says can answer it.
 */
public final class ApprovalCenter implements Approver {

    public static final class Pending {
        public final int id;
        public final Request request;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Outcome outcome;
        public final long createdAt = System.currentTimeMillis();

        Pending(int id, Request request) {
            this.id = id;
            this.request = request;
        }
    }

    public interface Listener {
        void onApprovalsChanged(List<Pending> pending);
    }

    static final String ACTION = "com.suvo.agent.APPROVAL";
    static final String EXTRA_ID = "id";
    static final String EXTRA_APPROVED = "approved";

    private final Context ctx;
    private final AtomicInteger ids = new AtomicInteger(1);
    private final CopyOnWriteArrayList<Pending> pending = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public ApprovalCenter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
        l.onApprovalsChanged(pending());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Pending> pending() {
        return new ArrayList<>(pending);
    }

    private void changed() {
        List<Pending> p = pending();
        for (Listener l : listeners) l.onApprovalsChanged(p);
    }

    @Override
    public Outcome request(Request request, long timeoutMs, CancellationToken cancel) {
        Pending p = new Pending(ids.getAndIncrement(), request);
        pending.add(p);
        changed();
        notifyUser(p);
        Runnable onCancel = p.latch::countDown;
        cancel.onCancel(onCancel);
        try {
            boolean answered = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (cancel.isCancelled()) return Outcome.DENIED;
            if (!answered || p.outcome == null) return Outcome.TIMED_OUT;
            return p.outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.DENIED;
        } finally {
            cancel.removeListener(onCancel);
            pending.remove(p);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(Notifications.ID_APPROVAL_BASE + p.id);
            changed();
        }
    }

    /** Called by the in-app dialog or the notification action receiver. */
    public void answer(int id, boolean approved) {
        for (Pending p : pending) {
            if (p.id == id) {
                p.outcome = approved ? Outcome.APPROVED : Outcome.DENIED;
                p.latch.countDown();
            }
        }
    }

    private void notifyUser(Pending p) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent approve = new Intent(ctx, ApprovalReceiver.class).setAction(ACTION).putExtra(EXTRA_ID, p.id).putExtra(EXTRA_APPROVED, true);
        Intent deny = new Intent(ctx, ApprovalReceiver.class).setAction(ACTION).putExtra(EXTRA_ID, p.id).putExtra(EXTRA_APPROVED, false);
        PendingIntent ap = PendingIntent.getBroadcast(ctx, p.id * 2, approve, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dp = PendingIntent.getBroadcast(ctx, p.id * 2 + 1, deny, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(ctx, 3, new Intent(ctx, LiveAgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String risk = ctx.getString(R.string.approval_risk, p.request.risk.name().toLowerCase());
        Notification n = Notifications.builder(ctx, Notifications.CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_stat_companion)
                .setContentTitle(ctx.getString(R.string.approval_title))
                .setContentText(p.request.summary)
                .setStyle(new Notification.BigTextStyle().bigText(p.request.summary + "\n" + risk))
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.action_approve), ap).build())
                .addAction(new Notification.Action.Builder(null, ctx.getString(R.string.action_deny), dp).build())
                .build();
        nm.notify(Notifications.ID_APPROVAL_BASE + p.id, n);
    }
}
