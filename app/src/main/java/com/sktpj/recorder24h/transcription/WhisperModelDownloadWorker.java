package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

public final class WhisperModelDownloadWorker extends Worker {
    public WhisperModelDownloadWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String modelId = getInputData().getString(WhisperModelManager.EXTRA_MODEL_ID);
        if (modelId == null || modelId.isEmpty()) {
            modelId = WhisperModelManager.MODEL_BASE;
        }
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        if (spec == null) {
            return Result.failure();
        }

        if (WhisperModelManager.isComparisonReady(context, modelId)) {
            if (WhisperModelManager.MODEL_BASE.equals(modelId)) {
                TranscriptionScheduler.enqueueExisting(context);
            }
            return Result.success();
        }

        // small/Kotoba are explicitly requested comparison assets and can be ~500 MiB. Promote
        // those user-triggered transfers to WorkManager's foreground dataSync service so a slow
        // network is not constrained by the normal short Worker execution window. The standard
        // base/VAD bootstrap keeps the existing background behavior because it may be scheduled by
        // MY_PACKAGE_REPLACED without a visible user action.
        if (!WhisperModelManager.MODEL_BASE.equals(modelId)) {
            try {
                setForegroundAsync(TranscriptionForegroundInfo.modelDownload(context, spec))
                        .get(15, TimeUnit.SECONDS);
            } catch (Exception foregroundError) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("modelId", modelId);
                    d.put("error", foregroundError.getClass().getSimpleName());
                    d.put("message", foregroundError.getMessage() == null
                            ? JSONObject.NULL : foregroundError.getMessage());
                    AppLogger.event(context, "WHISPER_MODEL_FOREGROUND_FAILED", d);
                } catch (Exception ignored) {
                }
                return Result.failure();
            }
        }

        JSONObject started = new JSONObject();
        try {
            started.put("modelId", modelId);
            started.put("label", spec.label);
            started.put("expectedBytes", spec.expectedBytes);
        } catch (Exception ignored) {
        }
        AppLogger.event(context, "WHISPER_MODEL_DOWNLOAD_STARTED", started);

        try {
            // All models share the same VAD file. Serialize downloads inside this process to avoid
            // two Workers replacing the common .part/final files concurrently on a fresh install.
            File model;
            synchronized (WhisperModelManager.class) {
                model = WhisperModelManager.downloadModel(context, modelId);
            }
            JSONObject details = new JSONObject();
            details.put("modelId", modelId);
            details.put("label", spec.label);
            details.put("asrBytes", model.length());
            details.put("asrVerified", WhisperModelManager.verifyModel(context, modelId));
            details.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
            details.put("vadBytes", WhisperModelManager.vadModelFile(context).length());
            details.put("vadSha256Verified", WhisperModelManager.verifyVadModel(context));
            AppLogger.event(context, "WHISPER_MODEL_READY", details);
            if (WhisperModelManager.MODEL_BASE.equals(modelId)) {
                TranscriptionScheduler.enqueueExisting(context);
            }
            return Result.success();
        } catch (Exception e) {
            try {
                JSONObject details = new JSONObject();
                details.put("modelId", modelId);
                details.put("label", spec.label);
                details.put("error", e.getClass().getSimpleName());
                details.put("message", e.getMessage() == null ? JSONObject.NULL : e.getMessage());
                AppLogger.event(context, "WHISPER_MODEL_DOWNLOAD_RETRY", details);
            } catch (Exception ignored) {
            }
            return Result.retry();
        }
    }
}
