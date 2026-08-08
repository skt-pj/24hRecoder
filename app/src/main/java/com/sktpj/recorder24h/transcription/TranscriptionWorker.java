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
        if (TranscriptionRepository.exists(context, segmentId)) {
            deleteAudio(context, segmentId, audioFile);
            return Result.success();
        }
        if (!audioFile.isFile()) {
            log(context, "TRANSCRIPTION_SOURCE_MISSING", segmentId, audioFile, null, null);
            SegmentRepository.append(context, segmentId, audioFile, 0L, System.currentTimeMillis(),
                    "FAILED", "SOURCE_AUDIO_MISSING");
            return Result.failure();
        }
        if (!WhisperModelManager.isReady(context)) {
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODEL", segmentId, audioFile, null, null);
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "READY", "LOCAL_MODEL_MISSING");
            return Result.failure();
        }

        long startedAt = System.currentTimeMillis();
        SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), startedAt,
                "TRANSCRIBING", null);
        log(context, "LOCAL_TRANSCRIPTION_STARTED", segmentId, audioFile, null, null);

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
            metrics.put("textChars", response.text.length());
            log(context, "LOCAL_TRANSCRIPTION_SAVED", segmentId, audioFile, null, metrics);
            deleteAudio(context, segmentId, audioFile);
            return Result.success();
        } catch (OutOfMemoryError oom) {
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "FAILED", "LOCAL_TRANSCRIPTION_OOM");
            log(context, "LOCAL_TRANSCRIPTION_OOM", segmentId, audioFile,
                    oom.getClass().getSimpleName(), null);
            return Result.failure();
        } catch (Exception error) {
            boolean retry = getRunAttemptCount() + 1 < MAX_ATTEMPTS;
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), retry ? "RETRY_WAIT" : "FAILED",
                    retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED");
            log(context, retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED",
                    segmentId, audioFile,
                    error.getClass().getSimpleName() + ": " + safeMessage(error), null);
            return retry ? Result.retry() : Result.failure();
        }
    }

    private static void deleteAudio(Context context, String segmentId, File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            return;
        }
        long size = audioFile.length();
        long modified = audioFile.lastModified();
        if (audioFile.delete()) {
            SegmentRepository.append(context, segmentId, audioFile, modified,
                    System.currentTimeMillis(), "DELETED", "TRANSCRIPT_DURABLY_SAVED");
            log(context, "TRANSCRIBED_AUDIO_DELETED", segmentId, audioFile,
                    "deletedBytes=" + size, null);
        } else {
            log(context, "TRANSCRIBED_AUDIO_DELETE_FAILED", segmentId, audioFile, null, null);
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
                            String message, JSONObject extra) {
        try {
            JSONObject details = extra == null ? new JSONObject() : extra;
            details.put("segmentId", segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.ENGINE_ID);
            details.put("attempt", 1);
            if (message != null) {
                details.put("message", message.length() > 500 ? message.substring(0, 500) : message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
