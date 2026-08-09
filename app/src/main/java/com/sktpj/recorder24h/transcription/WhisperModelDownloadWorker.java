package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;

public final class WhisperModelDownloadWorker extends Worker {
    public WhisperModelDownloadWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (WhisperModelManager.isReady(context)) {
            TranscriptionScheduler.enqueueExisting(context);
            return Result.success();
        }
        AppLogger.event(context, "WHISPER_MODEL_DOWNLOAD_STARTED");
        try {
            File model = WhisperModelManager.download(context);
            JSONObject details = new JSONObject();
            details.put("model", WhisperModelManager.MODEL_ID);
            details.put("asrBytes", model.length());
            details.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
            details.put("vadBytes", WhisperModelManager.vadModelFile(context).length());
            details.put("vadSha256Verified", WhisperModelManager.verifyVadModel(context));
            AppLogger.event(context, "WHISPER_MODEL_READY", details);
            TranscriptionScheduler.enqueueExisting(context);
            return Result.success();
        } catch (Exception e) {
            try {
                JSONObject details = new JSONObject();
                details.put("error", e.getClass().getSimpleName());
                details.put("message", e.getMessage() == null ? JSONObject.NULL : e.getMessage());
                AppLogger.event(context, "WHISPER_MODEL_DOWNLOAD_RETRY", details);
            } catch (Exception ignored) {
            }
            return Result.retry();
        }
    }
}
