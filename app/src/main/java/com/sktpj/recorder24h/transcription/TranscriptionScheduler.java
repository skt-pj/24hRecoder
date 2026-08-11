package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.Intent;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class TranscriptionScheduler {
    public static final String EXTRA_SEGMENT_ID = "segmentId";
    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_FORCE_RETRANSCRIBE = "forceRetranscribe";
    static final String ACTION_SEGMENT_READY = "com.sktpj.recorder24h.action.SEGMENT_READY";

    private static final String FIFO_DISPATCH_WORK = "transcription:fifo-dispatcher";

    private TranscriptionScheduler() {
    }

    public static void notifySegmentReady(Context context, String segmentId, File file) {
        if (segmentId == null || file == null) return;
        Intent intent = new Intent(context, SegmentReadyReceiver.class)
                .setAction(ACTION_SEGMENT_READY)
                .putExtra(EXTRA_SEGMENT_ID, segmentId)
                .putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        context.sendBroadcast(intent);
    }

    /** Queue normal automatic transcription. Actual execution is owned by the FIFO dispatcher. */
    public static void enqueue(Context context, String segmentId, File file) {
        enqueueInternal(context, segmentId, file, false, false);
    }

    /**
     * Explicit retranscription uses the visible foreground service when possible, but that service
     * drains the exact same FIFO as WorkManager. A manual request therefore cannot create a second
     * Whisper queue or bypass an older queued recording.
     */
    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) return false;
        if (completeRealtimeSilenceIfPossible(context, segmentId, file, true)) return true;
        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, file, reason);
            return false;
        }

        cancelLegacySegmentWorkers(context);
        markQueuedIfNeeded(context, segmentId, file, true, false);
        log(context, "MANUAL_RETRANSCRIPTION_FIFO_ENQUEUED", segmentId, file, null);

        if (TranscriptionQueueService.kick(context)) return true;

        log(context, "MANUAL_RETRANSCRIPTION_FIFO_FALLBACK_WORKMANAGER", segmentId, file, null);
        scheduleDispatcher(context, true);
        return true;
    }

    public static boolean removeFromQueue(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty()) return false;
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
        return enqueueInternal(context, segmentId, file, false, true);
    }

    private static boolean enqueueInternal(Context context, String segmentId, File file,
                                           boolean forceRetranscribe,
                                           boolean recoverTranscribing) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) return false;

        String currentEngine = LocalWhisperEngine.engineId(context);
        if (!forceRetranscribe &&
                TranscriptionRepository.isCurrentEngine(context, segmentId, currentEngine)) {
            log(context, "TRANSCRIPT_CURRENT_ENGINE_AUDIO_RETAINED", segmentId, file, null);
            return false;
        }

        if (completeRealtimeSilenceIfPossible(context, segmentId, file, forceRetranscribe)) return true;

        if (!WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            String reason = WhisperModelManager.isAsrReady(context)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, file, reason);
            return false;
        }

        cancelLegacySegmentWorkers(context);
        markQueuedIfNeeded(context, segmentId, file, forceRetranscribe, recoverTranscribing);
        scheduleDispatcher(context, forceRetranscribe);
        return true;
    }

    /**
     * Writes QUEUED only when the segment is not already waiting. This is the important migration
     * fix for 0.6.13 and earlier: reopening the app no longer rewrites queue age for every retained
     * recording. Processing order itself uses recorded startedAtMs, but preserving queue age keeps
     * diagnostics/UI truthful as well.
     */
    private static void markQueuedIfNeeded(Context context, String segmentId, File file,
                                           boolean forceRetranscribe,
                                           boolean recoverTranscribing) {
        SegmentRecord current = findRecord(context, segmentId);
        if (current != null) {
            String status = current.getStatus();
            if ("QUEUED".equals(status) || "RETRY_WAIT".equals(status)) {
                if (forceRetranscribe &&
                        (current.getReason() == null || !current.getReason().startsWith("MANUAL_"))) {
                    // Change only the queue mode. This reason deliberately does not end in
                    // WORK_ENQUEUED, so SegmentHistoryRepository keeps the original queue time.
                    SegmentRepository.appendWithoutNotify(context, segmentId, file, 0L,
                            System.currentTimeMillis(), "QUEUED", "MANUAL_FIFO_QUEUE_REQUESTED");
                }
                return;
            }
            if ("TRANSCRIBING".equals(status) && !recoverTranscribing) {
                // An active FIFO/legacy execution will either finish or be redirected. Do not turn
                // an in-flight item back into QUEUED just because enqueueExisting runs again.
                return;
            }
        }

        String reason;
        if (forceRetranscribe) {
            reason = "MANUAL_FIFO_QUEUE_REQUESTED";
        } else if (current != null && "TRANSCRIBING".equals(current.getStatus())) {
            reason = "LOCAL_FIFO_RECOVERED_STALE_TRANSCRIBING";
        } else if (TranscriptionRepository.exists(context, segmentId)) {
            reason = "LOCAL_RETRANSCRIPTION_FIFO_WORK_ENQUEUED";
        } else {
            reason = "LOCAL_TRANSCRIPTION_FIFO_WORK_ENQUEUED";
        }
        SegmentRepository.appendWithoutNotify(context, segmentId, file, 0L,
                System.currentTimeMillis(), "QUEUED", reason);
        log(context, forceRetranscribe
                        ? "MANUAL_RETRANSCRIPTION_FIFO_ENQUEUED"
                        : "LOCAL_TRANSCRIPTION_FIFO_ENQUEUED",
                segmentId, file, reason);
    }

    /**
     * WorkManager is now only a wake-up mechanism. Every request appends a dispatcher wake-up to a
     * single unique chain; no request contains a segment to execute. This avoids a lost-wakeup race
     * when a new recording arrives exactly as the current dispatcher observes an empty queue.
     */
    private static void scheduleDispatcher(Context context, boolean expedited) {
        Constraints.Builder constraints = new Constraints.Builder();
        if (!expedited) constraints.setRequiresBatteryNotLow(true);

        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(TranscriptionDispatchWorker.class)
                .setConstraints(constraints.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("transcription")
                .addTag("transcription-dispatcher");
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        }

        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                FIFO_DISPATCH_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                builder.build());
    }

    /** Stop per-segment workers persisted by older APKs before they can race the FIFO runner. */
    static void cancelLegacySegmentWorkers(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelAllWorkByTag("automatic-transcription");
        manager.cancelAllWorkByTag("manual-retranscription");
    }

    public static int enqueueExisting(Context context) {
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return 0;

        cancelLegacySegmentWorkers(context);
        boolean modelsReady = WhisperModelManager.isReady(context);
        if (!modelsReady) WhisperModelManager.enqueueDownload(context);

        Map<String, Long> recordedStarts = recordedStartMap(context);
        Arrays.sort(files, (left, right) -> Long.compare(
                orderAt(left, recordedStarts), orderAt(right, recordedStarts)));

        String currentEngine = LocalWhisperEngine.engineId(context);
        int count = 0;
        boolean queuedAny = false;
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
            if (!modelsReady) continue;

            // enqueueExisting is also the recovery point after process/package replacement. A stale
            // TRANSCRIBING row from the old per-segment architecture is safely returned to FIFO;
            // if a legacy native call is still finishing, the runner waits on LocalWhisperEngine
            // and re-checks the current transcript before doing duplicate work.
            markQueuedIfNeeded(context, segmentId, file, false, true);
            queuedAny = true;
            count++;
        }
        if (queuedAny) scheduleDispatcher(context, false);
        return count;
    }

    public static int enqueueExistingAfterReset(Context context) {
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return 0;

        cancelLegacySegmentWorkers(context);
        boolean modelsReady = WhisperModelManager.isReady(context);
        if (!modelsReady) WhisperModelManager.enqueueDownload(context);

        Map<String, Long> recordedStarts = recordedStartMap(context);
        Arrays.sort(files, (left, right) -> Long.compare(
                orderAt(left, recordedStarts), orderAt(right, recordedStarts)));

        int count = 0;
        boolean queuedAny = false;
        for (File file : files) {
            String segmentId = extractSegmentId(file.getName());
            if (segmentId == null) continue;
            if (completeRealtimeSilenceIfPossible(context, segmentId, file, false)) {
                count++;
                continue;
            }
            if (!modelsReady) continue;
            markQueuedIfNeeded(context, segmentId, file, false, true);
            queuedAny = true;
            count++;
        }
        if (queuedAny) scheduleDispatcher(context, false);
        return count;
    }

    public static int pendingAudioCount(Context context) {
        File[] files = StoragePolicy.getAudioDir(context)
                .listFiles((dir, name) -> name.endsWith(".m4a"));
        if (files == null) return 0;
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

    private static Map<String, Long> recordedStartMap(Context context) {
        Map<String, Long> result = new HashMap<>();
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (record.getStartedAtMs() > 0L) {
                result.put(record.getSegmentId(), record.getStartedAtMs());
            }
        }
        return result;
    }

    private static long orderAt(File file, Map<String, Long> starts) {
        String segmentId = extractSegmentId(file.getName());
        Long started = segmentId == null ? null : starts.get(segmentId);
        if (started != null && started > 0L) return started;
        long modified = file.lastModified();
        return modified > 0L ? modified : Long.MAX_VALUE;
    }

    private static SegmentRecord findRecord(Context context, String segmentId) {
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (segmentId.equals(record.getSegmentId())) return record;
        }
        return null;
    }

    public static String extractSegmentId(String fileName) {
        if (fileName == null || !fileName.endsWith(".m4a")) return null;
        int suffix = fileName.length() - ".m4a".length();
        int underscore = fileName.lastIndexOf('_', suffix - 1);
        if (underscore < 0 || underscore + 1 >= suffix) return null;
        return fileName.substring(underscore + 1, suffix);
    }

    /** Legacy work name retained only so the FIFO runner can cancel persisted old requests. */
    static String uniqueWorkName(String segmentId) {
        return "transcribe:" + segmentId;
    }

    private static boolean completeRealtimeSilenceIfPossible(Context context,
                                                             String segmentId,
                                                             File file,
                                                             boolean forceRetranscribe) {
        RealtimeSpeechGateStore.Snapshot gate = RealtimeSpeechGateStore.read(context, segmentId);
        if (!gate.available || !gate.definiteSilence) return false;

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
            details.put("fifoByRecordedStart", true);
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
            details.put("queuePolicy", "recorded-start-ascending");
            if (message != null) details.put("message", message);
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }
}
