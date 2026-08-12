package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Durable ownership and live-display state for full-streaming transcription. */
public final class FullStreamingStateStore {
    private static final Object LOCK = new Object();
    private static final String DIR = "metadata/full-streaming";

    private FullStreamingStateStore() {
    }

    public static void markOwned(Context context, String segmentId,
                                 TranscriptionPipelineSettings.Snapshot pipeline,
                                 String modelId, long startedAtMs, long endedAtMs) {
        if (segmentId == null || segmentId.isEmpty()) return;
        JSONObject row = readOwnership(context, segmentId);
        try {
            row.put("schemaVersion", 1);
            row.put("segmentId", segmentId);
            row.put("owned", true);
            row.put("state", row.optString("state", "OWNED"));
            row.put("modelId", modelId == null ? JSONObject.NULL : modelId);
            row.put("pipeline", pipeline == null ? JSONObject.NULL : pipeline.toJson());
            row.put("startedAtMs", startedAtMs);
            row.put("endedAtMs", endedAtMs);
            row.put("updatedAtMs", System.currentTimeMillis());
            row.put("automaticFallback", false);
            writeAtomic(ownershipFile(context, segmentId), row.toString());
        } catch (Exception ignored) {
        }
    }

    public static void markFinal(Context context, String segmentId, String engineId) {
        updateOwnershipState(context, segmentId, "FINAL", engineId, null);
    }

    public static void markFailed(Context context, String segmentId, String engineId, String error) {
        updateOwnershipState(context, segmentId, "FAILED", engineId, error);
    }

    public static boolean isOwned(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return false;
        JSONObject row = readOwnership(context, segmentId);
        return row.optBoolean("owned", false);
    }

    public static void writeLiveState(Context context, String state, String backend,
                                      String partialText, String latestFinalText,
                                      String accumulatedText, JSONArray accumulatedSegments,
                                      int queueDepth, String error) {
        JSONObject row = new JSONObject();
        try {
            row.put("schemaVersion", 1);
            row.put("state", state == null ? "OFF" : state);
            row.put("backend", backend == null ? JSONObject.NULL : backend);
            row.put("partialText", partialText == null ? "" : partialText);
            row.put("latestFinalText", latestFinalText == null ? "" : latestFinalText);
            row.put("accumulatedText", accumulatedText == null ? "" : accumulatedText);
            row.put("segments", accumulatedSegments == null
                    ? new JSONArray() : new JSONArray(accumulatedSegments.toString()));
            row.put("queueDepth", Math.max(0, queueDepth));
            row.put("error", error == null ? JSONObject.NULL : error);
            row.put("updatedAtMs", System.currentTimeMillis());
            row.put("automaticFallback", false);
            writeAtomic(currentFile(context), row.toString());
        } catch (Exception ignored) {
        }
    }

    public static LiveState readLiveState(Context context) {
        File file = currentFile(context);
        if (!file.isFile()) return LiveState.empty();
        try {
            JSONObject row = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return new LiveState(
                    row.optString("state", "OFF"),
                    row.isNull("backend") ? null : row.optString("backend", null),
                    row.optString("partialText", ""),
                    row.optString("latestFinalText", ""),
                    row.optString("accumulatedText", ""),
                    row.optInt("queueDepth", 0),
                    row.isNull("error") ? null : row.optString("error", null),
                    row.optLong("updatedAtMs", 0L));
        } catch (Exception ignored) {
            return LiveState.empty();
        }
    }

    private static void updateOwnershipState(Context context, String segmentId, String state,
                                             String engineId, String error) {
        if (segmentId == null || segmentId.isEmpty()) return;
        synchronized (LOCK) {
            JSONObject row = readOwnership(context, segmentId);
            try {
                row.put("schemaVersion", 1);
                row.put("segmentId", segmentId);
                row.put("owned", true);
                row.put("state", state);
                row.put("engineId", engineId == null ? JSONObject.NULL : engineId);
                row.put("error", error == null ? JSONObject.NULL : error);
                row.put("updatedAtMs", System.currentTimeMillis());
                row.put("automaticFallback", false);
                writeAtomic(ownershipFile(context, segmentId), row.toString());
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject readOwnership(Context context, String segmentId) {
        File file = ownershipFile(context, segmentId);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static File ownershipFile(Context context, String segmentId) {
        return new File(dir(context), safe(segmentId) + ".json");
    }

    private static File currentFile(Context context) {
        return new File(dir(context), "current.json");
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void writeAtomic(File target, String text) throws Exception {
        synchronized (LOCK) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File temp = new File(parent, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace full-streaming state");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Unable to finalize full-streaming state");
            }
        }
    }

    public static final class LiveState {
        public final String state;
        public final String backend;
        public final String partialText;
        public final String latestFinalText;
        public final String accumulatedText;
        public final int queueDepth;
        public final String error;
        public final long updatedAtMs;

        LiveState(String state, String backend, String partialText, String latestFinalText,
                  String accumulatedText, int queueDepth, String error, long updatedAtMs) {
            this.state = state;
            this.backend = backend;
            this.partialText = partialText;
            this.latestFinalText = latestFinalText;
            this.accumulatedText = accumulatedText;
            this.queueDepth = queueDepth;
            this.error = error;
            this.updatedAtMs = updatedAtMs;
        }

        static LiveState empty() {
            return new LiveState("OFF", null, "", "", "", 0, null, 0L);
        }
    }
}
