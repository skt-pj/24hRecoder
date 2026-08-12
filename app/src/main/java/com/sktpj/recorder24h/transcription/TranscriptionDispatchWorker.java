package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/** Single WorkManager entrypoint for all automatic transcription work. */
public final class TranscriptionDispatchWorker extends Worker {
    private static final long MAX_RUNTIME_MS = 5L * 60L * 60L * 1000L + 20L * 60L * 1000L;

    public TranscriptionDispatchWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            setForegroundAsync(TranscriptionForegroundInfo.queue(
                    context, "文字起こしキューを録音日時順に処理しています"))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception foregroundError) {
            try {
                JSONObject details = new JSONObject();
                details.put("error", foregroundError.getClass().getSimpleName());
                details.put("message", foregroundError.getMessage() == null
                        ? JSONObject.NULL : foregroundError.getMessage());
                AppLogger.event(context, "TRANSCRIPTION_FIFO_FOREGROUND_FAILED", details);
            } catch (Exception ignored) {
            }
            return Result.retry();
        }

        TranscriptionQueueRunner.DrainResult result = TranscriptionQueueRunner.drain(
                context,
                MAX_RUNTIME_MS,
                new TranscriptionQueueRunner.Listener() {
                    @Override
                    public boolean isStopped() {
                        return TranscriptionDispatchWorker.this.isStopped();
                    }

                    @Override
                    public void onStatus(String text) {
                        // WorkManager foreground notification is intentionally stable. Per-item details
                        // are emitted to TRANSCRIPTION_FIFO_* diagnostics instead of notification churn.
                    }
                });

        if (result.stopped) {
            return Result.success();
        }
        if (result.runtimeLimitReached) {
            return Result.retry();
        }
        return Result.success();
    }
}
