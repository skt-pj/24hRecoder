package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public final class TranscriptionWorker extends Worker {
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
            log(context, "TRANSCRIPTION_SOURCE_MISSING", segmentId, audioFile, null, 0);
            SegmentRepository.append(context, segmentId, audioFile, 0L, System.currentTimeMillis(),
                    "FAILED", "SOURCE_AUDIO_MISSING");
            return Result.failure();
        }

        String apiKey = ApiKeyStore.load(context);
        if (apiKey == null) {
            log(context, "TRANSCRIPTION_API_KEY_MISSING", segmentId, audioFile, null, 0);
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "READY", "API_KEY_MISSING");
            return Result.failure();
        }

        long startedAt = System.currentTimeMillis();
        SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), startedAt,
                "TRANSCRIBING", null);
        log(context, "TRANSCRIPTION_STARTED", segmentId, audioFile, null, 0);

        try {
            OpenAiTranscriptionClient.Response response =
                    new OpenAiTranscriptionClient().transcribe(audioFile, apiKey);
            if (response.isSuccess()) {
                TranscriptionRepository.save(context, segmentId, audioFile,
                        OpenAiTranscriptionClient.MODEL, response.text);
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "TRANSCRIBED", null);
                log(context, "TRANSCRIPTION_SAVED", segmentId, audioFile, null, response.httpCode);
                deleteAudio(context, segmentId, audioFile);
                return Result.success();
            }

            String reason = "HTTP_" + response.httpCode;
            if (OpenAiTranscriptionClient.isRetryableHttpCode(response.httpCode)) {
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "RETRY_WAIT", reason);
                log(context, "TRANSCRIPTION_RETRY", segmentId, audioFile,
                        response.errorMessage, response.httpCode);
                return Result.retry();
            }

            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "FAILED", reason);
            log(context, "TRANSCRIPTION_FAILED", segmentId, audioFile,
                    response.errorMessage, response.httpCode);
            return Result.failure();
        } catch (IOException networkError) {
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "RETRY_WAIT", "NETWORK_OR_IO_ERROR");
            log(context, "TRANSCRIPTION_RETRY_IO", segmentId, audioFile,
                    networkError.getClass().getSimpleName(), 0);
            return Result.retry();
        } catch (Exception fatal) {
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "FAILED", "TRANSCRIPT_SAVE_OR_PROCESSING_ERROR");
            log(context, "TRANSCRIPTION_FATAL", segmentId, audioFile,
                    fatal.getClass().getSimpleName(), 0);
            return Result.failure();
        }
    }

    private static void deleteAudio(Context context, String segmentId, File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            return;
        }
        long size = audioFile.length();
        if (audioFile.delete()) {
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "DELETED", "TRANSCRIPT_DURABLY_SAVED");
            log(context, "TRANSCRIBED_AUDIO_DELETED", segmentId, audioFile,
                    "deletedBytes=" + size, 0);
        } else {
            log(context, "TRANSCRIBED_AUDIO_DELETE_FAILED", segmentId, audioFile, null, 0);
        }
    }

    private static void log(Context context, String event, String segmentId, File file,
                            String message, int httpCode) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            if (httpCode != 0) {
                details.put("httpCode", httpCode);
            }
            if (message != null) {
                details.put("message", message.length() > 500 ? message.substring(0, 500) : message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
