package com.suvo.agent.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.util.concurrent.CountDownLatch;

/**
 * Receives PackageInstaller status. For STATUS_PENDING_USER_ACTION it shows Android's install
 * confirmation screen; it never bypasses it.
 */
public final class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int session = intent.getIntExtra("session", -1);
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        Object[] slot = WebTools.InstallApk.PENDING.get(session);
        if (slot != null) {
            slot[1] = status;
            slot[2] = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            ((CountDownLatch) slot[0]).countDown();
        }
    }
}
