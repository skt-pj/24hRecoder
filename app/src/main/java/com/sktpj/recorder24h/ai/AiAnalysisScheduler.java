package com.sktpj.recorder24h.ai;

import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.AiRerunActivity;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;

import java.util.concurrent.TimeUnit;

public final class AiAnalysisScheduler {
    static final String EXTRA_KIND = "analysisKind";
    static final String EXTRA_PERIOD_START_MS = "periodStartMs";
    static final String EXTRA_PERIOD_END_MS = "periodEndMs";
    static final String EXTRA_QUEUE_ID = "queueId";
    static final String EXTRA_REQUEST_TYPE = "requestType";
    static final String EXTRA_FORCE = "force";

    public static final String KIND_HOURLY = "hourly";
    public static final String KIND_DAILY = "daily";
    static final String KIND_WEEKLY = "weekly";
    static final String KIND_MONTHLY = "monthly";
    static final String KIND_YEARLY = "yearly";

    private static final long DAILY_READY_GRACE_MS = 15L * 60L * 1000L;
    private static final String PERIODIC_HOURLY = "ai-analysis-hourly";
    private static final String PERIODIC_DAILY = "ai-analysis-daily";
    private static final String PERIODIC_ROLLUP = "ai-analysis-rollup";
    private static final String NOW_ROLLUP = "ai-analysis-now-rollup";
    private static final String TARGET_PREFIX = "ai-analysis-target-";

    private AiAnalysisScheduler() {
    }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        if (!AiProviderStore.isConfigured(app)) {
            cancel(app);
            return;
        }

        WorkManager manager = WorkManager.getInstance(app);
        Constraints constraints = analysisConstraints(app);

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

    /** Existing AI-notebook action: open the explicit target-period picker. */
    public static void enqueueNow(Context context) {
        if (!AiProviderStore.isConfigured(context.getApplicationContext())) {
            return;
        }
        Intent intent = new Intent(context, AiRerunActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    public static boolean enqueuePeriod(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs) {
        return enqueueTarget(
                context,
                kind,
                periodStartMs,
                periodEndMs,
                AiQueueStore.REQUEST_MANUAL,
                true,
                ExistingWorkPolicy.REPLACE);
    }

    static boolean enqueueScheduledPeriod(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs) {
        return enqueueTarget(
                context,
                kind,
                periodStartMs,
                periodEndMs,
                AiQueueStore.REQUEST_SCHEDULED,
                false,
                ExistingWorkPolicy.KEEP);
    }

    /**
     * Wake only semantic AI queue targets that overlap the transcript just saved.
     * API retry items keep their normal backoff; only data-waiting items are reconsidered.
     */
    public static void wakeWaitingTargets(Context context, String segmentId) {
        Context app = context.getApplicationContext();
        if (!AiProviderStore.isConfigured(app) || segmentId == null || segmentId.isEmpty()) return;

        SegmentRecord sourceRecord = null;
        for (SegmentRecord record : SegmentHistoryRepository.load(app)) {
            if (segmentId.equals(record.getSegmentId())) {
                sourceRecord = record;
                break;
            }
        }
        if (sourceRecord == null) return;

        long recordStartMs = sourceRecord.getStartedAtMs() > 0L
                ? sourceRecord.getStartedAtMs() : sourceRecord.getSortTimeMs();
        if (recordStartMs <= 0L) return;
        long recordEndMs = sourceRecord.getEndedAtMs() > recordStartMs
                ? sourceRecord.getEndedAtMs() : recordStartMs + 1L;

        for (AiQueueStore.Entry entry : AiQueueStore.load(app)) {
            if (!AiQueueStore.STATE_WAITING_DATA.equals(entry.state)) continue;
            if (!KIND_HOURLY.equals(entry.kind) && !KIND_DAILY.equals(entry.kind)) continue;
            if (recordStartMs >= entry.periodEndMs || recordEndMs <= entry.periodStartMs) continue;
            boolean manual = AiQueueStore.REQUEST_MANUAL.equals(entry.requestType);
            enqueueTarget(
                    app,
                    entry.kind,
                    entry.periodStartMs,
                    entry.periodEndMs,
                    entry.requestType,
                    manual,
                    ExistingWorkPolicy.REPLACE);
        }
    }

    public static void removeTarget(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs) {
        Context app = context.getApplicationContext();
        String uniqueName = targetWorkName(kind, periodStartMs, periodEndMs);
        WorkManager.getInstance(app).cancelUniqueWork(uniqueName);
        AiQueueStore.remove(app, targetQueueId(kind, periodStartMs, periodEndMs));
    }

    private static boolean enqueueTarget(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs,
            String requestType,
            boolean force,
            ExistingWorkPolicy policy) {
        Context app = context.getApplicationContext();
        if (!AiProviderStore.isConfigured(app)) return false;
        if (!KIND_HOURLY.equals(kind) && !KIND_DAILY.equals(kind)) return false;
        if (periodStartMs <= 0L || periodEndMs <= periodStartMs) return false;

        String queueId = targetQueueId(kind, periodStartMs, periodEndMs);
        String effectiveRequestType = requestType;
        boolean effectiveForce = force;
        for (AiQueueStore.Entry existing : AiQueueStore.load(app)) {
            if (queueId.equals(existing.id)
                    && AiQueueStore.REQUEST_MANUAL.equals(existing.requestType)) {
                effectiveRequestType = AiQueueStore.REQUEST_MANUAL;
                effectiveForce = true;
                break;
            }
        }

        AiQueueStore.upsert(
                app,
                queueId,
                kind,
                periodStartMs,
                periodEndMs,
                effectiveRequestType,
                AiQueueStore.STATE_QUEUED,
                0,
                AiQueueStore.REQUEST_MANUAL.equals(effectiveRequestType)
                        ? "ユーザー指定期間" : "定期実行対象");

        Data input = new Data.Builder()
                .putString(EXTRA_KIND, kind)
                .putLong(EXTRA_PERIOD_START_MS, periodStartMs)
                .putLong(EXTRA_PERIOD_END_MS, periodEndMs)
                .putString(EXTRA_QUEUE_ID, queueId)
                .putString(EXTRA_REQUEST_TYPE, effectiveRequestType)
                .putBoolean(EXTRA_FORCE, effectiveForce)
                .build();

        String kindTag = KIND_HOURLY.equals(kind)
                ? "ai-analysis-target-hourly" : "ai-analysis-target-daily";
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(AiAnalysisWorker.class)
                .setInputData(input)
                .setConstraints(analysisConstraints(app))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis")
                .addTag("ai-analysis-target")
                .addTag(kindTag);

        long notBeforeMs = KIND_DAILY.equals(kind)
                ? periodEndMs + DAILY_READY_GRACE_MS : periodEndMs;
        long delayMs = Math.max(0L, notBeforeMs - System.currentTimeMillis());
        if (delayMs > 0L) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS);
        }

        WorkManager.getInstance(app).enqueueUniqueWork(
                targetWorkName(kind, periodStartMs, periodEndMs),
                policy,
                builder.build());
        return true;
    }

