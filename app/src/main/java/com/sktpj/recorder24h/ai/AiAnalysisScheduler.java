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
    static final String KIND_WEEKLY = "weekly";
    static final String KIND_MONTHLY = "monthly";
    static final String KIND_YEARLY = "yearly";

    private static final String PERIODIC_HOURLY = "ai-analysis-hourly";
    private static final String PERIODIC_DAILY = "ai-analysis-daily";
    private static final String PERIODIC_ROLLUP = "ai-analysis-rollup";
    private static final String NOW_HOURLY = "ai-analysis-now-hourly";
    private static final String NOW_DAILY = "ai-analysis-now-daily";
    private static final String NOW_ROLLUP = "ai-analysis-now-rollup";

    private AiAnalysisScheduler() {
    }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        if (!OpenAiKeyStore.hasKey(app)) {
            cancel(app);
            return;
        }

        WorkManager manager = WorkManager.getInstance(app);
        Constraints constraints = networkConstraints();

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

        PeriodicWorkRequest rollup = new PeriodicWorkRequest.Builder(
                AiRollupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag("ai-rollup")
                .addTag(PERIODIC_ROLLUP)
                .build();
        manager.enqueueUniquePeriodicWork(
                PERIODIC_ROLLUP,
                ExistingPeriodicWorkPolicy.UPDATE,
                rollup);
    }

    public static void enqueueNow(Context context) {
        Context app = context.getApplicationContext();
        if (!OpenAiKeyStore.hasKey(app)) {
            return;
        }
        WorkManager manager = WorkManager.getInstance(app);
        Constraints constraints = networkConstraints();

        manager.enqueueUniqueWork(
                NOW_HOURLY,
                ExistingWorkPolicy.REPLACE,
                oneTime(AiAnalysisWorker.class, data(KIND_HOURLY), constraints, "ai-analysis-now"));
        manager.enqueueUniqueWork(
                NOW_DAILY,
                ExistingWorkPolicy.REPLACE,
                oneTime(AiAnalysisWorker.class, data(KIND_DAILY), constraints, "ai-analysis-now"));
        enqueueRollup(app);
    }

    static void enqueueRollup(Context context) {
        Context app = context.getApplicationContext();
        if (!OpenAiKeyStore.hasKey(app)) {
            return;
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
                NOW_ROLLUP,
                ExistingWorkPolicy.REPLACE,
                oneTime(AiRollupWorker.class, null, networkConstraints(), "ai-rollup-now"));
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(PERIODIC_HOURLY);
        manager.cancelUniqueWork(PERIODIC_DAILY);
        manager.cancelUniqueWork(PERIODIC_ROLLUP);
        manager.cancelUniqueWork(NOW_HOURLY);
        manager.cancelUniqueWork(NOW_DAILY);
        manager.cancelUniqueWork(NOW_ROLLUP);
    }

    private static Constraints networkConstraints() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    private static OneTimeWorkRequest oneTime(
            Class<? extends androidx.work.ListenableWorker> workerClass,
            Data inputData,
            Constraints constraints,
            String tag) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(workerClass)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag(tag);
        if (inputData != null) {
            builder.setInputData(inputData);
        }
        return builder.build();
    }

    private static Data data(String kind) {
        return new Data.Builder().putString(EXTRA_KIND, kind).build();
    }
}
