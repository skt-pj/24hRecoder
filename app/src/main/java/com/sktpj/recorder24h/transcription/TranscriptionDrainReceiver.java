package com.sktpj.recorder24h.transcription;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Route WorkManager scheduling out of :postprocess_asr into the main app process.
 * The ASR process deliberately does not initialize WorkManager.
 */
public final class TranscriptionDrainReceiver extends BroadcastReceiver {
    private static final String ACTION_ENSURE =
            "com.sktpj.recorder24h.action.ENSURE_TRANSCRIPTION_DRAIN";
    private static final String ACTION_APPEND =
            "com.sktpj.recorder24h.action.APPEND_TRANSCRIPTION_DRAIN";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    static boolean isMainProcess(Context context) {
        return DrainProcessPolicy.isMainProcess(Application.getProcessName(), context.getPackageName());
    }

    static void requestMainProcess(Context context, boolean append) {
        Context app = context.getApplicationContext();
        if (isMainProcess(app)) {
            if (append) {
                TranscriptionScheduler.appendDrainContinuationIfPending(app);
            } else {
                TranscriptionScheduler.ensureDrainScheduled(app);
            }
            return;
        }
        Intent intent = new Intent(app, TranscriptionDrainReceiver.class)
                .setAction(append ? ACTION_APPEND : ACTION_ENSURE);
        try {
            app.sendBroadcast(intent);
            JSONObject details = new JSONObject();
            details.put("action", append ? "APPEND" : "ENSURE");
            details.put("fromProcess", Application.getProcessName());
            AppLogger.event(app, "TRANSCRIPTION_DRAIN_ROUTED_TO_MAIN", details);
        } catch (Exception error) {
            try {
                JSONObject details = new JSONObject();
                details.put("type", error.getClass().getName());
                details.put("message", error.getMessage() == null ? "" : error.getMessage());
                AppLogger.event(app, "TRANSCRIPTION_DRAIN_MAIN_DISPATCH_FAILED", details);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        if (!isMainProcess(app)) {
            AppLogger.event(app, "TRANSCRIPTION_DRAIN_WRONG_PROCESS");
            return;
        }
        final boolean append = intent != null && ACTION_APPEND.equals(intent.getAction());
        final PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                if (append) {
                    TranscriptionScheduler.appendDrainContinuationIfPending(app);
                } else {
                    TranscriptionScheduler.ensureDrainScheduled(app);
                }
            } catch (Throwable failure) {
                try {
                    JSONObject details = new JSONObject();
                    details.put("type", failure.getClass().getName());
                    details.put("message", failure.getMessage() == null ? "" : failure.getMessage());
                    details.put("stackTrace", Log.getStackTraceString(failure));
                    AppLogger.event(app, "TRANSCRIPTION_MAIN_DRAIN_SCHEDULE_FAILED", details);
                } catch (Throwable ignored) {
                }
            } finally {
                pending.finish();
            }
        });
    }
}
