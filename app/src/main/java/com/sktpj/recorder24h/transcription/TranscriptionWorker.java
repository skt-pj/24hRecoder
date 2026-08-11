package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
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
        boolean forceRetranscribe = getInputData()
                .getBoolean(TranscriptionScheduler.EXTRA_FORCE_RETRANSCRIBE, false);
        int workGeneration = getInputData().getInt(TranscriptionResetManager.EXTRA_GENERATION, 0);
        if (!TranscriptionResetManager.isCurrentGeneration(context, workGeneration)) {
            return Result.success();
        }
        if (segmentId == null || segmentId.isEmpty() || filePath == null || filePath.isEmpty()) {
            return Result.failure();
        }

        String selectedModelId = WhisperModelManager.selectedModelId(context);
        String selectedEngineId = LocalWhisperEngine.engineId(selectedModelId);
        File audioFile = new File(filePath);
        int attempt = getRunAttemptCount() + 1;
        if (!forceRetranscribe &&
                TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
            log(context, "TRANSCRIPT_CURRENT_ENGINE_AUDIO_RETAINED", segmentId, audioFile,
                    null, forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
            return Result.success();
        }
        if (!WhisperModelManager.isComparisonReady(context, selectedModelId)) {
            WhisperModelManager.enqueueModelDownload(context, selectedModelId);
            String reason = WhisperModelManager.isModelReady(context, selectedModelId)
                    ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
            log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS", segmentId, audioFile,
                    reason, forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                    System.currentTimeMillis(), "READY", reason);
            return Result.failure();
        }

        boolean replacingOldTranscript = TranscriptionRepository.exists(context, segmentId);
        long queuedAt = System.currentTimeMillis();
        String queueReason = forceRetranscribe
                ? "MANUAL_RETRANSCRIPTION_SLOT_WAIT"
                : replacingOldTranscript ? "LOCAL_RETRANSCRIPTION_SLOT_WAIT" : "LOCAL_TRANSCRIPTION_SLOT_WAIT";
        SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), queuedAt,
                "QUEUED", queueReason);
        log(context,
                forceRetranscribe ? "MANUAL_RETRANSCRIPTION_QUEUED"
                        : replacingOldTranscript ? "LOCAL_RETRANSCRIPTION_QUEUED" : "LOCAL_TRANSCRIPTION_QUEUED",
                segmentId, audioFile, null,
                forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);

        synchronized (LocalWhisperEngine.class) {
            if (isStopped()) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                        System.currentTimeMillis(),
                        TranscriptionRepository.exists(context, segmentId) ? "TRANSCRIBED" : "READY",
                        "USER_REMOVED_FROM_TRANSCRIPTION_QUEUE");
                log(context, "LOCAL_TRANSCRIPTION_STOPPED_BEFORE_START", segmentId, audioFile,
                        "QUEUE_ITEM_REMOVED",
                        forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                return Result.failure();
            }
            if (!forceRetranscribe &&
                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
                log(context, "TRANSCRIPT_CURRENT_ENGINE_AFTER_QUEUE", segmentId, audioFile,
                        null, forceMetrics(false, selectedModelId, selectedEngineId), attempt);
                return Result.success();
            }
            if (!audioFile.isFile()) {
                if (TranscriptionRepository.exists(context, segmentId)) {
                    log(context, "RETRANSCRIPTION_SOURCE_MISSING_AFTER_QUEUE_OLD_TRANSCRIPT_RETAINED",
                            segmentId, audioFile, null,
                            forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                    return Result.success();
                }
                SegmentRepository.append(context, segmentId, audioFile, 0L, System.currentTimeMillis(),
                        "FAILED", "SOURCE_AUDIO_MISSING");
                log(context, "TRANSCRIPTION_SOURCE_MISSING_AFTER_QUEUE", segmentId, audioFile,
                        null, forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                return Result.failure();
            }
            if (!WhisperModelManager.isComparisonReady(context, selectedModelId)) {
                WhisperModelManager.enqueueModelDownload(context, selectedModelId);
                String reason = WhisperModelManager.isModelReady(context, selectedModelId)
                        ? "SILERO_VAD_MODEL_MISSING" : "LOCAL_MODEL_MISSING";
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "READY", reason);
                log(context, "TRANSCRIPTION_WAITING_FOR_LOCAL_MODELS_AFTER_QUEUE",
                        segmentId, audioFile, reason,
                        forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                return Result.failure();
            }

            long startedAt = System.currentTimeMillis();
            long queueWaitMs = Math.max(0L, startedAt - queuedAt);
            String transcribingReason = forceRetranscribe
                    ? "MANUAL_RETRANSCRIBING"
                    : replacingOldTranscript ? "RETRANSCRIBING_WITH_AUDIO_FRONTEND" : null;
            SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(), startedAt,
                    "TRANSCRIBING", transcribingReason);
            JSONObject startedMetrics = forceMetrics(
                    forceRetranscribe, selectedModelId, selectedEngineId);
            try {
                RealtimeSpeechGateStore.Snapshot gate = RealtimeSpeechGateStore.read(context, segmentId);
                startedMetrics.put("queueWaitMs", queueWaitMs);
                startedMetrics.put("vadModel", WhisperModelManager.VAD_MODEL_ID);
                startedMetrics.put("vadEnabled", true);
                startedMetrics.put("vadBeforeWhisper", true);
                startedMetrics.put("audioFrontend", "adaptive-gain-v1");
                startedMetrics.put("replacingOldTranscript", replacingOldTranscript);
                startedMetrics.put("realtimeGateAvailable", gate.available);
                startedMetrics.put("realtimeDefiniteSilence", gate.definiteSilence);
                startedMetrics.put("realtimeCandidateCount", gate.candidateCount);
                startedMetrics.put("realtimeCandidateMs", gate.candidateMs);
            } catch (Exception ignored) {
            }
            log(context,
                    forceRetranscribe ? "MANUAL_RETRANSCRIPTION_STARTED"
                            : replacingOldTranscript ? "LOCAL_RETRANSCRIPTION_STARTED" : "LOCAL_TRANSCRIPTION_STARTED",
                    segmentId, audioFile, null, startedMetrics, attempt);

            try {
                LocalWhisperEngine.Response response =
                        LocalWhisperEngine.transcribe(context, audioFile, selectedModelId);
                JSONArray annotatedSegments = response.skippedNoSpeech
                        ? new JSONArray()
                        : SpeakerIdentifier.annotate(context, audioFile, response.segments);
                synchronized (TranscriptionResetManager.class) {
                    if (!TranscriptionResetManager.isCurrentGeneration(context, workGeneration)) {
                        SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                                System.currentTimeMillis(), "READY", null);
                        log(context, "TRANSCRIPTION_RESULT_DISCARDED_AFTER_RESET", segmentId, audioFile,
                                null,
                                forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                        return Result.success();
                    }
                    TranscriptionRepository.save(context, segmentId, audioFile,
                            selectedEngineId, response.text, annotatedSegments);
                    SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                            System.currentTimeMillis(), "TRANSCRIBED",
                            response.skippedNoSpeech ? "NO_SPEECH_DETECTED" : null);
                }

                JSONObject metrics = forceMetrics(
                        forceRetranscribe, selectedModelId, selectedEngineId);
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
                metrics.put("vadInputMs", response.vadInputMs);
                metrics.put("vadScope", response.vadScope);
                metrics.put("vadSegmentCount", response.vadSegmentCount);
                metrics.put("vadSpeechMs", response.vadSpeechMs);
                metrics.put("speechChunkCount", response.speechChunkCount);
                metrics.put("whisperFullCallCount", response.speechChunkCount);
                metrics.put("speechInputMs", response.speechInputMs);
                metrics.put("audioDurationMs", response.audioDurationMs);
                metrics.put("skippedSilenceMs", response.skippedSilenceMs);
                metrics.put("skippedNoSpeech", response.skippedNoSpeech);
                metrics.put("whisperInvoked", !response.skippedNoSpeech);
                metrics.put("realtimeGateUsed", response.realtimeGateUsed);
                metrics.put("realtimeCandidateCount", response.realtimeCandidateCount);
                metrics.put("realtimeCandidateMs", response.realtimeCandidateMs);
                metrics.put("skippedByRealtimeGate", response.skippedByRealtimeGate);
                metrics.put("originalTimelinePreserved", true);
                metrics.put("replacedOldTranscript", replacingOldTranscript);
                metrics.put("audioRetained", true);

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
                log(context, completedEvent, segmentId, audioFile, null, metrics, attempt);
                return Result.success();
            } catch (OutOfMemoryError oom) {
                if (!TranscriptionResetManager.isCurrentGeneration(context, workGeneration)) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "READY", null);
                    return Result.success();
                }
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), "FAILED", "LOCAL_TRANSCRIPTION_OOM");
                log(context, forceRetranscribe ? "MANUAL_RETRANSCRIPTION_OOM" : "LOCAL_TRANSCRIPTION_OOM",
                        segmentId, audioFile, oom.getClass().getSimpleName(),
                        forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                return Result.failure();
            } catch (Exception error) {
                if (!TranscriptionResetManager.isCurrentGeneration(context, workGeneration)) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "READY", null);
                    return Result.success();
                }
                boolean retry = getRunAttemptCount() + 1 < MAX_ATTEMPTS;
                String stateReason = forceRetranscribe
                        ? (retry ? "MANUAL_RETRANSCRIPTION_RETRY" : "MANUAL_RETRANSCRIPTION_FAILED")
                        : (retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED");
                SegmentRepository.append(context, segmentId, audioFile, audioFile.lastModified(),
                        System.currentTimeMillis(), retry ? "RETRY_WAIT" : "FAILED", stateReason);
                String event;
                if (forceRetranscribe) {
                    event = retry ? "MANUAL_RETRANSCRIPTION_RETRY" : "MANUAL_RETRANSCRIPTION_FAILED";
                } else {
                    event = retry ? "LOCAL_TRANSCRIPTION_RETRY" : "LOCAL_TRANSCRIPTION_FAILED";
                }
                log(context, event, segmentId, audioFile,
                        error.getClass().getSimpleName() + ": " + safeMessage(error),
                        forceMetrics(forceRetranscribe, selectedModelId, selectedEngineId), attempt);
                return retry ? Result.retry() : Result.failure();
            }
        }
    }

    private static JSONObject forceMetrics(boolean forceRetranscribe,
                                           String modelId, String engineId) {
        JSONObject details = new JSONObject();
        try {
            details.put("manualRetranscription", forceRetranscribe);
            details.put("modelId", modelId);
            details.put("engine", engineId);
        } catch (Exception ignored) {
        }
        return details;
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
            if (!details.has("engine")) {
                details.put("engine", LocalWhisperEngine.engineId(context));
            }
            if (!details.has("modelId")) {
                details.put("modelId", WhisperModelManager.selectedModelId(context));
            }
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
