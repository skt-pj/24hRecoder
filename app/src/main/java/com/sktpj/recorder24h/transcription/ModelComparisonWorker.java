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

        // A five-minute recording compared with small and Kotoba can legitimately take longer than
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
            root.put("schemaVersion", 1);
            root.put("status", "RUNNING");
            root.put("segmentId", segmentId);
            root.put("audioFile", audioFile.getName());
            root.put("startedAtMs", startedAt);
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
                ModelComparisonRepository.save(context, segmentId, root);

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
                        row.put("status", "COMPLETED");
                        row.put("finishedAtMs", System.currentTimeMillis());
                        row.put("modelBytes", response.modelBytes);
                        row.put("threads", response.threads);
                        row.put("decodeMs", response.decodeMs);
                        row.put("preprocessMs", response.preprocessMs);
                        row.put("inferenceMs", response.inferenceMs);
                        row.put("referenceEndToEndMs", referenceTotalMs);
                        row.put("realTimeFactor", rtf);
                        row.put("textChars", response.text.length());
                        row.put("segmentCount", response.segmentCount);
                        row.put("recognizedSpeechMs", response.recognizedSpeechMs);
                        row.put("text", response.text);
                        row.put("segments", response.segments);
                        anySuccess = true;

                        JSONObject log = new JSONObject();
                        log.put("segmentId", segmentId);
                        log.put("modelId", modelId);
                        log.put("modelLabel", response.modelLabel);
                        log.put("inferenceMs", response.inferenceMs);
                        log.put("rtf", rtf);
                        log.put("segmentCount", response.segmentCount);
                        log.put("recognizedSpeechMs", response.recognizedSpeechMs);
                        log.put("textChars", response.text.length());
                        log.put("appliedGainDb", front.appliedGainDb);
                        log.put("snrProxyDb", front.snrProxyDb);
                        AppLogger.event(context, "MODEL_COMPARISON_MODEL_COMPLETED", log);
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
