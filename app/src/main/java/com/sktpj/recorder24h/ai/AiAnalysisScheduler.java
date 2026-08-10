package com.sktpj.recorder24h.ai;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public final class AiAnalysisScheduler {
    static final String EXTRA_KIND = "analysisKind";
    static final String KIND_HOURLY = "hourly";
    static final String KIND_DAILY = "daily";

    private static final String PERIODIC_HOURLY = "ai-analysis-hourly";
    private static final String PERIODIC_DAILY = "ai-analysis-daily";
    private static final String NOW_HOURLY = "ai-analysis-now-hourly";
    private static final String NOW_DAILY = "ai-analysis-now-daily";

    private AiAnalysisScheduler() {
    }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        if (!OpenAiKeyStore.hasKey(app)) {
            cancel(app);
            return;
        }

        WorkManager manager = WorkManager.getInstance(app);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest hourly = new PeriodicWorkRequest.Builder(
                AiAnalysisWorker.class, 1, TimeUnit.HOURS)
                .setInputData(data(KIND_HOURLY))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag(PERIODIC_HOURLY)
                .build();
        manager.enqueueUniquePeriodicWork(
                PERIODIC_HOURLY,
                ExistingPeriodicWorkPolicy.UPDATE,
                hourly);

        PeriodicWorkRequest daily = new PeriodicWorkRequest.Builder(
                AiAnalysisWorker.class, 24, TimeUnit.HOURS)
                .setInputData(data(KIND_DAILY))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag(PERIODIC_DAILY)
                .build();
        manager.enqueueUniquePeriodicWork(
                PERIODIC_DAILY,
                ExistingPeriodicWorkPolicy.UPDATE,
                daily);
    }

    public static void enqueueNow(Context context) {
        Context app = context.getApplicationContext();
        if (!OpenAiKeyStore.hasKey(app)) {
            return;
        }
        WorkManager manager = WorkManager.getInstance(app);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        manager.enqueueUniqueWork(
                NOW_HOURLY,
                ExistingWorkPolicy.REPLACE,
                oneTime(KIND_HOURLY, constraints));
        manager.enqueueUniqueWork(
                NOW_DAILY,
                ExistingWorkPolicy.REPLACE,
                oneTime(KIND_DAILY, constraints));
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(PERIODIC_HOURLY);
        manager.cancelUniqueWork(PERIODIC_DAILY);
        manager.cancelUniqueWork(NOW_HOURLY);
        manager.cancelUniqueWork(NOW_DAILY);
    }

    private static OneTimeWorkRequest oneTime(String kind, Constraints constraints) {
        return new OneTimeWorkRequest.Builder(AiAnalysisWorker.class)
                .setInputData(data(kind))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag("ai-analysis-now")
                .build();
    }

    private static Data data(String kind) {
        return new Data.Builder().putString(EXTRA_KIND, kind).build();
    }
}
