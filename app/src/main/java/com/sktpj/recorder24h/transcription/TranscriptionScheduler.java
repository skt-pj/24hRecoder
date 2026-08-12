package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TranscriptionScheduler {
    public static final String EXTRA_SEGMENT_ID = "segmentId";
    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_FORCE_RETRANSCRIBE = "forceRetranscribe";
    static final String ACTION_SEGMENT_READY = "com.sktpj.recorder24h.action.SEGMENT_READY";

    private static final String DRAIN_WORK_NAME = "transcription-drain-single-runner";
    private static final String PREFS = "transcription_scheduler";
    private static final String KEY_SINGLE_RUNNER_MIGRATED = "single_runner_migrated_v1";
    private static final String KEY_QUEUE_PAUSED = "queue_paused";
    private static final ExecutorService RECOVERY_EXECUTOR = Executors.newSingleThreadExecutor();

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

    public static boolean isQueuePaused(Context context) {
        Context app = context.getApplicationContext();
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_QUEUE_PAUSED, false);
    }

    /**
     * Explicit user-controlled pause for the persisted transcription backlog.
     * A currently running item is not cancelled; the drain stops before starting the next item.
     */
    public static void setQueuePaused(Context context, boolean paused) {
        Context app = context.getApplicationContext();
        boolean before = isQueuePaused(app);
        if (before == paused) {
            if (!paused) ensureDrainScheduled(app);
            return;
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_QUEUE_PAUSED, paused)
                .commit();
        long cancellationGeneration = paused ? TranscriptionCancellation.cancelCurrent() : -1L;
        try {
            JSONObject details = new JSONObject();
            details.put("paused", paused);
            details.put("queuedItemsRetained", true);
            details.put("runningItemCancelledOnPause", paused);
            details.put("cancellationGeneration", cancellationGeneration);
            AppLogger.event(app,
                    paused ? "TRANSCRIPTION_QUEUE_PAUSED_BY_USER"
                            : "TRANSCRIPTION_QUEUE_RESUMED_BY_USER",
                    details);
        } catch (Exception ignored) {
        }
        if (!paused) {
            ensureDrainScheduled(app);
        }
    }

    public static void enqueue(Context context, String segmentId, File file) {
        enqueueInternal(context, segmentId, file, false);
    }

    /**
     * Explicit user requests are persisted first and normally drained by the mediaProcessing FGS.
     * If Android refuses the FGS start, the same persisted queue is handled by the single
     * WorkManager drain worker; no per-segment Worker is created.
     */
    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return false;
        }
        TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
        String pipelineReason = TranscriptionPipelineSettings.unavailableReason(
                context, pipeline, WhisperModelManager.selectedModelId(context));
        if (pipelineReason != null) {
            if ("SILERO_VAD_MODEL_MISSING".equals(pipelineReason)
                    || "LOCAL_WHISPER_MODEL_MISSING".equals(pipelineReason)) {
                WhisperModelManager.enqueueDownload(context);
            }
            log(context, "TRANSCRIPTION_SELECTED_PIPELINE_NOT_READY", segmentId, file, pipelineReason);
            return false;
        }

        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(uniqueWorkName(segmentId));
        SegmentRepository.appendWithoutNotify(context, segmentId, file, 0L,
                System.currentTimeMillis(), "QUEUED", "MANUAL_DIRECT_QUEUE_ENQUEUED");
        log(context, "MANUAL_RETRANSCRIPTION_DIRECT_ENQUEUED", segmentId, file, null);

        if (isQueuePaused(context)) {
            log(context, "MANUAL_RETRANSCRIPTION_QUEUED_PAUSED", segmentId, file,
                    "QUEUED_ITEM_RETAINED_UNTIL_QUEUE_RESUME");
            return true;
        }

        if (TranscriptionQueueService.kick(context)) {
            return true;
        }

        log(context, "MANUAL_RETRANSCRIPTION_DIRECT_FALLBACK_WORKMANAGER", segmentId, file, null);
        ensureDrainScheduled(context);
        return true;
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

    private static boolean enqueueInternal(Context context, String segmentId, File file,
                                           boolean forceRetranscribe) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) {
            return false;
        }
        String currentEngine = LocalWhisperEngine.engineId(context);
        if (!forceRetranscribe &&
                TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
            log(context, "TRANSCRIPT_CURRENT_ENGINE_AUDIO_RETAINED", segmentId, file, null);
            return false;
        }
        TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
        String pipelineReason = TranscriptionPipelineSettings.unavailableReason(
                context, pipeline, WhisperModelManager.selectedModelId(context));
        if (pipelineReason != null) {
            if ("SILERO_VAD_MODEL_MISSING".equals(pipelineReason)
                    || "LOCAL_WHISPER_MODEL_MISSING".equals(pipelineReason)) {
                WhisperModelManager.enqueueDownload(context);
            }
            log(context, "TRANSCRIPTION_SELECTED_PIPELINE_NOT_READY", segmentId, file, pipelineReason);
            return false;
        }

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
            log(context, replacingExistingTranscript
                            ? "LOCAL_RETRANSCRIPTION_ENQUEUED" : "LOCAL_TRANSCRIPTION_ENQUEUED",
                    segmentId, file, null);
        }
        ensureDrainScheduled(context);
        return true;
    }

    public static void ensureDrainScheduled(Context context) {
        Context app = context.getApplicationContext();
        if (isQueuePaused(app)) {
            return;
        }
        if (!hasQueuedWork(app)) {
            return;
        }
        OneTimeWorkRequest request = buildDrainRequest(true);
        WorkManager.getInstance(app).enqueueUniqueWork(
                DRAIN_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request);
    }

    static void appendDrainContinuationIfPending(Context context) {
        Context app = context.getApplicationContext();
        if (isQueuePaused(app) || !hasQueuedWork(app)) {
            return;
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
                DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildDrainRequest(true));
    }

    private static OneTimeWorkRequest buildDrainRequest(boolean batteryNotLow) {
        Constraints.Builder constraints = new Constraints.Builder();
        if (batteryNotLow) {
            constraints.setRequiresBatteryNotLow(true);
        }
        return new OneTimeWorkRequest.Builder(TranscriptionWorker.class)
                .setConstraints(constraints.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transcription")
                .addTag("transcription-drain")
                .build();
    }

    public static void recoverInterruptedAndEnsureDrainAsync(Context context) {
        Context app = context.getApplicationContext();
        RECOVERY_EXECUTOR.execute(() -> {
            boolean migrated = false;
            try {
                SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!prefs.getBoolean(KEY_SINGLE_RUNNER_MIGRATED, false)) {
                    WorkManager.getInstance(app)
                            .cancelAllWorkByTag("transcription")
                            .getResult()
                            .get(15, TimeUnit.SECONDS);
                    prefs.edit().putBoolean(KEY_SINGLE_RUNNER_MIGRATED, true).apply();
                    migrated = true;
                }

                int recovered = 0;
                List<SegmentRecord> records = SegmentHistoryRepository.load(app);
                for (SegmentRecord record : records) {
                    if (!"TRANSCRIBING".equals(record.getStatus()) || !record.getAudioAvailable()) {
                        continue;
                    }
                    String audioPath = record.getAudioPath();
                    File audioFile = audioPath == null ? null : new File(audioPath);
                    if (audioFile == null || !audioFile.isFile()) {
                        continue;
                    }
                    SegmentRepository.appendWithoutNotify(
                            app,
                            record.getSegmentId(),
                            audioFile,
                            audioFile.lastModified(),
                            System.currentTimeMillis(),
                            "QUEUED",
                            "RECOVERED_INTERRUPTED_TRANSCRIPTION");
                    recovered++;
                }

                JSONObject details = new JSONObject();
                details.put("legacyWorkCancelled", migrated);
                details.put("recoveredTranscribingCount", recovered);
                details.put("queuePaused", isQueuePaused(app));
                AppLogger.event(app, "TRANSCRIPTION_SINGLE_RUNNER_RECOVERY_COMPLETED", details);
            } catch (Exception error) {
                try {
                    JSONObject details = new JSONObject();
                    details.put("type", error.getClass().getSimpleName());
                    details.put("message", error.getMessage() == null ? "" : error.getMessage());
                    AppLogger.event(app, "TRANSCRIPTION_SINGLE_RUNNER_RECOVERY_FAILED", details);
                } catch (Exception ignored) {
                }
            } finally {
                ensureDrainScheduled(app);
            }
        });
    }

    private static boolean hasQueuedWork(Context context) {
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus()))
                    && record.getAudioAvailable()) {
                return true;
            }
        }
        return false;
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
        String currentEngine = LocalWhisperEngine.engineId(context);
        Map<String, String> currentStates = new HashMap<>();
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            currentStates.put(record.getSegmentId(), record.getStatus());
        }
        int count = 0;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId == null ||
                    TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
                continue;
            }
            String status = currentStates.get(segmentId);
            if ("QUEUED".equals(status)
                    || "RETRY_WAIT".equals(status)
                    || "TRANSCRIBING".equals(status)) {
                continue;
            }
            if (enqueueInternal(context, segmentId, file, false)) {
                count++;
            }
        }
        ensureDrainScheduled(context);
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
            if (enqueueInternal(context, segmentId, file, false)) {
                count++;
            }
        }
        ensureDrainScheduled(context);
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

    static void log(Context context, String event, String segmentId, File file, String message) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.engineId(context));
            details.put("modelId", WhisperModelManager.selectedModelId(context));
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            details.put("queuePaused", isQueuePaused(context));
            if (message != null) {
                details.put("message", message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
