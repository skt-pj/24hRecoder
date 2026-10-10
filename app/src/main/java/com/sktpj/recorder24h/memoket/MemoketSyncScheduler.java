package com.sktpj.recorder24h.memoket;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class MemoketSyncScheduler {
    private static final String PERIODIC_WORK = "memoket-gem-periodic";
    private static final String MANUAL_WORK = "memoket-gem-manual";
    private static final String AFTER_STOP_WORK = "memoket-gem-after-stop";

    private MemoketSyncScheduler() {}

    public static void setPeriodic(Context context, boolean enabled) {
        MemoketSettings.setEnabled(context, enabled);
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_WORK);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                MemoketSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().build())
                .build();
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void syncNow(Context context) {
        scheduleOneTime(context, 0);
    }

    public static void syncAfterStop(Context context, long startedAtMs, long stoppedAtMs, String zoneId) {
        // Only this specific recording is authorized for automatic retrieval.
        new MemoketRecordingWindow(startedAtMs, stoppedAtMs, zoneId);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoketSyncWorker.class)
                .setInputData(new Data.Builder()
                        .putBoolean("manual", true)
                        .putBoolean("afterStop", true)
                        .putLong("startedAtMs", startedAtMs)
                        .putLong("stoppedAtMs", stoppedAtMs)
                        .putString("recordingZone", zoneId)
                        .build())
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(AFTER_STOP_WORK, ExistingWorkPolicy.REPLACE, request);
    }

    private static void scheduleOneTime(Context context, long delaySeconds) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoketSyncWorker.class)
                .setInputData(new Data.Builder().putBoolean("manual", true).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request);
    }
}
