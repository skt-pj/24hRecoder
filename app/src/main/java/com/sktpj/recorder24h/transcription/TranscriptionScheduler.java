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
    public static final String EXTRA_FORCE_RETRANSCRIBE = "forceRetranscribe";
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
        enqueueInternal(context, segmentId, file, false, ExistingWorkPolicy.KEEP);
    }

    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        return enqueueInternal(context, segmentId, file, true, ExistingWorkPolicy.KEEP);
    }

    private static boolean enqueueAfterReset(Context context, String segmentId, File file) {
        return enqueueInternal(context, segmentId, file, false, ExistingWorkPolicy.REPLACE);
    }

    private static boolean enqueueInternal(Context context, String segmentId, File file,
                                           boolean forceRetranscribe, ExistingWorkPolicy workPolicy) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return false;
        }
        if (!forceRetranscribe &&
                TranscriptionRepository.isCurrentEngine(context, segmentId, LocalWhisperEngine.ENGINE_ID)) {
            log(context, "TRANSCRIPT_CURRENT_ENGINE_AUDIO_RETAINED", segmentId, file, null);
            return false;
        }
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, file, reason);
            return false;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        Data input = new Data.Builder()
                .putString(EXTRA_SEGMENT_ID, segmentId)
                .putString(EXTRA_FILE_PATH, file.getAbsolutePath())
                .putBoolean(EXTRA_FORCE_RETRANSCRIBE, forceRetranscribe)
                .putInt(TranscriptionResetManager.EXTRA_GENERATION,
                        TranscriptionResetManager.currentGeneration(context))
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TranscriptionWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transcription")
                .addTag("segment:" + segmentId)
                .addTag(forceRetranscribe ? "manual-retranscription" : "automatic-transcription")
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                forceRetranscribe ? forceWorkName(segmentId) : uniqueWorkName(segmentId),
                workPolicy,
                request);
        if (forceRetranscribe) {
            log(context, "MANUAL_RETRANSCRIPTION_ENQUEUED", segmentId, file, null);
        } else {
            log(context, TranscriptionRepository.exists(context, segmentId)
                            ? "LOCAL_RETRANSCRIPTION_ENQUEUED" : "LOCAL_TRANSCRIPTION_ENQUEUED",
                    segmentId, file, null);
        }
        return true;
    }

    public static int enqueueExisting(Context context) {
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
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
            if (segmentId == null ||
                    TranscriptionRepository.isCurrentEngine(context, segmentId, LocalWhisperEngine.ENGINE_ID)) {
                continue;
            }
            enqueue(context, segmentId, file);
            count++;
        }
        return count;
    }

    public static int enqueueExistingAfterReset(Context context) {
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
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
            if (segmentId == null) {
                continue;
            }
            if (enqueueAfterReset(context, segmentId, file)) {
                count++;
            }
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
            if (segmentId != null &&
                    !TranscriptionRepository.isCurrentEngine(context, segmentId, LocalWhisperEngine.ENGINE_ID)) {
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

    private static String forceWorkName(String segmentId) {
        return "manual-retranscribe:" + segmentId;
    }

    static void log(Context context, String event, String segmentId, File file, String message) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.ENGINE_ID);
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            if (message != null) {
                details.put("message", message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
