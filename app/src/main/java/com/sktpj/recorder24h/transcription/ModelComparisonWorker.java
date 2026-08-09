package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ModelComparisonWorker extends Worker {
    private static final long LAST_VAD_TOLERANCE_MS = 1_500L;

    public ModelComparisonWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String segmentId = getInputData().getString(ModelComparisonScheduler.EXTRA_SEGMENT_ID);
        String filePath = getInputData().getString(ModelComparisonScheduler.EXTRA_FILE_PATH);
        String modelCsv = getInputData().getString(ModelComparisonScheduler.EXTRA_MODEL_IDS);
        if (segmentId == null || filePath == null || modelCsv == null) {
            return Result.failure();
        }
        File audioFile = new File(filePath);
        if (!audioFile.isFile()) {
            return Result.failure();
        }
        String[] modelIds = parseModels(modelCsv);
        if (modelIds.length == 0) {
            return Result.failure();
        }

        // A five-minute recording compared with larger models can legitimately take longer than
        // a normal WorkManager Worker window. This comparison is explicitly user-requested, so use
        // WorkManager's long-running foreground path with the mediaProcessing service type.
        try {
            setForegroundAsync(TranscriptionForegroundInfo.comparison(
                    context, segmentId, "選択した音声認識モデルを比較しています"))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception foregroundError) {
            try {
                JSONObject d = new JSONObject();
                d.put("segmentId", segmentId);
                d.put("error", foregroundError.getClass().getSimpleName());
                d.put("message", foregroundError.getMessage() == null
                        ? JSONObject.NULL : foregroundError.getMessage());
                AppLogger.event(context, "MODEL_COMPARISON_FOREGROUND_FAILED", d);
            } catch (Exception ignored) {
            }
            return Result.failure();
        }

        JSONObject root = new JSONObject();
        JSONArray results = new JSONArray();
        long startedAt = System.currentTimeMillis();
        try {
            root.put("schemaVersion", 3);
            root.put("status", "RUNNING");
            root.put("segmentId", segmentId);
            root.put("audioFile", audioFile.getName());
            root.put("startedAtMs", startedAt);
            root.put("defaultModelId", WhisperModelManager.MODEL_DEFAULT);
            JSONArray requested = new JSONArray();
            for (String id : modelIds) requested.put(id);
            root.put("requestedModels", requested);
            root.put("results", results);
            ModelComparisonRepository.save(context, segmentId, root);
        } catch (Exception e) {
            return Result.failure();
        }

        boolean anySuccess = false;
        boolean allSuccess = true;
        try {
            synchronized (LocalWhisperEngine.class) {
                if (isStopped()) return Result.failure();
                LocalWhisperEngine.PreparedAudio prepared = LocalWhisperEngine.prepareAudio(audioFile);
                root.put("audioDurationMs", prepared.durationMs());
                root.put("decodeMs", prepared.decodeMs);
                root.put("preprocessMs", prepared.preprocessMs);
                AudioPreprocessor.Result front = prepared.frontEnd;
                root.put("inputRms", front.input.rms);
                root.put("inputPeak", front.input.peak);
                root.put("outputRms", front.output.rms);
                root.put("outputPeak", front.output.peak);
                root.put("estimatedNoiseRms", front.estimatedNoiseRms);
                root.put("estimatedSpeechRms", front.estimatedSpeechRms);
                root.put("snrProxyDb", front.snrProxyDb);
                root.put("appliedGainDb", front.appliedGainDb);
                root.put("activeFrameFraction", front.activeFrameFraction);
                root.put("limitedSampleFraction", front.limitedSampleFraction);
                root.put("boostSuppressedForLowSnr", front.boostSuppressedForLowSnr);

                // Run Silero once independently from Whisper. This is the ground truth for what the
                // VAD stage saw, so a model that stops early can be distinguished from a VAD miss.
                LocalWhisperEngine.VadDiagnostics vad = LocalWhisperEngine.analyzeVad(context, prepared);
                root.put("vadDiagnostics", vad.toJson());
                root.put("vadSegmentCount", vad.segmentCount);
                root.put("vadSpeechMs", vad.totalSpeechMs);
                root.put("vadLastEndMs", vad.lastEndMs);
                root.put("vadDetectMs", vad.vadDetectMs);
                ModelComparisonRepository.save(context, segmentId, root);

                JSONObject vadLog = new JSONObject();
                vadLog.put("segmentId", segmentId);
                vadLog.put("segmentCount", vad.segmentCount);
                vadLog.put("totalSpeechMs", vad.totalSpeechMs);
                vadLog.put("lastEndMs", vad.lastEndMs);
                vadLog.put("vadInitMs", vad.vadInitMs);
                vadLog.put("vadDetectMs", vad.vadDetectMs);
                vadLog.put("meanSpeechProbability", vad.meanSpeechProbability);
                vadLog.put("maxSpeechProbability", vad.maxSpeechProbability);
                vadLog.put("aboveThresholdFraction", vad.aboveThresholdFraction);
                vadLog.put("segments", vad.segments);
                AppLogger.event(context, "MODEL_COMPARISON_VAD_ANALYZED", vadLog);

                for (String modelId : modelIds) {
                    if (isStopped()) {
                        allSuccess = false;
                        break;
                    }
                    WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
                    JSONObject row = new JSONObject();
                    row.put("modelId", modelId);
                    row.put("modelLabel", spec == null ? modelId : spec.label);
                    row.put("status", "RUNNING");
                    row.put("startedAtMs", System.currentTimeMillis());
                    results.put(row);
                    ModelComparisonRepository.save(context, segmentId, root);

                    if (spec == null || !WhisperModelManager.isComparisonReady(context, modelId)) {
                        row.put("status", "MODEL_MISSING");
                        row.put("error", "MODEL_NOT_READY");
                        allSuccess = false;
                        ModelComparisonRepository.save(context, segmentId, root);
                        continue;
                    }

                    try {
                        LocalWhisperEngine.Response response = LocalWhisperEngine.transcribePrepared(
                                context, prepared, modelId);
                        long audioDurationMs = Math.max(1L, prepared.durationMs());
                        long referenceTotalMs = response.decodeMs + response.preprocessMs + response.inferenceMs;
                        double rtf = response.inferenceMs / (double) audioDurationMs;
                        long lastVadGapMs = Math.max(0L, vad.lastEndMs - response.lastOutputEndMs);
                        boolean reachesLastVad = vad.segmentCount == 0
                                || response.lastOutputEndMs + LAST_VAD_TOLERANCE_MS >= vad.lastEndMs;

                        row.put("status", "COMPLETED");
                        row.put("finishedAtMs", System.currentTimeMillis());
                        row.put("modelBytes", response.modelBytes);
                        row.put("threads", response.threads);
                        row.put("decodeMs", response.decodeMs);
                        row.put("preprocessMs", response.preprocessMs);
                        row.put("inferenceMs", response.inferenceMs);
                        row.put("modelLoadMs", response.modelLoadMs);
                        row.put("whisperFullMs", response.whisperFullMs);
                        row.put("referenceEndToEndMs", referenceTotalMs);
                        row.put("realTimeFactor", rtf);
                        row.put("textChars", response.text.length());
                        row.put("segmentCount", response.segmentCount);
                        // Kept for backward compatibility. This is the sum of Whisper output segment
                        // spans, not the independently measured VAD speech duration.
                        row.put("recognizedSpeechMs", response.recognizedSpeechMs);
                        row.put("outputSegmentDurationMs", response.recognizedSpeechMs);
                        row.put("lastOutputEndMs", response.lastOutputEndMs);
                        row.put("lastVadEndMs", vad.lastEndMs);
                        row.put("lastVadGapMs", lastVadGapMs);
                        row.put("reachesLastVad", reachesLastVad);
                        if (!reachesLastVad) {
                            row.put("warning", "OUTPUT_STOPS_BEFORE_LAST_VAD");
                        }
                        row.put("text", response.text);
                        row.put("segments", response.segments);
                        anySuccess = true;

                        JSONObject log = new JSONObject();
                        log.put("segmentId", segmentId);
                        log.put("modelId", modelId);
                        log.put("modelLabel", response.modelLabel);
                        log.put("inferenceMs", response.inferenceMs);
                        log.put("modelLoadMs", response.modelLoadMs);
                        log.put("whisperFullMs", response.whisperFullMs);
                        log.put("rtf", rtf);
                        log.put("segmentCount", response.segmentCount);
                        log.put("outputSegmentDurationMs", response.recognizedSpeechMs);
                        log.put("lastOutputEndMs", response.lastOutputEndMs);
                        log.put("lastVadEndMs", vad.lastEndMs);
                        log.put("lastVadGapMs", lastVadGapMs);
                        log.put("reachesLastVad", reachesLastVad);
                        log.put("textChars", response.text.length());
                        log.put("appliedGainDb", front.appliedGainDb);
                        log.put("snrProxyDb", front.snrProxyDb);
                        log.put("decoderSegments", decoderDiagnostics(response.segments));
                        AppLogger.event(context, "MODEL_COMPARISON_MODEL_COMPLETED", log);

                        if (WhisperModelManager.MODEL_KOTOBA_V2_Q5.equals(modelId)) {
                            JSONObject kotoba = new JSONObject();
                            kotoba.put("segmentId", segmentId);
                            kotoba.put("modelId", modelId);
                            kotoba.put("modelBytes", response.modelBytes);
                            kotoba.put("modelLoadMs", response.modelLoadMs);
                            kotoba.put("whisperFullMs", response.whisperFullMs);
                            kotoba.put("inferenceMs", response.inferenceMs);
                            kotoba.put("vadSegmentCount", vad.segmentCount);
                            kotoba.put("vadSpeechMs", vad.totalSpeechMs);
                            kotoba.put("vadLastEndMs", vad.lastEndMs);
                            kotoba.put("vadSegments", vad.segments);
                            kotoba.put("outputSegmentCount", response.segmentCount);
                            kotoba.put("outputSegmentDurationMs", response.recognizedSpeechMs);
                            kotoba.put("lastOutputEndMs", response.lastOutputEndMs);
                            kotoba.put("lastVadGapMs", lastVadGapMs);
                            kotoba.put("reachesLastVad", reachesLastVad);
                            kotoba.put("textChars", response.text.length());
                            kotoba.put("decoderSegments", decoderDiagnostics(response.segments));
                            AppLogger.event(context, "KOTOBA_LONGFORM_DIAGNOSTICS", kotoba);
                        }
                    } catch (Throwable error) {
                        row.put("status", "FAILED");
                        row.put("finishedAtMs", System.currentTimeMillis());
                        row.put("error", error.getClass().getSimpleName());
                        row.put("message", safe(error.getMessage()));
                        allSuccess = false;
                        JSONObject log = new JSONObject();
                        log.put("segmentId", segmentId);
                        log.put("modelId", modelId);
                        log.put("error", error.getClass().getSimpleName());
                        log.put("message", safe(error.getMessage()));
                        AppLogger.event(context, "MODEL_COMPARISON_MODEL_FAILED", log);
                    }
                    ModelComparisonRepository.save(context, segmentId, root);
                }
            }

            root.put("finishedAtMs", System.currentTimeMillis());
            root.put("elapsedMs", System.currentTimeMillis() - startedAt);
            root.put("status", allSuccess ? "COMPLETED" : anySuccess ? "PARTIAL" : "FAILED");
            ModelComparisonRepository.save(context, segmentId, root);
            JSONObject finished = new JSONObject();
            finished.put("segmentId", segmentId);
            finished.put("status", root.optString("status"));
            finished.put("elapsedMs", root.optLong("elapsedMs"));
            finished.put("modelCount", results.length());
            finished.put("vadSegmentCount", root.optInt("vadSegmentCount"));
            finished.put("vadLastEndMs", root.optLong("vadLastEndMs"));
            AppLogger.event(context, "MODEL_COMPARISON_FINISHED", finished);
            return anySuccess ? Result.success() : Result.failure();
        } catch (Throwable error) {
            try {
                root.put("finishedAtMs", System.currentTimeMillis());
                root.put("status", "FAILED");
                root.put("error", error.getClass().getSimpleName());
                root.put("message", safe(error.getMessage()));
                ModelComparisonRepository.save(context, segmentId, root);
                JSONObject log = new JSONObject();
                log.put("segmentId", segmentId);
                log.put("error", error.getClass().getSimpleName());
                log.put("message", safe(error.getMessage()));
                AppLogger.event(context, "MODEL_COMPARISON_FAILED", log);
            } catch (Exception ignored) {
            }
            return Result.failure();
        }
    }

    private static JSONArray decoderDiagnostics(JSONArray segments) {
        JSONArray out = new JSONArray();
        if (segments == null) return out;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject source = segments.optJSONObject(i);
            if (source == null) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("startMs", source.optLong("startMs"));
                row.put("endMs", source.optLong("endMs"));
                row.put("durationMs", source.optLong("durationMs"));
                row.put("tokenCount", source.optInt("tokenCount"));
                row.put("avgTokenProbability", source.optDouble("avgTokenProbability"));
                row.put("minTokenProbability", source.optDouble("minTokenProbability"));
                row.put("noSpeechProbability", source.optDouble("noSpeechProbability"));
                row.put("textChars", source.optString("text", "").length());
                out.put(row);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String[] parseModels(String csv) {
        String[] raw = csv.split(",");
        List<String> valid = new ArrayList<>();
        for (String value : raw) {
            String id = value.trim();
            if (!id.isEmpty() && WhisperModelManager.modelSpec(id) != null && !valid.contains(id)) {
                valid.add(id);
            }
        }
        return valid.toArray(new String[0]);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
