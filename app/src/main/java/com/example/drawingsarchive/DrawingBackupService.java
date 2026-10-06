package com.example.drawingsarchive;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DrawingBackupService extends JobService {
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped.set(false);
        new Thread(() -> {
            DrawingBackup.writeIfDue(getApplicationContext(), stopped::get);
            jobFinished(params, false);
        }, "drawing-daily-backup").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped.set(true);
        return true;
    }
}
