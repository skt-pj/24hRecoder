package com.sktpj.recorder24h.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

public final class AiAnalysisWorker extends Worker {
    private static final int MAX_ATTEMPTS = 3;
    private static final long DAILY_FINALIZATION_GRACE_MS = 15L * 60L * 1000L;

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

        long[] period = periodFor(kind);
        long periodStartMs = period[0];
        long periodEndMs = period[1];
        boolean daily = AiAnalysisScheduler.KIND_DAILY.equals(kind);
        File target = daily
                ? AiAnalysisRepository.dailyFile(context, periodStartMs)
                : AiAnalysisRepository.hourlyFile(context, periodStartMs);

        // Once cleanup has started, the saved daily note is final. Never rebuild it from a
        // partially deleted source; just continue the cleanup until it succeeds.
        if (daily) {
            String cleanupStatus = AiDailySourceCleanup.cleanupStatus(target);
            if (AiDailySourceCleanup.STATUS_PENDING.equals(cleanupStatus)) {
                return finishDailyCleanup(context, target, periodStartMs, periodEndMs, null);
            }
            if (AiDailySourceCleanup.STATUS_COMPLETE.equals(cleanupStatus)) {
                log(context, "AI_DAILY_ANALYSIS_ALREADY_FINALIZED", kind,
                        periodStartMs, periodEndMs, null, null);
                return Result.success();
            }

            // Give the final recording segment time to close after midnight. Then wait until
            // all still-processable audio for the day has left READY/QUEUED/TRANSCRIBING states.
            if (System.currentTimeMillis() < periodEndMs + DAILY_FINALIZATION_GRACE_MS) {
                log(context, "AI_DAILY_ANALYSIS_WAITING_FOR_DAY_CLOSE", kind,
                        periodStartMs, periodEndMs, null, null);
                return Result.retry();
            }
            if (AiDailySourceCleanup.hasPendingTranscription(context, periodStartMs, periodEndMs)) {
                log(context, "AI_DAILY_ANALYSIS_WAITING_FOR_TRANSCRIPTION", kind,
                        periodStartMs, periodEndMs, null, null);
                return Result.retry();
            }
        }

        String apiKey;
        try {
            apiKey = OpenAiKeyStore.load(context);
        } catch (Exception error) {
            log(context, "AI_ANALYSIS_KEY_READ_FAILED", kind, periodStartMs, periodEndMs, null, error);
            return Result.failure();
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.success();
        }

        AiAnalysisRepository.SourceWindow source =
                AiAnalysisRepository.buildSource(context, periodStartMs, periodEndMs);

        if (source.isEmpty()) {
            log(context, "AI_ANALYSIS_SKIPPED_NO_TRANSCRIPT", kind,
                    periodStartMs, periodEndMs, source, null);
            return Result.success();
        }
        if (AiAnalysisRepository.isCurrent(target, source.sourceHash)) {
            log(context, "AI_ANALYSIS_SKIPPED_CURRENT", kind,
                    periodStartMs, periodEndMs, source, null);
            if (daily) {
                return finishDailyCleanup(context, target, periodStartMs, periodEndMs, source);
            }
            return Result.success();
        }

        log(context, "AI_ANALYSIS_STARTED", kind,
                periodStartMs, periodEndMs, source, null);
        try {
            OpenAiLunaClient.Response response = daily
                    ? OpenAiLunaClient.analyzeDaily(apiKey, source)
                    : OpenAiLunaClient.analyzeHourly(apiKey, source);
            AiAnalysisRepository.save(target, kind, source, response);
            log(context, "AI_ANALYSIS_SAVED", kind,
                    periodStartMs, periodEndMs, source, null);
            if (daily) {
                return finishDailyCleanup(context, target, periodStartMs, periodEndMs, source);
            }
            return Result.success();
        } catch (OpenAiLunaClient.ApiException error) {
            log(context, "AI_ANALYSIS_API_FAILED", kind,
                    periodStartMs, periodEndMs, source, error);
            boolean canRetry = error.retryable && getRunAttemptCount() + 1 < MAX_ATTEMPTS;
            return canRetry ? Result.retry() : Result.failure();
        } catch (Exception error) {
            log(context, "AI_ANALYSIS_FAILED", kind,
                    periodStartMs, periodEndMs, source, error);
            return getRunAttemptCount() + 1 < MAX_ATTEMPTS ? Result.retry() : Result.failure();
        }
    }

    private Result finishDailyCleanup(Context context, File target,
                                      long periodStartMs, long periodEndMs,
                                      AiAnalysisRepository.SourceWindow source) {
        try {
            if (!AiDailySourceCleanup.STATUS_PENDING.equals(AiDailySourceCleanup.cleanupStatus(target))) {
                AiDailySourceCleanup.markPending(target);
            }
            AiDailySourceCleanup.CleanupResult cleanup =
                    AiDailySourceCleanup.cleanup(context, periodStartMs, periodEndMs);
            AiDailySourceCleanup.markResult(target, cleanup);
            if (cleanup.failedCount > 0) {
                log(context, "AI_DAILY_SOURCE_CLEANUP_RETRY", AiAnalysisScheduler.KIND_DAILY,
                        periodStartMs, periodEndMs, source, null);
                return Result.retry();
            }
            log(context, "AI_DAILY_SOURCE_CLEANUP_COMPLETE", AiAnalysisScheduler.KIND_DAILY,
                    periodStartMs, periodEndMs, source, null);
            return Result.success();
        } catch (Exception error) {
            log(context, "AI_DAILY_SOURCE_CLEANUP_FAILED", AiAnalysisScheduler.KIND_DAILY,
                    periodStartMs, periodEndMs, source, error);
            return Result.retry();
        }
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
            details.put("model", OpenAiLunaClient.MODEL);
            if (periodStartMs > 0L) details.put("periodStartMs", periodStartMs);
            if (periodEndMs > 0L) details.put("periodEndMs", periodEndMs);
            if (source != null) {
                details.put("sourceTranscriptCount", source.transcriptCount);
                details.put("sourceEntryCount", source.entries.size());
                details.put("sourceHash", source.sourceHash);
            }
            if (error instanceof OpenAiLunaClient.ApiException) {
                OpenAiLunaClient.ApiException apiError = (OpenAiLunaClient.ApiException) error;
                details.put("httpStatus", apiError.statusCode);
                details.put("retryable", apiError.retryable);
            }
            if (error != null) {
                String message = error.getMessage();
                details.put("error", error.getClass().getSimpleName());
                if (message != null) {
                    details.put("message", message.length() > 500 ? message.substring(0, 500) : message);
                }
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
