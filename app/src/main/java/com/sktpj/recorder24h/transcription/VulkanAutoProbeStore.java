package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Shared progress/result store for the one-button CPU vs production Vulkan benchmark. */
public final class VulkanAutoProbeStore {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "vulkan_auto_probe_status.json";

    private VulkanAutoProbeStore() {}

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

    public static void start(Context context, int totalProfiles, String modelId,
                             String audioFile, String audioPath, long sourceDurationMs) {
        JSONObject row = empty();
        try {
            long now = System.currentTimeMillis();
            row.put("state", "RUNNING");
            row.put("phase", "STARTING");
            row.put("totalProfiles", totalProfiles);
            row.put("currentIndex", -1);
            row.put("currentProfile", JSONObject.NULL);
            row.put("startedAtMs", now);
            row.put("updatedAtMs", now);
            row.put("modelId", modelId == null ? JSONObject.NULL : modelId);
            row.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile);
            row.put("audioPath", audioPath == null ? JSONObject.NULL : audioPath);
            row.put("sourceDurationMs", sourceDurationMs);
            row.put("fixedInput", true);
            row.put("benchmarkDurationsMs", new JSONArray().put(2_000L).put(10_000L));
            row.put("results", new JSONArray());
        } catch (Exception ignored) {}
        write(context, row);
    }

    public static void profileStarting(Context context, int index, String profile) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                row.put("state", "RUNNING");
                row.put("phase", "PROFILE_STARTING");
                row.put("currentIndex", index);
                row.put("currentProfile", profile);
                row.put("profileStartedAtMs", System.currentTimeMillis());
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void updatePhase(Context context, String profile, String phase) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            if (!profile.equals(row.optString("currentProfile", ""))) return;
            try {
                row.put("phase", phase == null ? "-" : phase);
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    public static void profileResult(Context context, int index, String profile, JSONObject result) {
        synchronized (LOCK) {
            JSONObject row = read(context);
            try {
                JSONArray results = row.optJSONArray("results");
                if (results == null) results = new JSONArray();
                JSONObject wrapped = result == null ? new JSONObject() : new JSONObject(result.toString());
                wrapped.put("index", index);
                wrapped.put("profile", profile);
                wrapped.put("label", VulkanProbeStore.profileLabel(profile));
                wrapped.put("finishedAtMs", System.currentTimeMillis());
                results.put(wrapped);
                row.put("results", results);
                row.put("phase", "PROFILE_FINISHED");
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
                row.put("currentProfile", JSONObject.NULL);
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
                row.put("phase", "FAILED");
                row.put("error", error == null ? "unknown" : error);
                row.put("finishedAtMs", System.currentTimeMillis());
                row.put("updatedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) {}
            writeLocked(context, row);
        }
    }

    private static JSONObject empty() {
        JSONObject row = new JSONObject();
        try {
            row.put("schemaVersion", 2);
            row.put("state", "IDLE");
            row.put("phase", "-");
            row.put("totalProfiles", 2);
            row.put("currentIndex", -1);
            row.put("currentProfile", JSONObject.NULL);
            row.put("fixedInput", true);
            row.put("results", new JSONArray());
        } catch (Exception ignored) {}
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
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            temp.renameTo(target);
        }
    }
}
