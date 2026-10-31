package com.suvo.agent.autoreply;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.suvo.agent.R;

/**
 * Your Friend — Original project by Suvo.
 *
 * The buttons on a draft notification: Send (the draft as written), Edit (send what the owner typed
 * in the notification instead) and Discard.
 */
public final class DraftReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        long id = intent.getLongExtra(Drafts.EXTRA_LOG, 0);
        if (id == 0) return;
        String err = null;
        switch (intent.getAction()) {
            case Drafts.ACTION_SEND:
                err = Drafts.send(ctx, id, null);
                break;
            case Drafts.ACTION_EDIT:
                err = Drafts.send(ctx, id, Drafts.editedText(intent));
                break;
            case Drafts.ACTION_DISCARD:
                Drafts.discard(ctx, id);
                return;
            default:
                return;
        }
        Toast.makeText(ctx, err == null ? ctx.getString(R.string.ar_sent_toast) : err, Toast.LENGTH_SHORT).show();
    }
}
