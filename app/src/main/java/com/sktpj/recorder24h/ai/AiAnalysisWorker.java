package com.sktpj.recorder24h.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class AiAnalysisWorker extends Worker {
    private static final int MAX_ATTEMPTS = 3;
    private static final long DAILY_FINALIZATION_GRACE_MS = 15L * 60L * 1000L;
    private static final String PURGED_REASON = "AI_DAILY_SUMMARY_SOURCE_PURGED";

    public AiAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String kind = getInputData().getString(AiAnalysisScheduler.EXTRA_KIND);
        if (!AiAnalysisScheduler.KIND_HOURLY.equals(kind)
                && !AiAnalysisScheduler.KIND_DAILY.equals(kind)) {
            return Result.failure();
        }

        long explicitStartMs = getInputData().getLong(
                AiAnalysisScheduler.EXTRA_PERIOD_START_MS, 0L);
        long explicitEndMs = getInputData().getLong(
                AiAnalysisScheduler.EXTRA_PERIOD_END_MS, 0L);

        // Periodic WorkManager jobs only decide what period is due. Actual processing is moved to
        // a one-time target work item so retries keep the exact same period instead of drifting.
        if (explicitStartMs <= 0L || explicitEndMs <= explicitStartMs) {
            long[] period = periodFor(kind);
            AiAnalysisScheduler.enqueueScheduledPeriod(context, kind, period[0], period[1]);
            return Result.success();
        }

        long periodStartMs = explicitStartMs;
        long periodEndMs = explicitEndMs;
        String queueId = getInputData().getString(AiAnalysisScheduler.EXTRA_QUEUE_ID);
        if (queueId == null || queueId.isEmpty()) {
            queueId = AiAnalysisScheduler.targetQueueId(kind, periodStartMs, periodEndMs);
        }
        String requestType = getInputData().getString(AiAnalysisScheduler.EXTRA_REQUEST_TYPE);
        if (requestType == null || requestType.isEmpty()) {
            requestType = AiQueueStore.REQUEST_SCHEDULED;
        }
        boolean manualRequest = AiQueueStore.REQUEST_MANUAL.equals(requestType);
        boolean force = getInputData().getBoolean(AiAnalysisScheduler.EXTRA_FORCE, false);
        boolean daily = AiAnalysisScheduler.KIND_DAILY.equals(kind);

        setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                AiQueueStore.STATE_RUNNING, "AI分析を実行中");

        File target = daily
                ? AiAnalysisRepository.dailyFile(context, periodStartMs)
                : AiAnalysisRepository.hourlyFile(context, periodStartMs);

        if (daily) {
            String cleanupStatus = AiDailySourceCleanup.cleanupStatus(target);
            if (!force && AiDailySourceCleanup.STATUS_PENDING.equals(cleanupStatus)) {
                return finishDailyCleanup(
                        context, target, periodStartMs, periodEndMs, null,
                        queueId, kind, requestType);
            }
            if (!force && AiDailySourceCleanup.STATUS_COMPLETE.equals(cleanupStatus)) {
                log(context, "AI_DAILY_ANALYSIS_ALREADY_FINALIZED", kind,
                        periodStartMs, periodEndMs, null, null);
                AiQueueStore.remove(context, queueId);
                AiAnalysisScheduler.enqueueRollup(context);
                return Result.success();
            }
            if (System.currentTimeMillis() < periodEndMs + DAILY_FINALIZATION_GRACE_MS) {
                setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                        AiQueueStore.STATE_WAITING_DATA, "日次対象期間の終了待ち");
                log(context, "AI_DAILY_ANALYSIS_WAITING_FOR_DAY_CLOSE", kind,
                        periodStartMs, periodEndMs, null, null);
                // Data/time wait is a semantic queue state, not a WorkManager retry loop.
                return Result.success();
            }
        }

        // Scheduled generation waits until every non-corrupt recording in the target period has
        // finished transcription. Explicit user generation/re-generation may use any transcripts
        // already available in the period and does not wait for the remaining recordings.
        if (!manualRequest && hasPendingScheduledTranscription(context, periodStartMs, periodEndMs)) {
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    AiQueueStore.STATE_WAITING_DATA, "対象期間の文字起こし待ち");
            log(context, "AI_ANALYSIS_WAITING_FOR_TRANSCRIPTION", kind,
                    periodStartMs, periodEndMs, null, null);
            // TranscriptionRepository wakes only overlapping waiting targets when data is saved.
            return Result.success();
        }

        if (!AiInferenceClient.isConfigured(context)) {
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    AiQueueStore.STATE_FAILED, "AI設定が無効です");
            return Result.success();
        }
        String modelId = AiInferenceClient.modelId(context);

        // Daily finalization can intentionally purge raw transcripts. A forced rerun must never
        // rebuild from only the surviving subset, because that would overwrite a correct note
        // with an incomplete one. The same protection applies to an hourly slice of a purged day.
        if (force && sourceWasPurged(context, periodStartMs, periodEndMs)) {
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    AiQueueStore.STATE_FAILED, "元の文字起こしデータは日次確定後に削除済みです");
            log(context, "AI_ANALYSIS_SOURCE_ALREADY_PURGED", kind,
                    periodStartMs, periodEndMs, null, null);
            return Result.success();
        }

        AiAnalysisRepository.SourceWindow source =
                AiAnalysisRepository.buildSource(context, periodStartMs, periodEndMs);

        if (source.isEmpty()) {
            if (hasAnyRecording(context, periodStartMs, periodEndMs)) {
                // Recording exists and transcription has no pending work: this is a completed
                // no-speech/empty-transcript period, not a queue item that should wait forever.
                AiQueueStore.remove(context, queueId);
                log(context, "AI_ANALYSIS_SKIPPED_NO_TRANSCRIPT", kind,
                        periodStartMs, periodEndMs, source, null);
                if (daily) AiAnalysisScheduler.enqueueRollup(context);
                return Result.success();
            }
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    AiQueueStore.STATE_WAITING_DATA, "対象期間の録音・文字起こしデータ待ち");
            log(context, "AI_ANALYSIS_WAITING_FOR_SOURCE", kind,
                    periodStartMs, periodEndMs, source, null);
            // Leave the semantic queue item dormant until overlapping transcript data arrives.
            return Result.success();
        }

        if (!force && AiAnalysisRepository.isCurrent(target, source.sourceHash, modelId)) {
            AiQueueStore.remove(context, queueId);
            log(context, "AI_ANALYSIS_SKIPPED_CURRENT", kind,
                    periodStartMs, periodEndMs, source, null);
            if (daily) {
                return finishDailyCleanup(
                        context, target, periodStartMs, periodEndMs, source,
                        queueId, kind, requestType);
            }
            return Result.success();
        }

        log(context, "AI_ANALYSIS_STARTED", kind,
                periodStartMs, periodEndMs, source, null);
        try {
            OpenAiLunaClient.Response response = daily
                    ? AiInferenceClient.analyzeDaily(context, source)
                    : AiInferenceClient.analyzeHourly(context, source);
            AiAnalysisRepository.save(target, kind, source, response, modelId);
            log(context, "AI_ANALYSIS_SAVED", kind,
                    periodStartMs, periodEndMs, source, null);
            if (daily) {
                return finishDailyCleanup(
                        context, target, periodStartMs, periodEndMs, source,
                        queueId, kind, requestType);
            }
            AiQueueStore.remove(context, queueId);
            return Result.success();
        } catch (OpenAiLunaClient.ApiException error) {
            log(context, "AI_ANALYSIS_API_FAILED", kind,
                    periodStartMs, periodEndMs, source, error);
            boolean canRetry = error.retryable && getRunAttemptCount() + 1 < MAX_ATTEMPTS;
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    canRetry ? AiQueueStore.STATE_RETRY_WAIT : AiQueueStore.STATE_FAILED,
                    canRetry ? "APIエラーの再試行待ち" : "AI分析に失敗しました");
            return canRetry ? Result.retry() : Result.failure();
        } catch (Exception error) {
            log(context, "AI_ANALYSIS_FAILED", kind,
                    periodStartMs, periodEndMs, source, error);
            boolean canRetry = getRunAttemptCount() + 1 < MAX_ATTEMPTS;
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    canRetry ? AiQueueStore.STATE_RETRY_WAIT : AiQueueStore.STATE_FAILED,
                    canRetry ? "処理エラーの再試行待ち" : "AI分析に失敗しました");
            return canRetry ? Result.retry() : Result.failure();
        }
    }

    private Result finishDailyCleanup(
            Context context,
            File target,
            long periodStartMs,
            long periodEndMs,
            AiAnalysisRepository.SourceWindow source,
            String queueId,
            String kind,
            String requestType) {
        try {
            if (!AiDailySourceCleanup.STATUS_PENDING.equals(
                    AiDailySourceCleanup.cleanupStatus(target))) {
                AiDailySourceCleanup.markPending(target);
            }
            AiDailySourceCleanup.CleanupResult cleanup =
                    AiDailySourceCleanup.cleanup(context, periodStartMs, periodEndMs);
            AiDailySourceCleanup.markResult(target, cleanup);
            if (cleanup.failedCount > 0) {
                setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                        AiQueueStore.STATE_RETRY_WAIT, "日次ソース整理の再試行待ち");
                log(context, "AI_DAILY_SOURCE_CLEANUP_RETRY", AiAnalysisScheduler.KIND_DAILY,
                        periodStartMs, periodEndMs, source, null);
                return Result.retry();
            }
            AiQueueStore.remove(context, queueId);
            log(context, "AI_DAILY_SOURCE_CLEANUP_COMPLETE", AiAnalysisScheduler.KIND_DAILY,
                    periodStartMs, periodEndMs, source, null);
            AiAnalysisScheduler.enqueueRollup(context);
            return Result.success();
        } catch (Exception error) {
            setQueueState(context, queueId, kind, periodStartMs, periodEndMs, requestType,
                    AiQueueStore.STATE_RETRY_WAIT, "日次ソース整理の再試行待ち");
            log(context, "AI_DAILY_SOURCE_CLEANUP_FAILED", AiAnalysisScheduler.KIND_DAILY,
                    periodStartMs, periodEndMs, source, error);
            return Result.retry();
        }
    }

    private void setQueueState(
            Context context,
            String queueId,
            String kind,
            long periodStartMs,
            long periodEndMs,
            String requestType,
            String state,
            String message) {
        AiQueueStore.upsert(
                context,
                queueId,
                kind,
                periodStartMs,
                periodEndMs,
                requestType,
                state,
                getRunAttemptCount(),
                message);
    }

    private static boolean hasPendingScheduledTranscription(
            Context context,
            long periodStartMs,
            long periodEndMs) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (!overlaps(record, periodStartMs, periodEndMs)) {
                continue;
            }
            if ("CORRUPT".equals(record.getStatus())) {
                continue;
            }
            if (!record.getAudioAvailable()) {
                continue;
            }
            String status = record.getStatus();
            if (!record.getHasTranscript()
                    || "READY".equals(status)
                    || "QUEUED".equals(status)
                    || "RETRY_WAIT".equals(status)
                    || "TRANSCRIBING".equals(status)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyRecording(Context context, long periodStartMs, long periodEndMs) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (overlaps(record, periodStartMs, periodEndMs)) return true;
        }
        return false;
    }

    private static boolean sourceWasPurged(Context context, long periodStartMs, long periodEndMs) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (overlaps(record, periodStartMs, periodEndMs)
                    && PURGED_REASON.equals(record.getReason())) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(SegmentRecord record, long periodStartMs, long periodEndMs) {
        long start = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
        if (start <= 0L) return false;
        long end = record.getEndedAtMs() > start ? record.getEndedAtMs() : start + 1L;
        return start < periodEndMs && end > periodStartMs;
    }

    private static long[] periodFor(String kind) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (AiAnalysisScheduler.KIND_HOURLY.equals(kind)) {
            ZonedDateTime end = now.truncatedTo(ChronoUnit.HOURS);
            ZonedDateTime start = end.minusHours(1);
            return new long[]{start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()};
        }
        ZonedDateTime end = now.toLocalDate().atStartOfDay(zone);
        ZonedDateTime start = end.minusDays(1);
        return new long[]{start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli()};
    }

    private static void log(Context context, String event, String kind,
                            long periodStartMs, long periodEndMs,
                            AiAnalysisRepository.SourceWindow source, Throwable error) {
        try {
            JSONObject details = new JSONObject();
            details.put("kind", kind);
            details.put("model", AiInferenceClient.modelId(context));
            details.put("provider", AiProviderStore.getProvider(context));
            if (periodStartMs > 0L) details.put("periodStartMs", periodStartMs);
            if (periodEndMs > 0L) details.put("periodEndMs", periodEndMs);
            if (source != null) {
                details.put("sourceTranscriptCount", source.transcriptCount);
                details.put("sourceEntryCount", source.entries.size());
                details.put("sourceHash", source.sourceHash);
            }
            if (error instanceof OpenAiLunaClient.ApiException) {
                OpenAiLunaClient.ApiException apiError =
                        (OpenAiLunaClient.ApiException) error;
                details.put("httpStatus", apiError.statusCode);
                details.put("retryable", apiError.retryable);
            }
            if (error != null) {
                String message = error.getMessage();
                details.put("error", error.getClass().getSimpleName());
                if (message != null) {
                    details.put("message",
                            message.length() > 500 ? message.substring(0, 500) : message);
                }
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
