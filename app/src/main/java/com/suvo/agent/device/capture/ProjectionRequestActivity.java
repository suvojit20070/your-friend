package com.suvo.agent.device.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.suvo.agent.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Transparent activity that shows Android's own screen-capture consent dialog. The app never starts
 * capturing without the user tapping "Start now" in that dialog.
 */
public final class ProjectionRequestActivity extends Activity {

    private static final int REQ = 77;
    private static volatile CountDownLatch pending;

    /** Launches the consent dialog and waits for the user's decision (up to timeoutMs). */
    public static boolean requestAndWait(Context ctx, long timeoutMs) throws InterruptedException {
        if (ScreenCaptureService.isActive()) return true;
        CountDownLatch latch = new CountDownLatch(1);
        pending = latch;
        Intent i = new Intent(ctx, ProjectionRequestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        // Give the service a moment to create the virtual display.
        long end = System.currentTimeMillis() + 3000;
        while (!ScreenCaptureService.isActive() && System.currentTimeMillis() < end && latch.getCount() == 0) {
            Thread.sleep(100);
        }
        return ScreenCaptureService.isActive();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (savedInstanceState == null) startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ScreenCaptureService.class)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(ScreenCaptureService.EXTRA_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
                else startService(svc);
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_SHORT).show();
            }
            CountDownLatch l = pending;
            if (l != null) l.countDown();
            finish();
        }
    }
}
