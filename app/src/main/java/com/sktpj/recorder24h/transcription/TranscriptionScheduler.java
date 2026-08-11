package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
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

    /**
     * Explicit user requests do not wait behind WorkManager/JobScheduler in the normal path.
     * The request is persisted as QUEUED, any old scheduled copy is cancelled, and a
     * mediaProcessing foreground service drains the queue immediately while the app is visible.
     * WorkManager is only used as a fallback if Android refuses the direct FGS start.
     */
    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return false;
        }
        if (completeRealtimeSilenceIfPossible(context, segmentId, file, true)) {
            return true;
        }
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, file, reason);
            return false;
        }

        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(uniqueWorkName(segmentId));
        SegmentRepository.appendWithoutNotify(context, segmentId, file, 0L,
                System.currentTimeMillis(), "QUEUED", "MANUAL_DIRECT_QUEUE_ENQUEUED");
        log(context, "MANUAL_RETRANSCRIPTION_DIRECT_ENQUEUED", segmentId, file, null);

        if (TranscriptionQueueService.kick(context)) {
            return true;
        }

        log(context, "MANUAL_RETRANSCRIPTION_DIRECT_FALLBACK_WORKMANAGER", segmentId, file, null);
        return enqueueInternal(context, segmentId, file, true, ExistingWorkPolicy.REPLACE);
    }

    public static boolean removeFromQueue(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty()) {
            return false;
        }
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(uniqueWorkName(segmentId));
        boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);
        SegmentRepository.appendWithoutNotify(
                context,
                segmentId,
                file,
                0L,
                System.currentTimeMillis(),
                hasTranscript ? "TRANSCRIBED" : "READY",
                "USER_REMOVED_FROM_TRANSCRIPTION_QUEUE");
        log(context, "TRANSCRIPTION_QUEUE_ITEM_REMOVED", segmentId, file,
                hasTranscript ? "OLD_TRANSCRIPT_RETAINED" : "AUDIO_RETAINED");
        return true;
    }

    private static boolean enqueueAfterReset(Context context, String segmentId, File file) {
        return enqueueInternal(context, segmentId, file, false, ExistingWorkPolicy.REPLACE);
    }

    private static boolean enqueueInternal(Context context, String segmentId, File file,
                                           boolean forceRetranscribe, ExistingWorkPolicy workPolicy) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return false;
        }
        String currentEngine = LocalWhisperEngine.engineId(context);
        if (!forceRetranscribe &&
                TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
            log(context, "TRANSCRIPT_CURRENT_ENGINE_AUDIO_RETAINED", segmentId, file, null);
            return false;
        }

        // Definite silence is finalized before model checks and before WorkManager. It therefore
        // never waits for a Whisper slot and does not require either Whisper or Silero to be loaded.
        if (completeRealtimeSilenceIfPossible(context, segmentId, file, forceRetranscribe)) {
            return true;
        }

        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, file, reason);
            return false;
        }

        Constraints.Builder constraintBuilder = new Constraints.Builder();
        // Automatic background work yields to recording when Android considers the battery low.
        // Explicit user requests never inherit this constraint.
        if (!forceRetranscribe) {
            constraintBuilder.setRequiresBatteryNotLow(true);
        }
        Constraints constraints = constraintBuilder.build();

        Data input = new Data.Builder()
                .putString(EXTRA_SEGMENT_ID, segmentId)
                .putString(EXTRA_FILE_PATH, file.getAbsolutePath())
                .putBoolean(EXTRA_FORCE_RETRANSCRIBE, forceRetranscribe)
                .putInt(TranscriptionResetManager.EXTRA_GENERATION,
                        TranscriptionResetManager.currentGeneration(context))
                .build();
        OneTimeWorkRequest.Builder requestBuilder = new OneTimeWorkRequest.Builder(TranscriptionWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transcription")
                .addTag("segment:" + segmentId)
                .addTag(forceRetranscribe ? "manual-retranscription" : "automatic-transcription");
        if (forceRetranscribe) {
            requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        }
        OneTimeWorkRequest request = requestBuilder.build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                uniqueWorkName(segmentId),
                workPolicy,
                request);

        boolean replacingExistingTranscript = TranscriptionRepository.exists(context, segmentId);
        String queuedReason = forceRetranscribe
                ? "MANUAL_RETRANSCRIPTION_WORK_ENQUEUED"
                : replacingExistingTranscript
                    ? "LOCAL_RETRANSCRIPTION_WORK_ENQUEUED"
                    : "LOCAL_TRANSCRIPTION_WORK_ENQUEUED";
        SegmentRepository.appendWithoutNotify(context, segmentId, file, 0L,
                System.currentTimeMillis(), "QUEUED", queuedReason);

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
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) {
            return 0;
        }
        boolean modelsReady = WhisperModelManager.isReady(context);
        if (!modelsReady) {
            WhisperModelManager.enqueueDownload(context);
        }
        String currentEngine = LocalWhisperEngine.engineId(context);
        int count = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId == null ||
                    TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
                continue;
            }
            if (completeRealtimeSilenceIfPossible(context, segmentId, file, false)) {
                count++;
                continue;
            }
            if (!modelsReady) {
                continue;
            }
            enqueue(context, segmentId, file);
            count++;
        }
        return count;
    }

    public static int enqueueExistingAfterReset(Context context) {
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) {
            return 0;
        }
        boolean modelsReady = WhisperModelManager.isReady(context);
        if (!modelsReady) {
            WhisperModelManager.enqueueDownload(context);
        }
        int count = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId == null) {
                continue;
            }
            if (completeRealtimeSilenceIfPossible(context, segmentId, file, false)) {
                count++;
                continue;
            }
            if (!modelsReady) {
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
        String currentEngine = LocalWhisperEngine.engineId(context);
        int pending = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId != null &&
                    !TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
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

    static String uniqueWorkName(String segmentId) {
        return "transcribe:" + segmentId;
    }

    private static boolean completeRealtimeSilenceIfPossible(Context context,
                                                             String segmentId,
                                                             File file,
                                                             boolean forceRetranscribe) {
        RealtimeSpeechGateStore.Snapshot gate = RealtimeSpeechGateStore.read(context, segmentId);
        if (!gate.available || !gate.definiteSilence) {
            return false;
        }

        long startedAt = System.currentTimeMillis();
        String startReason = forceRetranscribe
                ? "MANUAL_REALTIME_SILENCE"
                : "LOCAL_REALTIME_SILENCE";
        SegmentRepository.appendWithoutNotify(context, segmentId, file, file.lastModified(),
                startedAt, "TRANSCRIBING", startReason);
        try {
            String engine = LocalWhisperEngine.engineId(context);
            TranscriptionRepository.save(context, segmentId, file, engine, "", new JSONArray());
            long finishedAt = System.currentTimeMillis();
            SegmentRepository.append(context, segmentId, file, file.lastModified(),
                    finishedAt, "TRANSCRIBED", "NO_SPEECH_DETECTED");

            JSONObject details = gate.toJson();
            details.put("segmentId", segmentId);
            details.put("file", file.getName());
            details.put("engine", engine);
            details.put("modelId", WhisperModelManager.selectedModelId(context));
            details.put("manualRetranscription", forceRetranscribe);
            details.put("processingMs", Math.max(0L, finishedAt - startedAt));
            details.put("decodeMs", 0L);
            details.put("preprocessMs", 0L);
            details.put("vadInitMs", 0L);
            details.put("vadDetectMs", 0L);
            details.put("vadInputMs", 0L);
            details.put("whisperInvoked", false);
            details.put("skippedNoSpeech", true);
            details.put("skippedByRealtimeGate", true);
            details.put("originalTimelinePreserved", true);
            AppLogger.event(context,
                    forceRetranscribe
                            ? "MANUAL_RETRANSCRIPTION_SKIPPED_REALTIME_SILENCE"
                            : "LOCAL_TRANSCRIPTION_SKIPPED_REALTIME_SILENCE",
                    details);
            return true;
        } catch (Exception error) {
            SegmentRepository.appendWithoutNotify(context, segmentId, file, file.lastModified(),
                    System.currentTimeMillis(), "READY", "REALTIME_SILENCE_FINALIZE_FAILED");
            try {
                JSONObject details = new JSONObject();
                details.put("segmentId", segmentId);
                details.put("file", file.getName());
                details.put("error", error.getClass().getSimpleName());
                details.put("message", error.getMessage() == null ? JSONObject.NULL : error.getMessage());
                AppLogger.event(context, "REALTIME_SILENCE_FINALIZE_FAILED", details);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    static void log(Context context, String event, String segmentId, File file, String message) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.engineId(context));
            details.put("modelId", WhisperModelManager.selectedModelId(context));
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            if (message != null) {
                details.put("message", message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
