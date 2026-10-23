package com.suvo.agent.terminal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the RUN_COMMAND result that Termux sends through our PendingIntent. Not exported. */
public final class TermuxResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(TermuxBackend.EXTRA_EXEC_ID, -1);
        Bundle result = intent.getBundleExtra(TermuxBackend.RESULT_BUNDLE);
        if (id >= 0) TermuxBackend.deliver(id, result);
    }
}
