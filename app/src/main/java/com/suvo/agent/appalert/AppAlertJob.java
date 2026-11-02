package com.suvo.agent.appalert;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Your Friend — Original project by Suvo.
 *
 * Background check every ~15 minutes (and after reboot): finds apps installed while Your Friend
 * was not running, so the new-app alert never misses one.
 */
public final class AppAlertJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                AppAlerts.scan(getApplicationContext());
            } finally {
                jobFinished(params, false);
            }
        }, "yf-appalert-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
