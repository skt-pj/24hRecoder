package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Durable ownership and live-display state for full-streaming transcription. */
public final class FullStreamingStateStore {
    private static final Object LOCK = new Object();
    private static final String DIR = "metadata/full-streaming";
    private static final String RECENT_FILE = "recent.json";
    private static final long RECENT_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int RECENT_MAX_ENTRIES = 1000;

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

    /** Durable rolling final utterances for the Record > Realtime view. */
    public static void appendRecentFinal(Context context,
                                         long startAtMs, long endAtMs,
                                         long startPtsUs, long endPtsUs,
                                         String text, String backend,
                                         JSONArray segments) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                long cutoff = now - RECENT_RETENTION_MS;
                JSONObject root = readRecentRoot(context);
                JSONArray old = root.optJSONArray("entries");
                if (old == null) old = new JSONArray();
                JSONArray kept = new JSONArray();
                int earliestIndex = Math.max(0, old.length() - (RECENT_MAX_ENTRIES - 1));
                for (int i = earliestIndex; i < old.length(); i++) {
                    JSONObject row = old.optJSONObject(i);
                    if (row == null) continue;
                    long rowEnd = row.optLong("endAtMs", row.optLong("createdAtMs", 0L));
                    if (rowEnd >= cutoff) kept.put(row);
                }
                JSONObject entry = new JSONObject();
                entry.put("id", now + "-" + startPtsUs + "-" + endPtsUs);
                entry.put("startAtMs", Math.max(0L, startAtMs));
                entry.put("endAtMs", Math.max(startAtMs, endAtMs));
                entry.put("startPtsUs", startPtsUs);
                entry.put("endPtsUs", endPtsUs);
                entry.put("text", text.trim());
                entry.put("backend", backend == null ? JSONObject.NULL : backend);
                String speaker = firstSpeaker(segments);
                entry.put("speaker", speaker == null ? JSONObject.NULL : speaker);
                entry.put("segments", segments == null ? new JSONArray() : new JSONArray(segments.toString()));
                entry.put("createdAtMs", now);
                kept.put(entry);
                root.put("schemaVersion", 1);
                root.put("retentionMs", RECENT_RETENTION_MS);
                root.put("updatedAtMs", now);
                root.put("entries", kept);
                writeAtomic(recentFile(context), root.toString());
            } catch (Exception ignored) {
                // Live display persistence must never fail the authoritative transcription.
            }
        }
    }

    public static List<RecentFinal> readRecentFinals(Context context) {
        List<RecentFinal> out = new ArrayList<>();
        synchronized (LOCK) {
            try {
                JSONObject root = readRecentRoot(context);
                JSONArray rows = root.optJSONArray("entries");
                if (rows == null) return out;
                long cutoff = System.currentTimeMillis() - RECENT_RETENTION_MS;
                int start = Math.max(0, rows.length() - RECENT_MAX_ENTRIES);
                for (int i = start; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long endAtMs = row.optLong("endAtMs", 0L);
                    if (endAtMs > 0L && endAtMs < cutoff) continue;
                    out.add(new RecentFinal(
                            row.optString("id", "live-" + i),
                            row.optLong("startAtMs", 0L),
                            endAtMs,
                            row.optLong("startPtsUs", -1L),
                            row.optLong("endPtsUs", -1L),
                            row.optString("text", ""),
                            row.isNull("speaker") ? null : row.optString("speaker", null),
                            row.isNull("backend") ? null : row.optString("backend", null)));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject readRecentRoot(Context context) {
        File file = recentFile(context);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String firstSpeaker(JSONArray segments) {
        if (segments == null) return null;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject row = segments.optJSONObject(i);
            if (row == null) continue;
            String speaker = row.optString("speaker", "").trim();
            if (!speaker.isEmpty()) return speaker;
            speaker = row.optString("speakerId", "").trim();
            if (!speaker.isEmpty()) return speaker;
        }
        return null;
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

    private static File recentFile(Context context) {
        return new File(dir(context), RECENT_FILE);
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
            File temp = new File(parent, target.getName()
                    + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                if (temp.exists()) temp.delete();
            }
        }
    }

    public static final class RecentFinal {
        public final String id;
        public final long startAtMs;
        public final long endAtMs;
        public final long startPtsUs;
        public final long endPtsUs;
        public final String text;
        public final String speaker;
        public final String backend;

        RecentFinal(String id, long startAtMs, long endAtMs,
                    long startPtsUs, long endPtsUs, String text,
                    String speaker, String backend) {
            this.id = id;
            this.startAtMs = startAtMs;
            this.endAtMs = endAtMs;
            this.startPtsUs = startPtsUs;
            this.endPtsUs = endPtsUs;
            this.text = text;
            this.speaker = speaker;
            this.backend = backend;
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
