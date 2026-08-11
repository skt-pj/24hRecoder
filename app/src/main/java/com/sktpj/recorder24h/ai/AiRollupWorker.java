package com.sktpj.recorder24h.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public final class AiRollupWorker extends Worker {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_GENERATIONS_PER_RUN = 4;

    public AiRollupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!AiInferenceClient.isConfigured(context)) {
            return Result.success();
        }
        String modelId = AiInferenceClient.modelId(context);

        int generated = 0;
        for (PeriodSpec spec : completedPeriods()) {
            AiRollupRepository.RollupSource source =
                    AiAnalysisScheduler.KIND_YEARLY.equals(spec.kind)
                            ? AiRollupRepository.buildYearSource(
                                    context, spec.periodStartMs, spec.periodEndMs)
                            : AiRollupRepository.buildSource(
                                    context,
                                    AiAnalysisScheduler.KIND_DAILY,
                                    spec.periodStartMs,
                                    spec.periodEndMs);

            if (source.isEmpty()) {
                log(context, "AI_ROLLUP_SKIPPED_NO_SOURCE", spec.kind,
                        spec.periodStartMs, spec.periodEndMs, source, null);
                continue;
            }

            File target = AiRollupRepository.fileFor(context, spec.kind, spec.periodStartMs);
            if (AiRollupRepository.isCurrent(target, source.sourceHash, modelId)) {
                log(context, "AI_ROLLUP_SKIPPED_CURRENT", spec.kind,
                        spec.periodStartMs, spec.periodEndMs, source, null);
                continue;
            }

            log(context, "AI_ROLLUP_STARTED", spec.kind,
                    spec.periodStartMs, spec.periodEndMs, source, null);
            try {
                OpenAiLunaClient.Response response =
                        AiInferenceClient.analyzeRollup(context, spec.kind, source);
                AiRollupRepository.save(target, spec.kind, source, response, modelId);
                generated++;
                log(context, "AI_ROLLUP_SAVED", spec.kind,
                        spec.periodStartMs, spec.periodEndMs, source, null);
                if (generated >= MAX_GENERATIONS_PER_RUN) {
                    break;
                }
            } catch (OpenAiLunaClient.ApiException error) {
                log(context, "AI_ROLLUP_API_FAILED", spec.kind,
                        spec.periodStartMs, spec.periodEndMs, source, error);
                boolean canRetry =
                        error.retryable && getRunAttemptCount() + 1 < MAX_ATTEMPTS;
                return canRetry ? Result.retry() : Result.failure();
            } catch (Exception error) {
                log(context, "AI_ROLLUP_FAILED", spec.kind,
                        spec.periodStartMs, spec.periodEndMs, source, error);
                return getRunAttemptCount() + 1 < MAX_ATTEMPTS
                        ? Result.retry() : Result.failure();
            }
        }

        return Result.success();
    }

    private static List<PeriodSpec> completedPeriods() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<PeriodSpec> specs = new ArrayList<>();

        LocalDate thisWeekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime latestWeekEnd = thisWeekStart.atStartOfDay(zone);

        YearMonth currentMonth = YearMonth.from(now);
        ZonedDateTime latestMonthEnd = currentMonth.atDay(1).atStartOfDay(zone);

        ZonedDateTime latestYearEnd = LocalDate.of(now.getYear(), 1, 1).atStartOfDay(zone);

        addWeek(specs, latestWeekEnd.minusWeeks(1), latestWeekEnd);
        addMonth(specs, latestMonthEnd.minusMonths(1), latestMonthEnd);
        addYear(specs, latestYearEnd.minusYears(1), latestYearEnd);

        for (int offset = 2; offset <= 8; offset++) {
            ZonedDateTime end = latestWeekEnd.minusWeeks(offset - 1L);
            addWeek(specs, end.minusWeeks(1), end);
        }
        for (int offset = 2; offset <= 14; offset++) {
            ZonedDateTime end = latestMonthEnd.minusMonths(offset - 1L);
            addMonth(specs, end.minusMonths(1), end);
        }
        for (int offset = 2; offset <= 3; offset++) {
            ZonedDateTime end = latestYearEnd.minusYears(offset - 1L);
            addYear(specs, end.minusYears(1), end);
        }

        return specs;
    }

    private static void addWeek(List<PeriodSpec> specs,
                                ZonedDateTime start, ZonedDateTime end) {
        specs.add(new PeriodSpec(
                AiAnalysisScheduler.KIND_WEEKLY,
                start.toInstant().toEpochMilli(),
                end.toInstant().toEpochMilli()));
    }

    private static void addMonth(List<PeriodSpec> specs,
                                 ZonedDateTime start, ZonedDateTime end) {
        specs.add(new PeriodSpec(
                AiAnalysisScheduler.KIND_MONTHLY,
                start.toInstant().toEpochMilli(),
                end.toInstant().toEpochMilli()));
    }

    private static void addYear(List<PeriodSpec> specs,
                                ZonedDateTime start, ZonedDateTime end) {
        specs.add(new PeriodSpec(
                AiAnalysisScheduler.KIND_YEARLY,
                start.toInstant().toEpochMilli(),
                end.toInstant().toEpochMilli()));
    }

    private static void log(Context context, String event, String kind,
                            long periodStartMs, long periodEndMs,
                            AiRollupRepository.RollupSource source, Throwable error) {
        try {
            JSONObject details = new JSONObject();
            if (kind != null) {
                details.put("kind", kind);
            }
            details.put("model", AiInferenceClient.modelId(context));
            details.put("provider", AiProviderStore.getProvider(context));
            if (periodStartMs > 0L) {
                details.put("periodStartMs", periodStartMs);
            }
            if (periodEndMs > 0L) {
                details.put("periodEndMs", periodEndMs);
            }
            if (source != null) {
                details.put("sourceAnalysisCount", source.documents.size());
                details.put("sourceHash", source.sourceHash);
            }
            if (error instanceof OpenAiLunaClient.ApiException) {
                OpenAiLunaClient.ApiException apiError =
                        (OpenAiLunaClient.ApiException) error;
                details.put("httpStatus", apiError.statusCode);
                details.put("retryable", apiError.retryable);
            }
            if (error != null) {
                details.put("error", error.getClass().getSimpleName());
                String message = error.getMessage();
                if (message != null) {
                    details.put("message",
                            message.length() > 500 ? message.substring(0, 500) : message);
                }
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }

    private static final class PeriodSpec {
        final String kind;
        final long periodStartMs;
        final long periodEndMs;

        PeriodSpec(String kind, long periodStartMs, long periodEndMs) {
            this.kind = kind;
            this.periodStartMs = periodStartMs;
            this.periodEndMs = periodEndMs;
        }
    }
}