    static String targetQueueId(String kind, long periodStartMs, long periodEndMs) {
        return kind + ":" + periodStartMs + ":" + periodEndMs;
    }

    private static String targetWorkName(String kind, long periodStartMs, long periodEndMs) {
        return TARGET_PREFIX + safeId(kind + "-" + periodStartMs + "-" + periodEndMs);
    }

    static void enqueueRollup(Context context) {
        Context app = context.getApplicationContext();
        if (!AiProviderStore.isConfigured(app)) {
            return;
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
                NOW_ROLLUP,
                ExistingWorkPolicy.REPLACE,
                oneTime(
                        AiRollupWorker.class,
                        null,
                        analysisConstraints(app),
                        "ai-rollup-now",
                        NOW_ROLLUP));
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(PERIODIC_HOURLY);
        manager.cancelUniqueWork(PERIODIC_DAILY);
        manager.cancelUniqueWork(PERIODIC_ROLLUP);
        manager.cancelUniqueWork(NOW_ROLLUP);
        manager.cancelAllWorkByTag("ai-analysis-target");
    }

    private static String safeId(String raw) {
        return raw.replace(':', '-').replace('/', '-');
    }

    private static Constraints analysisConstraints(Context context) {
        NetworkType networkType = AiInferenceClient.requiresNetwork(context)
                ? NetworkType.CONNECTED : NetworkType.NOT_REQUIRED;
        return new Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build();
    }

    private static OneTimeWorkRequest oneTime(
            Class<? extends androidx.work.ListenableWorker> workerClass,
            Data inputData,
            Constraints constraints,
            String... tags) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(workerClass)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("ai-analysis");
        for (String tag : tags) {
            builder.addTag(tag);
        }
        if (inputData != null) {
            builder.setInputData(inputData);
        }
        return builder.build();
    }

    private static Data data(String kind) {
        return new Data.Builder().putString(EXTRA_KIND, kind).build();
    }
}
