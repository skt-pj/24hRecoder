package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Persistent UI/session state for the fixed 30-second per-model benchmark. */
public final class Model30sBenchmarkStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "model_30s_benchmark_status.json";

    private Model30sBenchmarkStore() {}

    public static JSONObject read(Context context) {
        synchronized (LOCK) {
            try {
                File file = file(context);
                if (!file.isFile()) return empty();
                return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                return empty();
            }
        }
    }

    public static void start(Context context, int modelCount, String profile, String backendLabel,
                             String audioFile, String audioPath) {
        JSONObject row = empty();
        try {
            long now = System.currentTimeMillis();
            row.put("state", "RUNNING");
            row.put("startedAtMs", now);
            row.put("updatedAtMs", now);
            row.put("modelCount", modelCount);
            row.put("currentIndex", -1);
            row.put("currentModelId", JSONObject.NULL);
            row.put("profile", profile == null ? JSONObject.NULL : profile);
            row.put("backendLabel", backendLabel == null ? JSONObject.NULL : backendLabel);
            row.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile);
            row.put("audioPath", audioPath == null ? JSONObject.NULL : audioPath);
            row.put("durationMs", Model30sBenchmarkController.BENCHMARK_DURATION_MS);
            row.put("results", new JSONArray());
        } catch (Exception ignored) {}
        write(context, row);
    }

    public static void modelStarting(Context context, int index, String modelId, String modelLabel) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                row.put("state", "RUNNING");
                row.put("currentIndex", index);
                row.put("currentModelId", modelId);
                row.put("currentModelLabel", modelLabel);
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void modelResult(Context context, int index, String modelId, String modelLabel,
                                   JSONObject result) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                JSONArray results = row.optJSONArray("results");
                if (results == null) results = new JSONArray();
                JSONObject item = new JSONObject();
                item.put("index", index);
                item.put("modelId", modelId);
                item.put("modelLabel", modelLabel);
                item.put("finishedAtMs", System.currentTimeMillis());
                if (result != null) {
                    java.util.Iterator<String> keys = result.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        item.put(key, result.opt(key));
                    }
                }
                results.put(item);
                row.put("results", results);
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void complete(Context context) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                long now = System.currentTimeMillis();
                row.put("state", "COMPLETED");
                row.put("currentIndex", -1);
                row.put("currentModelId", JSONObject.NULL);
                row.put("finishedAtMs", now);
                row.put("updatedAtMs", now);
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void fail(Context context, String error) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                long now = System.currentTimeMillis();
                row.put("state", "FAILED");
                row.put("error", error == null ? "unknown" : error);
                row.put("finishedAtMs", now);
                row.put("updatedAtMs", now);
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    private static JSONObject empty() {
        JSONObject row = new JSONObject();
        try {
            row.put("schemaVersion", 1);
            row.put("state", "IDLE");
            row.put("durationMs", Model30sBenchmarkController.BENCHMARK_DURATION_MS);
            row.put("results", new JSONArray());
        } catch (Exception ignored) {}
        return row;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void write(Context context, JSONObject row) {
        synchronized (LOCK) {
            writeLocked(context, row);
        }
    }

    private static void writeLocked(Context context, JSONObject row) {
        File target = file(context);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(row.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        }
    }
}
