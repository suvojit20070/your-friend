package com.suvo.agent.launch;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.ui.Notifications;

/**
 * Your Friend — Original project by Suvo.
 *
 * "Reply" right in the answer's notification, like a messaging app: the text goes to the same
 * conversation as a new message, and the notification shows that it was sent.
 */
public final class ReplyReceiver extends BroadcastReceiver {

    public static final String KEY_TEXT = "reply_text";
    public static final String EXTRA_CONVERSATION = "conversation";

    /** The "Reply" action for a notification about {@code conversationId}. */
    public static Notification.Action action(Context ctx, long conversationId) {
        Intent i = new Intent(ctx, ReplyReceiver.class).putExtra(EXTRA_CONVERSATION, conversationId);
        // Mutable: Android puts the typed text into it.
        PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) (conversationId % 10000) + 300, i,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput in = new RemoteInput.Builder(KEY_TEXT).setLabel(ctx.getString(R.string.notif_reply_hint)).build();
        return new Notification.Action.Builder(null, ctx.getString(R.string.notif_reply), pi).addRemoteInput(in).build();
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence text = r == null ? null : r.getCharSequence(KEY_TEXT);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (text == null || text.toString().trim().isEmpty()) return;
        AppGraph g = AppGraph.get();
        long conv = intent.getLongExtra(EXTRA_CONVERSATION, -1);
        if (conv < 0) conv = g.agent.activeConversation();
        String err = g.agent.submit(conv, text.toString().trim(), null);
        if (nm != null) {
            Notification n = Notifications.builder(ctx, Notifications.CHANNEL_GENERAL)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle(g.characters.active().name)
                    .setContentText(err == null ? ctx.getString(R.string.notif_reply_sent, text) : err)
                    .setTimeoutAfter(4000)
                    .setAutoCancel(true)
                    .build();
            nm.notify(Notifications.ID_TASK_DONE, n);
        }
    }
}
