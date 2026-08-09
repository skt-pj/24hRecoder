package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

public final class ModelComparisonScheduler {
    public static final String EXTRA_SEGMENT_ID = "segmentId";
    public static final String EXTRA_FILE_PATH = "filePath";
    public static final String EXTRA_MODEL_IDS = "modelIds";

    private ModelComparisonScheduler() {
    }

    public static boolean enqueue(Context context, String segmentId, File audioFile, String[] modelIds) {
        if (segmentId == null || segmentId.isEmpty() || audioFile == null || !audioFile.isFile()
                || modelIds == null || modelIds.length == 0) {
            return false;
        }
        for (String modelId : modelIds) {
            if (!WhisperModelManager.isComparisonReady(context, modelId)) {
                return false;
            }
        }

        String joined = String.join(",", modelIds);
        Data data = new Data.Builder()
                .putString(EXTRA_SEGMENT_ID, segmentId)
                .putString(EXTRA_FILE_PATH, audioFile.getAbsolutePath())
                .putString(EXTRA_MODEL_IDS, joined)
                .build();
        Constraints constraints = new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelComparisonWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("model-comparison")
                .addTag("model-comparison:" + segmentId)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                "model-comparison:" + segmentId,
                ExistingWorkPolicy.REPLACE,
                request);
        try {
            JSONObject d = new JSONObject();
            d.put("segmentId", segmentId);
            d.put("file", audioFile.getName());
            JSONArray models = new JSONArray();
            for (String id : modelIds) models.put(id);
            d.put("models", models);
            AppLogger.event(context, "MODEL_COMPARISON_ENQUEUED", d);
        } catch (Exception ignored) {
        }
        return true;
    }
}
