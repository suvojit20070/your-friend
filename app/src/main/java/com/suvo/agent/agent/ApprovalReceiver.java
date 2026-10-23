package com.suvo.agent.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.suvo.agent.AppGraph;

/** Handles Approve/Deny taps on approval notifications. Not exported. */
public final class ApprovalReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ApprovalCenter.ACTION.equals(intent.getAction())) return;
        int id = intent.getIntExtra(ApprovalCenter.EXTRA_ID, -1);
        boolean approved = intent.getBooleanExtra(ApprovalCenter.EXTRA_APPROVED, false);
        AppGraph.get().approvals.answer(id, approved);
    }
}
