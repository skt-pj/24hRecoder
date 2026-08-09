package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

public final class TranscriptionScheduler {
    public static final String EXTRA_SEGMENT_ID = "segmentId";
    public static final String EXTRA_FILE_PATH = "filePath";
    static final String ACTION_SEGMENT_READY = "com.sktpj.recorder24h.action.SEGMENT_READY";

    private TranscriptionScheduler() {
    }

    public static void notifySegmentReady(Context context, String segmentId, File file) {
        if (segmentId == null || file == null) {
            return;
        }
        Intent intent = new Intent(context, SegmentReadyReceiver.class)
                .setAction(ACTION_SEGMENT_READY)
                .putExtra(EXTRA_SEGMENT_ID, segmentId)
                .putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        context.sendBroadcast(intent);
    }

    public static void enqueue(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return;
        }
        if (TranscriptionRepository.exists(context, segmentId)) {
            log(context, "TRANSCRIPT_ALREADY_SAVED_AUDIO_RETAINED", segmentId, file, null);
            return;
        }
        if (!WhisperModelManager.isReady(context)) {
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODEL", segmentId, file, null);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        Data input = new Data.Builder()
                .putString(EXTRA_SEGMENT_ID, segmentId)
                .putString(EXTRA_FILE_PATH, file.getAbsolutePath())
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TranscriptionWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transcription")
                .addTag("segment:" + segmentId)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                uniqueWorkName(segmentId),
                ExistingWorkPolicy.KEEP,
                request);
        log(context, "LOCAL_TRANSCRIPTION_ENQUEUED", segmentId, file, null);
    }

    public static int enqueueExisting(Context context) {
        if (!WhisperModelManager.isReady(context)) {
            return 0;
        }
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId == null || TranscriptionRepository.exists(context, segmentId)) {
                continue;
            }
            enqueue(context, segmentId, file);
            count++;
        }
        return count;
    }

    public static int pendingAudioCount(Context context) {
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) {
            return 0;
        }
        int pending = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId != null && !TranscriptionRepository.exists(context, segmentId)) {
                pending++;
            }
        }
        return pending;
    }

    public static String extractSegmentId(String fileName) {
        if (fileName == null || !fileName.endsWith(".m4a")) {
            return null;
        }
        int suffix = fileName.length() - ".m4a".length();
        int underscore = fileName.lastIndexOf('_', suffix - 1);
        if (underscore < 0 || underscore + 1 >= suffix) {
            return null;
        }
        return fileName.substring(underscore + 1, suffix);
    }

    private static String uniqueWorkName(String segmentId) {
        return "transcribe:" + segmentId;
    }

    static void log(Context context, String event, String segmentId, File file, String message) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.ENGINE_ID);
            if (message != null) {
                details.put("message", message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
