package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single canonical transcription queue runner.
 *
 * Queue order is based on the original recording start timestamp, not WorkManager scheduling,
 * Java monitor acquisition order, file-system enumeration order, or a rewritten queue timestamp.
 * Only one runner (automatic WorkManager or visible foreground service) may drain at a time.
 */
final class TranscriptionQueueRunner {
    private static final ReentrantLock DISPATCH_LOCK = new ReentrantLock(true);
    private static final int MAX_ATTEMPTS = 3;

    interface Listener {
        boolean isStopped();
        void onStatus(String text);
    }

    static final class DrainResult {
        final boolean empty;
        final boolean stopped;
        final boolean runtimeLimitReached;
        final int processedCount;

        DrainResult(boolean empty, boolean stopped, boolean runtimeLimitReached, int processedCount) {
            this.empty = empty;
            this.stopped = stopped;
            this.runtimeLimitReached = runtimeLimitReached;
            this.processedCount = processedCount;
        }
    }

    private TranscriptionQueueRunner() {
    }

    static DrainResult drain(Context context, long maxRuntimeMs, Listener listener) {
        Context app = context.getApplicationContext();
        long drainStartedAt = System.currentTimeMillis();
        int processed = 0;
        boolean locked = false;
        try {
            while (!locked) {
                if (listener != null && listener.isStopped()) {
                    return new DrainResult(false, true, false, processed);
                }
                try {
                    locked = DISPATCH_LOCK.tryLock(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new DrainResult(false, true, false, processed);
                }
            }

            logDrain(app, "TRANSCRIPTION_FIFO_DRAIN_STARTED", processed, null);
            while (System.currentTimeMillis() - drainStartedAt < maxRuntimeMs) {
                if (listener != null && listener.isStopped()) {
                    logDrain(app, "TRANSCRIPTION_FIFO_DRAIN_STOPPED", processed, null);
                    return new DrainResult(false, true, false, processed);
                }

                QueueSelection selection = nextQueuedRecord(app);
                if (selection.record == null) {
                    logDrain(app, "TRANSCRIPTION_FIFO_DRAIN_EMPTY", processed, null);
                    return new DrainResult(true, false, false, processed);
                }

                SegmentRecord next = selection.record;
                String segmentId = next.getSegmentId();
                String audioPath = next.getAudioPath();
                if (audioPath == null || audioPath.isEmpty()) {
                    SegmentRepository.appendWithoutNotify(app, segmentId, null, 0L,
                            System.currentTimeMillis(), "FAILED", "SOURCE_AUDIO_MISSING");
                    continue;
                }

                JSONObject selected = new JSONObject();
                try {
                    selected.put("segmentId", segmentId);
                    selected.put("file", next.getFileName() == null ? JSONObject.NULL : next.getFileName());
                    selected.put("recordedStartedAtMs", next.getStartedAtMs());
                    selected.put("queueEnqueuedAtMs", next.getQueueEnqueuedAtMs());
                    selected.put("stateChangedAtMs", next.getStateChangedAtMs());
                    selected.put("fifoOrderAtMs", fifoOrderAtMs(next));
                    selected.put("queuedCount", selection.queuedCount);
                    selected.put("status", next.getStatus());
                    selected.put("reason", next.getReason() == null ? JSONObject.NULL : next.getReason());
                    selected.put("orderPolicy", "recorded-start-ascending");
                    AppLogger.event(app, "TRANSCRIPTION_FIFO_SELECTED", selected);
                } catch (Exception ignored) {
                }

                // Old APKs scheduled one unique WorkManager job per segment. Cancel any residual copy;
                // the canonical FIFO runner owns this segment now.
                WorkManager.getInstance(app).cancelUniqueWork(TranscriptionScheduler.uniqueWorkName(segmentId));

                boolean manual = next.getReason() != null && next.getReason().startsWith("MANUAL_");
                if (listener != null) {
                    listener.onStatus("文字起こし中: " + modelLabel(WhisperModelManager.selectedModelId(app)));
                }
                processOne(app, next, new File(audioPath), manual, listener);
                processed++;
                if (listener != null) {
                    listener.onStatus("文字起こしキューを処理中");
                }
            }

            logDrain(app, "TRANSCRIPTION_FIFO_RUNTIME_LIMIT_REACHED", processed, null);
            return new DrainResult(false, false, true, processed);
        } finally {
            if (locked) {
                DISPATCH_LOCK.unlock();
            }
        }
    }

    private static void processOne(Context context, SegmentRecord queuedRecord, File audioFile,
                                   boolean forceRetranscribe, Listener listener) {
        String segmentId = queuedRecord.getSegmentId();
        int generation = TranscriptionResetManager.currentGeneration(context);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (listener != null && listener.isStopped()) {
                return;
            }
            String selectedModelId = WhisperModelManager.selectedModelId(context);
            String selectedEngineId = LocalWhisperEngine.engineId(selectedModelId);

            if (!isStillQueued(context, segmentId)) {
                log(context, "TRANSCRIPTION_FIFO_ITEM_SKIPPED", queuedRecord, audioFile,
                        "ITEM_NO_LONGER_QUEUED", forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            }
            if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                log(context, "TRANSCRIPTION_FIFO_ITEM_SKIPPED", queuedRecord, audioFile,
                        "RESET_GENERATION_CHANGED", forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            }
            if (!audioFile.isFile()) {
                if (TranscriptionRepository.exists(context, segmentId)) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "TRANSCRIBED",
                            "SOURCE_AUDIO_MISSING_OLD_TRANSCRIPT_RETAINED");
                } else {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "FAILED", "SOURCE_AUDIO_MISSING");
                }
                log(context, "TRANSCRIPTION_FIFO_SOURCE_MISSING", queuedRecord, audioFile,
                        null, forceRetranscribe, attempt, null, selectedModelId, selectedEngineId);
                return;
            }
            if (!WhisperModelManager.isComparisonReady(context, selectedModelId)) {
                WhisperModelManager.enqueueModelDownload(context, selectedModelId);
                String reason = WhisperModelManager.isModelReady(context, selectedModelId)
                        ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "READY", reason);
                log(context, "TRANSCRIPTION_FIFO_MODELS_MISSING", queuedRecord, audioFile,
                        reason, forceRetranscribe, attempt, null, selectedModelId, selectedEngineId);
                return;
            }
            if (!forceRetranscribe &&
                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED", null);
                return;
            }

            try {
                synchronized (LocalWhisperEngine.class) {
                    if (listener != null && listener.isStopped()) {
                        return;
                    }
                    if (!isStillQueued(context, segmentId)) {
                        return;
                    }
                    // Re-check the current engine after waiting behind a non-queue LocalWhisper user.
                    if (!forceRetranscribe &&
                            TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
                        SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                                audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED", null);
                        return;
                    }

                    long startedAt = System.currentTimeMillis();
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), startedAt, "TRANSCRIBING",
                            forceRetranscribe ? "MANUAL_FIFO_TRANSCRIBING" : "LOCAL_FIFO_TRANSCRIBING");
                    JSONObject started = new JSONObject();
                    started.put("fifoOrderAtMs", fifoOrderAtMs(queuedRecord));
                    started.put("recordedStartedAtMs", queuedRecord.getStartedAtMs());
                    started.put("queueEnqueuedAtMs", queuedRecord.getQueueEnqueuedAtMs());
                    started.put("orderPolicy", "recorded-start-ascending");
                    log(context, "TRANSCRIPTION_FIFO_STARTED", queuedRecord, audioFile,
                            null, forceRetranscribe, attempt, started,
                            selectedModelId, selectedEngineId);

                    LocalWhisperEngine.Response response =
                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId);
                    JSONArray annotatedSegments = response.skippedNoSpeech
                            ? new JSONArray()
                            : SpeakerIdentifier.annotate(context, audioFile, response.segments);

                    if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                        SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                                System.currentTimeMillis(), "READY", null);
                        log(context, "TRANSCRIPTION_FIFO_RESULT_DISCARDED_AFTER_RESET", queuedRecord,
                                audioFile, null, forceRetranscribe, attempt, null,
                                selectedModelId, selectedEngineId);
                        return;
                    }

                    TranscriptionRepository.save(context, segmentId, audioFile,
                            selectedEngineId, response.text, annotatedSegments);
                    long finishedAt = System.currentTimeMillis();
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), finishedAt, "TRANSCRIBED",
                            response.skippedNoSpeech ? "NO_SPEECH_DETECTED" : null);

                    JSONObject metrics = new JSONObject();
                    metrics.put("processingMs", Math.max(0L, finishedAt - startedAt));
                    metrics.put("decodeMs", response.decodeMs);
                    metrics.put("preprocessMs", response.preprocessMs);
                    metrics.put("inferenceMs", response.inferenceMs);
                    metrics.put("modelLoadMs", response.modelLoadMs);
                    metrics.put("whisperFullMs", response.whisperFullMs);
                    metrics.put("modelId", response.modelId);
                    metrics.put("modelLabel", response.modelLabel);
                    metrics.put("modelBytes", response.modelBytes);
                    metrics.put("textChars", response.text.length());
                    metrics.put("segmentCount", response.segmentCount);
                    metrics.put("audioDurationMs", response.audioDurationMs);
                    metrics.put("vadInitMs", response.vadInitMs);
                    metrics.put("vadDetectMs", response.vadDetectMs);
                    metrics.put("vadInputMs", response.vadInputMs);
                    metrics.put("vadScope", response.vadScope);
                    metrics.put("vadSegmentCount", response.vadSegmentCount);
                    metrics.put("vadSpeechMs", response.vadSpeechMs);
                    metrics.put("speechChunkCount", response.speechChunkCount);
                    metrics.put("speechInputMs", response.speechInputMs);
                    metrics.put("skippedSilenceMs", response.skippedSilenceMs);
                    metrics.put("skippedNoSpeech", response.skippedNoSpeech);
                    metrics.put("originalTimelinePreserved", true);
                    metrics.put("recordedStartedAtMs", queuedRecord.getStartedAtMs());
                    metrics.put("queueEnqueuedAtMs", queuedRecord.getQueueEnqueuedAtMs());
                    metrics.put("orderPolicy", "recorded-start-ascending");
                    log(context, response.skippedNoSpeech
                                    ? "TRANSCRIPTION_FIFO_SKIPPED_NO_SPEECH"
                                    : "TRANSCRIPTION_FIFO_SAVED",
                            queuedRecord, audioFile, null, forceRetranscribe, attempt, metrics,
                            selectedModelId, selectedEngineId);
                    return;
                }
            } catch (OutOfMemoryError oom) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        "FAILED", "LOCAL_TRANSCRIPTION_OOM");
                log(context, "TRANSCRIPTION_FIFO_OOM", queuedRecord, audioFile,
                        oom.getClass().getSimpleName(), forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            } catch (Exception error) {
                boolean retry = attempt < MAX_ATTEMPTS;
                String reason = forceRetranscribe
                        ? (retry ? "MANUAL_FIFO_RETRY" : "MANUAL_FIFO_FAILED")
                        : (retry ? "LOCAL_FIFO_RETRY" : "LOCAL_FIFO_FAILED");
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        retry ? "RETRY_WAIT" : "FAILED", reason);
                log(context, retry ? "TRANSCRIPTION_FIFO_RETRY" : "TRANSCRIPTION_FIFO_FAILED",
                        queuedRecord, audioFile,
                        error.getClass().getSimpleName() + ": " + safeMessage(error),
                        forceRetranscribe, attempt, null, selectedModelId, selectedEngineId);
                if (!retry || !sleepBackoff(attempt, listener)) {
                    return;
                }
            }
        }
    }

    private static QueueSelection nextQueuedRecord(Context context) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        List<SegmentRecord> queued = new ArrayList<>();
        for (SegmentRecord record : records) {
            if (("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus()))
                    && record.getAudioAvailable()) {
                queued.add(record);
            }
        }
        queued.sort(Comparator
                .comparingLong(TranscriptionQueueRunner::fifoOrderAtMs)
                .thenComparingLong(record -> {
                    long queuedAt = record.getQueueEnqueuedAtMs();
                    return queuedAt > 0L ? queuedAt : Long.MAX_VALUE;
                })
                .thenComparing(SegmentRecord::getSegmentId));
        return new QueueSelection(queued.isEmpty() ? null : queued.get(0), queued.size());
    }

    private static long fifoOrderAtMs(SegmentRecord record) {
        if (record == null) return Long.MAX_VALUE;
        if (record.getStartedAtMs() > 0L) return record.getStartedAtMs();
        if (record.getSortTimeMs() > 0L) return record.getSortTimeMs();
        if (record.getQueueEnqueuedAtMs() > 0L) return record.getQueueEnqueuedAtMs();
        if (record.getStateChangedAtMs() > 0L) return record.getStateChangedAtMs();
        return Long.MAX_VALUE;
    }

    private static boolean isStillQueued(Context context, String segmentId) {
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (segmentId.equals(record.getSegmentId())) {
                return "QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus());
            }
        }
        return false;
    }

    private static boolean sleepBackoff(int attempt, Listener listener) {
        long remaining = attempt == 1 ? 30_000L : 60_000L;
        while (remaining > 0L) {
            if (listener != null && listener.isStopped()) return false;
            long sleep = Math.min(1_000L, remaining);
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= sleep;
        }
        return true;
    }

    private static void logDrain(Context context, String event, int processed, String message) {
        try {
            JSONObject details = new JSONObject();
            details.put("processedCount", processed);
            details.put("orderPolicy", "recorded-start-ascending");
            if (message != null) details.put("message", message);
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }

    private static void log(Context context, String event, SegmentRecord queuedRecord, File file,
                            String message, boolean forceRetranscribe, int attempt, JSONObject extra,
                            String modelId, String engineId) {
        try {
            JSONObject details = extra == null ? new JSONObject() : extra;
            details.put("segmentId", queuedRecord.getSegmentId());
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", engineId);
            details.put("modelId", modelId);
            details.put("manualRetranscription", forceRetranscribe);
            details.put("attempt", attempt);
            details.put("asrReady", WhisperModelManager.isModelReady(context, modelId));
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            details.put("recordedStartedAtMs", queuedRecord.getStartedAtMs());
            details.put("queueEnqueuedAtMs", queuedRecord.getQueueEnqueuedAtMs());
            details.put("fifoOrderAtMs", fifoOrderAtMs(queuedRecord));
            details.put("orderPolicy", "recorded-start-ascending");
            if (message != null) details.put("message", message);
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }

    private static String modelLabel(String modelId) {
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        return spec == null ? modelId : spec.label;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static final class QueueSelection {
        final SegmentRecord record;
        final int queuedCount;

        QueueSelection(SegmentRecord record, int queuedCount) {
            this.record = record;
            this.queuedCount = queuedCount;
        }
    }
}
