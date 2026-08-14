package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class VulkanProbeStore {
    public static final String PROFILE_CPU = "cpu-control";
    public static final String PROFILE_VULKAN_DEFAULT = "vulkan-default";
    public static final String PROFILE_VULKAN_COOPMAT_OFF = "vulkan-coopmat-off";
    public static final String PROFILE_VULKAN_GRAPH_OFF = "vulkan-graph-optimize-off";
    public static final String PROFILE_VULKAN_SAFE = "vulkan-current-safe";

    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "vulkan_probe_status.json";

    private VulkanProbeStore() {}

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

    public static void begin(Context context, String profile, String modelId, String audioFile) {
        JSONObject row = empty();
        try {
            row.put("state", "RUNNING");
            row.put("profile", profile);
            row.put("phase", "STARTING");
            row.put("modelId", modelId);
            row.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile);
            row.put("startedAtMs", System.currentTimeMillis());
            row.put("updatedAtMs", System.currentTimeMillis());
            row.put("results", new JSONArray());
        } catch (Exception ignored) {}
        write(context, row);
    }

    public static void phase(Context context, String phase) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                row.put("state", "RUNNING");
                row.put("phase", phase);
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void addResult(Context context, String phase, JSONObject result) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                JSONArray results = row.optJSONArray("results");
                if (results == null) results = new JSONArray();
                results.put(new JSONObject()
                        .put("phase", phase)
                        .put("finishedAtMs", System.currentTimeMillis())
                        .put("result", result == null ? new JSONObject() : result));
                row.put("results", results);
                row.put("phase", phase + "_DONE");
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void complete(Context context) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                row.put("state", "COMPLETED");
                row.put("phase", "DONE");
                row.put("finishedAtMs", System.currentTimeMillis());
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void fail(Context context, String error) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                row.put("state", "FAILED");
                row.put("error", error == null ? "unknown" : error);
                row.put("finishedAtMs", System.currentTimeMillis());
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static String profileLabel(String profile) {
        if (PROFILE_CPU.equals(profile)) return "CPU対照";
        if (PROFILE_VULKAN_DEFAULT.equals(profile)) return "Vulkan標準";
        if (PROFILE_VULKAN_COOPMAT_OFF.equals(profile)) return "Vulkan coopmat無効";
        if (PROFILE_VULKAN_GRAPH_OFF.equals(profile)) return "Vulkan graph optimize無効";
        if (PROFILE_VULKAN_SAFE.equals(profile)) return "Vulkan 現行回避策";
        return profile == null ? "-" : profile;
    }

    private static JSONObject empty() {
        JSONObject row = new JSONObject();
        try { row.put("schemaVersion", 1).put("state", "IDLE").put("phase", "-"); }
        catch (Exception ignored) {}
        return row;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void write(Context context, JSONObject row) {
        synchronized (LOCK) { writeLocked(context, row); }
    }

    private static void writeLocked(Context context, JSONObject row) {
        File target = file(context);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(row.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) { return; }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            temp.renameTo(target);
        }
    }
}
