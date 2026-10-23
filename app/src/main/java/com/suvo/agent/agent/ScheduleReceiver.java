package com.suvo.agent.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.suvo.agent.AppGraph;

/**
 * Your Friend — Original project by Suvo.
 *
 * Receives scheduled-task alarms and re-arms all alarms after a reboot, an app update or a change
 * of the exact-alarm permission.
 */
public final class ScheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        AppGraph g;
        try {
            g = AppGraph.get();
        } catch (IllegalStateException e) {
            return;
        }
        if (Scheduler.ACTION_FIRE.equals(action)) {
            long id = intent.getLongExtra(Scheduler.EXTRA_ID, -1);
            if (id >= 0) g.scheduler.fire(id);
        } else {
            g.scheduler.armAll();
        }
    }
}
