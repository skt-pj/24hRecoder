package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

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

/**
 * Single background transcription lane.
 *
 * Unlike 0.7.10 and older, this Worker is not tied to a segment. Exactly one Worker owns the
 * execution gate, takes the highest-priority persisted QUEUED item, processes that one item, then
 * appends a successor Worker only when more queue items remain. This prevents dozens of
 * per-segment Workers from all sitting in WorkManager RUNNING while waiting on a JVM monitor.
 */
public final class TranscriptionWorker extends Worker {
    private static final int MAX_ATTEMPTS = 3;

    public TranscriptionWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (TranscriptionScheduler.isQueuePaused(context)) {
            AppLogger.event(context, "TRANSCRIPTION_DRAIN_WORKER_SKIPPED_QUEUE_PAUSED");
            return Result.success();
        }
        if (!TranscriptionExecutionGate.tryAcquire()) {
            AppLogger.event(context, "TRANSCRIPTION_DRAIN_WORKER_EXITED_RUNNER_BUSY");
            return Result.success();
        }

        try {
            if (TranscriptionScheduler.isQueuePaused(context)) {
                AppLogger.event(context, "TRANSCRIPTION_DRAIN_WORKER_HELD_QUEUE_PAUSED");
                return Result.success();
            }
            SegmentRecord next = nextQueuedRecord(context);
            if (next == null) {
                AppLogger.event(context, "TRANSCRIPTION_DRAIN_WORKER_EMPTY");
                return Result.success();
            }
            processOne(context, next);
            return Result.success();
        } finally {
            TranscriptionExecutionGate.release();
            TranscriptionScheduler.appendDrainContinuationIfPending(context);
        }
    }

    private void processOne(Context context, SegmentRecord record) {
        String segmentId = record.getSegmentId();
        String audioPath = record.getAudioPath();
        File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
        boolean forceRetranscribe = record.getReason() != null && record.getReason().startsWith("MANUAL_");
        int generation = TranscriptionResetManager.currentGeneration(context);

        if (audioFile == null || !audioFile.isFile()) {
            boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);
            SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                    System.currentTimeMillis(), hasTranscript ? "TRANSCRIBED" : "FAILED",
                    hasTranscript ? "SOURCE_AUDIO_MISSING_OLD_TRANSCRIPT_RETAINED" : "SOURCE_AUDIO_MISSING");
            log(context, "TRANSCRIPTION_DRAIN_SOURCE_MISSING", segmentId, audioFile, null,
                    forceRetranscribe, 1, null);
            return;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (isStopped()) {
                log(context, "TRANSCRIPTION_DRAIN_STOPPED_BEFORE_START", segmentId, audioFile,
                        null, forceRetranscribe, attempt, null);
                return;
            }
            if (!isStillQueued(context, segmentId)) {
                log(context, "TRANSCRIPTION_DRAIN_ITEM_SKIPPED", segmentId, audioFile,
                        "ITEM_NO_LONGER_QUEUED", forceRetranscribe, attempt, null);
                return;
            }
            if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                log(context, "TRANSCRIPTION_DRAIN_ITEM_SKIPPED", segmentId, audioFile,
                        "RESET_GENERATION_CHANGED", forceRetranscribe, attempt, null);
                return;
            }

            String selectedModelId = WhisperModelManager.selectedModelId(context);
            TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
            String selectedEngineId = LocalWhisperEngine.engineId(context, selectedModelId, pipeline);
            boolean replacingOldTranscript = TranscriptionRepository.exists(context, segmentId);

            if (!forceRetranscribe &&
                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED", null);
                log(context, "TRANSCRIPT_CURRENT_ENGINE_AFTER_QUEUE", segmentId, audioFile,
                        null, false, attempt, null);
                return;
            }
            String pipelineReason = TranscriptionPipelineSettings.unavailableReason(
                    context, pipeline, selectedModelId);
            if (pipelineReason != null) {
                boolean modelWait = "SILERO_VAD_MODEL_MISSING".equals(pipelineReason)
                        || "LOCAL_WHISPER_MODEL_MISSING".equals(pipelineReason);
                if (modelWait) WhisperModelManager.enqueueModelDownload(context, selectedModelId);
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        modelWait ? "READY" : "FAILED", pipelineReason);
                log(context, "TRANSCRIPTION_SELECTED_PIPELINE_NOT_READY", segmentId, audioFile,
                        pipelineReason, forceRetranscribe, attempt, null);
                return;
            }

            SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                    audioFile.lastModified(), System.currentTimeMillis(), "QUEUED",
                    forceRetranscribe ? "MANUAL_SINGLE_RUNNER_SLOT_WAIT" : "LOCAL_SINGLE_RUNNER_SLOT_WAIT");

            try {
                // The execution gate already gives one logical runner. Keep this native-engine lock
                // as a second line of defense against any future non-queue caller.
                synchronized (LocalWhisperEngine.class) {
                    if (isStopped() || !isStillQueued(context, segmentId)) {
                        log(context, "TRANSCRIPTION_DRAIN_ITEM_SKIPPED", segmentId, audioFile,
                                "REMOVED_OR_STOPPED_BEFORE_INFERENCE", forceRetranscribe, attempt, null);
                        return;
                    }

                    long cancellationToken = TranscriptionCancellation.snapshot();
                    if (TranscriptionScheduler.isQueuePaused(context)) {
                        throw new IllegalStateException(TranscriptionCancellation.CANCELLED);
                    }
                    long startedAt = System.currentTimeMillis();
                    long queueWaitMs = Math.max(0L, startedAt - queueEnqueuedAt(record));
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), startedAt, "TRANSCRIBING",
                            forceRetranscribe ? "MANUAL_SINGLE_RUNNER_TRANSCRIBING"
                                    : replacingOldTranscript ? "LOCAL_SINGLE_RUNNER_RETRANSCRIBING"
                                    : "LOCAL_SINGLE_RUNNER_TRANSCRIBING");

                    JSONObject started = new JSONObject();
                    started.put("queueWaitMs", queueWaitMs);
                    started.put("replacingOldTranscript", replacingOldTranscript);
                    started.put("runner", "workmanager-single-drain");
                    log(context, forceRetranscribe ? "MANUAL_RETRANSCRIPTION_STARTED"
                                    : replacingOldTranscript ? "LOCAL_RETRANSCRIPTION_STARTED"
                                    : "LOCAL_TRANSCRIPTION_STARTED",
                            segmentId, audioFile, null, forceRetranscribe, attempt, started);

                    LocalWhisperEngine.Response response =
                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline, cancellationToken);
                    TranscriptionCancellation.throwIfCancelled(cancellationToken);
                    JSONArray annotatedSegments = response.skippedNoSpeech
                            ? new JSONArray()
                            : TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU.equals(pipeline.speakerBackend)
                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments, cancellationToken)
                                : new JSONArray(response.segments.toString());
                    TranscriptionCancellation.throwIfCancelled(cancellationToken);

                    synchronized (TranscriptionResetManager.class) {
                        if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                            SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                                    System.currentTimeMillis(), "READY", null);
                            log(context, "TRANSCRIPTION_RESULT_DISCARDED_AFTER_RESET", segmentId,
                                    audioFile, null, forceRetranscribe, attempt, null);
                            return;
                        }
                        TranscriptionRepository.save(context, segmentId, audioFile,
                                selectedEngineId, response.text, annotatedSegments);
                        SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                                System.currentTimeMillis(), "TRANSCRIBED",
                                response.skippedNoSpeech ? "NO_SPEECH_DETECTED" : null);
                    }

                    JSONObject metrics = new JSONObject();
                    metrics.put("sampleCount", response.sampleCount);
                    metrics.put("threads", response.threads);
                    metrics.put("decodeMs", response.decodeMs);
                    metrics.put("preprocessMs", response.preprocessMs);
                    metrics.put("inferenceMs", response.inferenceMs);
                    metrics.put("modelLoadMs", response.modelLoadMs);
                    metrics.put("whisperFullMs", response.whisperFullMs);
                    metrics.put("modelId", response.modelId);
                    metrics.put("modelLabel", response.modelLabel);
                    metrics.put("modelBytes", response.modelBytes);
                    metrics.put("queueWaitMs", queueWaitMs);
                    metrics.put("textChars", response.text.length());
                    metrics.put("inputRms", response.inputRms);
                    metrics.put("inputPeak", response.inputPeak);
                    metrics.put("inputClippedFraction", response.inputClippedFraction);
                    metrics.put("audioRms", response.rms);
                    metrics.put("audioPeak", response.peak);
                    metrics.put("clippedFraction", response.clippedFraction);
                    metrics.put("dcOffset", response.dcOffset);
                    metrics.put("estimatedNoiseRms", response.estimatedNoiseRms);
                    metrics.put("estimatedSpeechRms", response.estimatedSpeechRms);
                    metrics.put("snrProxyDb", response.snrProxyDb);
                    metrics.put("appliedGainDb", response.appliedGainDb);
                    metrics.put("activeFrameFraction", response.activeFrameFraction);
                    metrics.put("limitedSampleFraction", response.limitedSampleFraction);
                    metrics.put("boostSuppressedForLowSnr", response.boostSuppressedForLowSnr);
                    metrics.put("audioFrontend", "adaptive-gain-v1");
                    metrics.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
                    metrics.put("vadEnabled", true);
                    metrics.put("vadBeforeWhisper", true);
                    metrics.put("vadInitMs", response.vadInitMs);
                    metrics.put("vadDetectMs", response.vadDetectMs);
                    metrics.put("vadSegmentCount", response.vadSegmentCount);
                    metrics.put("vadSpeechMs", response.vadSpeechMs);
                    metrics.put("speechChunkCount", response.speechChunkCount);
                    metrics.put("speechInputMs", response.speechInputMs);
                    metrics.put("audioDurationMs", response.audioDurationMs);
                    metrics.put("skippedSilenceMs", response.skippedSilenceMs);
                    metrics.put("skippedNoSpeech", response.skippedNoSpeech);
                    metrics.put("whisperInvoked", !response.skippedNoSpeech);
                    metrics.put("replacedOldTranscript", replacingOldTranscript);
                    metrics.put("audioRetained", true);
                    metrics.put("runner", "workmanager-single-drain");
                    metrics.put("asrBackend", pipeline.asrBackend);
                    metrics.put("vadBackend", pipeline.vadBackend);
                    metrics.put("denoiseBackend", pipeline.denoiseBackend);
                    metrics.put("speakerBackend", pipeline.speakerBackend);
                    metrics.put("automaticFallback", false);

                    String completedEvent;
                    if (response.skippedNoSpeech) {
                        completedEvent = forceRetranscribe
                                ? "MANUAL_RETRANSCRIPTION_SKIPPED_NO_SPEECH"
                                : replacingOldTranscript
                                ? "LOCAL_RETRANSCRIPTION_SKIPPED_NO_SPEECH"
                                : "LOCAL_TRANSCRIPTION_SKIPPED_NO_SPEECH";
                    } else {
                        completedEvent = forceRetranscribe
                                ? "MANUAL_RETRANSCRIPTION_SAVED"
                                : replacingOldTranscript
                                ? "LOCAL_RETRANSCRIPTION_SAVED"
                                : "LOCAL_TRANSCRIPTION_SAVED";
                    }
                    log(context, completedEvent, segmentId, audioFile, null,
                            forceRetranscribe, attempt, metrics);
                    return;
                }
            } catch (OutOfMemoryError oom) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "FAILED", "LOCAL_TRANSCRIPTION_OOM");
                log(context, forceRetranscribe ? "MANUAL_RETRANSCRIPTION_OOM" : "LOCAL_TRANSCRIPTION_OOM",
                        segmentId, audioFile, oom.getClass().getSimpleName(),
                        forceRetranscribe, attempt, null);
                return;
            } catch (Exception error) {
                if (TranscriptionCancellation.isCancellation(error)) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), System.currentTimeMillis(), "QUEUED",
                            "USER_PAUSED_RUNNING_TRANSCRIPTION");
                    log(context, "TRANSCRIPTION_RUNNING_ITEM_CANCELLED_BY_USER", segmentId, audioFile,
                            TranscriptionCancellation.CANCELLED, forceRetranscribe, attempt, null);
                    return;
                }
                boolean retry = attempt < MAX_ATTEMPTS;
                String reason = forceRetranscribe
                        ? (retry ? "MANUAL_SINGLE_RUNNER_RETRY" : "MANUAL_SINGLE_RUNNER_FAILED")
                        : (retry ? "LOCAL_SINGLE_RUNNER_RETRY" : "LOCAL_SINGLE_RUNNER_FAILED");
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        retry ? "RETRY_WAIT" : "FAILED", reason);
                log(context, retry ? "TRANSCRIPTION_SINGLE_RUNNER_RETRY"
                                : "TRANSCRIPTION_SINGLE_RUNNER_FAILED",
                        segmentId, audioFile,
                        error.getClass().getSimpleName() + ": " + safeMessage(error),
                        forceRetranscribe, attempt, null);
                if (!retry || !sleepBackoff(attempt)) {
                    return;
                }
            }
        }
    }

    private SegmentRecord nextQueuedRecord(Context context) {
        List<SegmentRecord> queued = new ArrayList<>();
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus()))
                    && record.getAudioAvailable()) {
                queued.add(record);
            }
        }
        queued.sort(Comparator.comparingLong(this::queueEnqueuedAt));
        return queued.isEmpty() ? null : queued.get(0);
    }

    private long queueEnqueuedAt(SegmentRecord record) {
        long queuedAt = record.getQueueEnqueuedAtMs();
        return queuedAt > 0L ? queuedAt : record.getStateChangedAtMs();
    }

    private boolean isStillQueued(Context context, String segmentId) {
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (segmentId.equals(record.getSegmentId())) {
                return "QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus());
            }
        }
        return false;
    }

    private boolean sleepBackoff(int attempt) {
        long delayMs = attempt == 1 ? 30_000L : 60_000L;
        try {
            Thread.sleep(delayMs);
            return !isStopped();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
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
                            String message, boolean forceRetranscribe, int attempt, JSONObject extra) {
        try {
            JSONObject details = extra == null ? new JSONObject() : extra;
            details.put("segmentId", segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", LocalWhisperEngine.engineId(context));
            details.put("modelId", WhisperModelManager.selectedModelId(context));
            details.put("manualRetranscription", forceRetranscribe);
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
