package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

/** Lightweight main-process scheduler worker. Heavy Whisper remains in :postprocess_asr. */
public final class NightlyHourlyTranscriptionWorker extends Worker {
    public NightlyHourlyTranscriptionWorker(@NonNull Context context,
                                            @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        try {
            int enqueued = NightlyHourlyTranscriptionScheduler.enqueuePreviousDayBatches(app);
            AppLogger.event(app, "NIGHTLY_HOURLY_SCAN_COMPLETED", new JSONObject()
                    .put("enqueuedCount", enqueued)
                    .put("runAttemptCount", getRunAttemptCount())
                    .put("heavyInferenceInThisWorker", false));
            return Result.success();
        } catch (Throwable error) {
            try {
                AppLogger.event(app, "NIGHTLY_HOURLY_SCAN_FAILED", new JSONObject()
                        .put("error", error.getClass().getSimpleName())
                        .put("message", error.getMessage() == null ? "" : error.getMessage())
                        .put("runAttemptCount", getRunAttemptCount()));
            } catch (Exception ignored) {}
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
