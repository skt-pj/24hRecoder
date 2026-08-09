package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;

public final class TranscriptionWorker extends Worker {
    private static final int MAX_ATTEMPTS = 3;

    public TranscriptionWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String segmentId = getInputData().getString(TranscriptionScheduler.EXTRA_SEGMENT_ID);
        String filePath = getInputData().getString(TranscriptionScheduler.EXTRA_FILE_PATH);
        if (segmentId == null || segmentId.isEmpty() || filePath == null || filePath.isEmpty()) {
            return Result.failure();
        }

        File audioFile = new File(filePath);
        int attempt = getRunAttemptCount() + 1;
        if (TranscriptionRepository.exists(context, segmentId)) {
            log(context, "TRANSCRIPT_ALREADY_SAVED_AUDIO_RETAINED", segmentId, audioFile, null, null, attempt);
            return Result.success();
        }
        if (!audioFile.isFile()) {
            log(context, "TRANSCRIPTION_SOURCE_MISSING", segmentId, audioFile, null, null, attempt);
            SegmentRepository.append(context, segmentId, audioFile, 0L, System.currentTimeMillis(),
                    "FAILED", "SOURCE_AUDIO_MISSING");
            return Result.failure();
        }
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, audioFile,
                    reason, null, attempt);
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "READY", reason);
            return Result.failure();
        }

        long queuedAt = System.currentTimeMillis();
        SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), queuedAt,
                "QUEUED", "LOCAL_TRANSCRIPTION_SLOT_WAIT");
        log(context, "LOCAL_TRANSCRIPTION_QUEUED", segmentId, audioFile, null, null, attempt);

        synchronized (LocalWhisperEngine.class) {
            if (isStopped()) {
                log(context, "LOCAL_TRANSCRIPTION_STOPPED_BEFORE_START", segmentId, audioFile,
                        null, null, attempt);
                return Result.failure();
            }
            if (TranscriptionRepository.exists(context, segmentId)) {
                log(context, "TRANSCRIPT_ALREADY_SAVED_AFTER_QUEUE", segmentId, audioFile,
                        null, null, attempt);
                return Result.success();
            }
            if (!audioFile.isFile()) {
                SegmentRepository.append(context, segmentId, audioFile, 0L, System.currentTimeMillis(),
                        "FAILED", "SOURCE_AUDIO_MISSING");
                log(context, "TRANSCRIPTION_SOURCE_MISSING_AFTER_QUEUE", segmentId, audioFile,
                        null, null, attempt);
                return Result.failure();
            }

            long startedAt = System.currentTimeMillis();
            long queueWaitMs = Math.max(0L, startedAt - queuedAt);
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), startedAt,
                    "TRANSCRIBING", null);
            JSONObject startedMetrics = new JSONObject();
            try {
                startedMetrics.put("queueWaitMs", queueWaitMs);
                startedMetrics.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
                startedMetrics.put("vadEnabled", true);
            } catch (Exception ignored) {
            }
            log(context, "LOCAL_TRANSCRIPTION_STARTED", segmentId, audioFile, null,
                    startedMetrics, attempt);

            try {
                LocalWhisperEngine.Response response = LocalWhisperEngine.transcribe(context, audioFile);
                TranscriptionRepository.save(context, segmentId, audioFile,
                        LocalWhisperEngine.ENGINE_ID, response.text);
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "TRANSCRIBED", null);

                JSONObject metrics = new JSONObject();
                metrics.put("sampleCount", response.sampleCount);
                metrics.put("threads", response.threads);
                metrics.put("decodeMs", response.decodeMs);
                metrics.put("inferenceMs", response.inferenceMs);
                metrics.put("queueWaitMs", queueWaitMs);
                metrics.put("textChars", response.text.length());
                metrics.put("audioRms", response.rms);
                metrics.put("audioPeak", response.peak);
                metrics.put("clippedFraction", response.clippedFraction);
                metrics.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
                metrics.put("vadEnabled", true);
                metrics.put("audioRetained", true);
                log(context, "LOCAL_TRANSCRIPTION_SAVED", segmentId, audioFile, null, metrics, attempt);
                return Result.success();
            } catch (OutOfMemoryError oom) {
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "FAILED", "LOCAL_TRANSCRIPTION_OOM");
                log(context, "LOCAL_TRANSCRIPTION_OOM", segmentId, audioFile,
                        oom.getClass().getSimpleName(), null, attempt);
                return Result.failure();
            } catch (Exception error) {
                boolean retry = getRunAttemptCount() + 1 < MAX_ATTEMPTS;
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), retry ? "RETRY_WAIT" : "FAILED",
                        retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED");
                log(context, retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED",
                        segmentId, audioFile,
                        error.getClass().getSimpleName() + ": " + safeMessage(error), null, attempt);
                return retry ? Result.retry() : Result.failure();
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static void log(Context context, String event, String segmentId, File file,
                            String message, JSONObject extra, int attempt) {
        try {
            JSONObject details = extra == null ? new JSONObject() : extra;
            details.put("segmentId", segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.ENGINE_ID);
            details.put("attempt", attempt);
            details.put("asrReady", WhisperModelManager.isAsrReady(context));
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            if (message != null) {
                details.put("message", message.length() > 500 ? message.substring(0, 500) : message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
