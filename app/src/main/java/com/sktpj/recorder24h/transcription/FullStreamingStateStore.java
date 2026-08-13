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
import java.util.Map;

/** Durable ownership and live-display state for full-streaming transcription. */
public final class FullStreamingStateStore {
    private static final Object LOCK = new Object();
    private static final String DIR = "metadata/full-streaming";
    private static final String RECENT_FILE = "recent.json";
    private static final long RECENT_RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int RECENT_MAX_ENTRIES = 1000;
    private static final long BIND_TOLERANCE_MS = 2_500L;

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
                entry.put("manualText", JSONObject.NULL);
                entry.put("backend", backend == null ? JSONObject.NULL : backend);
                String speaker = firstSpeaker(segments);
                entry.put("speaker", speaker == null ? JSONObject.NULL : speaker);
                entry.put("manualSpeaker", JSONObject.NULL);
                entry.put("deleted", false);
                entry.put("segments", segments == null ? new JSONArray() : new JSONArray(segments.toString()));
                entry.put("createdAtMs", now);
                entry.put("updatedAtMs", now);
                kept.put(entry);
                root.put("schemaVersion", 2);
                root.put("retentionMs", RECENT_RETENTION_MS);
                root.put("maxEntries", RECENT_MAX_ENTRIES);
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
                    if (row == null || row.optBoolean("deleted", false)) continue;
                    long endAtMs = row.optLong("endAtMs", 0L);
                    if (endAtMs > 0L && endAtMs < cutoff) continue;
                    String effectiveText = row.isNull("manualText")
                            ? row.optString("text", "") : row.optString("manualText", "");
                    String effectiveSpeaker = row.isNull("manualSpeaker")
                            ? (row.isNull("speaker") ? null : row.optString("speaker", null))
                            : row.optString("manualSpeaker", null);
                    out.add(new RecentFinal(
                            row.optString("id", "live-" + i),
                            row.optLong("startAtMs", 0L),
                            endAtMs,
                            row.optLong("startPtsUs", -1L),
                            row.optLong("endPtsUs", -1L),
                            effectiveText,
                            effectiveSpeaker,
                            row.isNull("backend") ? null : row.optString("backend", null)));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static boolean editRecentFinalText(Context context, String entryId, String text,
                                              String segmentId, long segmentStartedAtMs,
                                              long segmentEndedAtMs) {
        return mutateRecentFinal(context, entryId, text == null ? "" : text, null, false,
                segmentId, segmentStartedAtMs, segmentEndedAtMs);
    }

    public static boolean setRecentFinalSpeaker(Context context, String entryId, String speaker,
                                                String segmentId, long segmentStartedAtMs,
                                                long segmentEndedAtMs) {
        String normalized = speaker == null || speaker.trim().isEmpty() ? "判定不能" : speaker.trim();
        return mutateRecentFinal(context, entryId, null, normalized, false,
                segmentId, segmentStartedAtMs, segmentEndedAtMs);
    }

    public static boolean deleteRecentFinal(Context context, String entryId,
                                            String segmentId, long segmentStartedAtMs,
                                            long segmentEndedAtMs) {
        return mutateRecentFinal(context, entryId, null, null, true,
                segmentId, segmentStartedAtMs, segmentEndedAtMs);
    }

    /**
     * Called after a five-minute full-streaming transcript is saved. It binds recent live rows to
     * exact canonical transcript edit keys and applies any edit/delete that happened before the
     * five-minute boundary.
     */
    public static void bindAndApplyRecentEditsToSegment(Context context, String segmentId,
                                                        long segmentStartedAtMs, long segmentEndedAtMs) {
        if (segmentId == null || segmentId.isEmpty() || segmentStartedAtMs <= 0L) return;
        synchronized (LOCK) {
            try {
                JSONObject root = readRecentRoot(context);
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) return;
                boolean changed = false;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) continue;
                    long startAtMs = entry.optLong("startAtMs", 0L);
                    long endAtMs = entry.optLong("endAtMs", startAtMs);
                    if (!overlaps(startAtMs, endAtMs,
                            segmentStartedAtMs - BIND_TOLERANCE_MS,
                            segmentEndedAtMs + BIND_TOLERANCE_MS)) continue;
                    if (bindEntryToTranscript(context, entry, segmentId, segmentStartedAtMs)) {
                        changed = true;
                    }
                    applyEntryEdits(context, entry);
                }
                if (changed) {
                    root.put("schemaVersion", 2);
                    root.put("updatedAtMs", System.currentTimeMillis());
                    writeAtomic(recentFile(context), root.toString());
                }
            } catch (Exception ignored) {
                // Realtime edit synchronization must not fail authoritative transcript saving.
            }
        }
    }

    private static boolean mutateRecentFinal(Context context, String entryId,
                                             String manualText, String manualSpeaker,
                                             boolean delete,
                                             String segmentId, long segmentStartedAtMs,
                                             long segmentEndedAtMs) {
        if (entryId == null || entryId.isEmpty()) return false;
        synchronized (LOCK) {
            try {
                JSONObject root = readRecentRoot(context);
                JSONArray entries = root.optJSONArray("entries");
                if (entries == null) return false;
                JSONObject target = null;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject row = entries.optJSONObject(i);
                    if (row != null && entryId.equals(row.optString("id", ""))) {
                        target = row;
                        break;
                    }
                }
                if (target == null) return false;

                if (segmentId != null && !segmentId.trim().isEmpty() && segmentStartedAtMs > 0L) {
                    bindEntryToTranscript(context, target, segmentId, segmentStartedAtMs);
                }
                if (manualText != null) target.put("manualText", manualText);
                if (manualSpeaker != null) target.put("manualSpeaker", manualSpeaker);
                if (delete) target.put("deleted", true);
                target.put("updatedAtMs", System.currentTimeMillis());
                root.put("schemaVersion", 2);
                root.put("updatedAtMs", System.currentTimeMillis());
                writeAtomic(recentFile(context), root.toString());
                applyEntryEdits(context, target);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static boolean bindEntryToTranscript(Context context, JSONObject entry,
                                                 String segmentId, long segmentStartedAtMs) {
        try {
            File transcriptFile = TranscriptionRepository.fileFor(context, segmentId);
            if (!transcriptFile.isFile()) return false;
            JSONObject transcript = new JSONObject(new String(
                    Files.readAllBytes(transcriptFile.toPath()), StandardCharsets.UTF_8));
            JSONArray segments = transcript.optJSONArray("segments");
            if (segments == null || segments.length() == 0) return false;

            long entryStart = entry.optLong("startAtMs", 0L);
            long entryEnd = entry.optLong("endAtMs", entryStart);
            JSONArray bindings = new JSONArray();
            long bestDistance = Long.MAX_VALUE;
            JSONObject best = null;
            for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.optJSONObject(i);
                if (segment == null) continue;
                long startMs = segment.optLong("startMs", -1L);
                long endMs = segment.optLong("endMs", -1L);
                String sourceText = segment.optString("text", "").trim();
                if (startMs < 0L || endMs < startMs || sourceText.isEmpty()) continue;
                long absoluteStart = segmentStartedAtMs + startMs;
                long absoluteEnd = segmentStartedAtMs + endMs;
                JSONObject binding = bindingFor(segment, startMs, endMs, sourceText);
                if (overlaps(entryStart, entryEnd, absoluteStart, absoluteEnd)) {
                    bindings.put(binding);
                }
                long entryCenter = (entryStart + entryEnd) / 2L;
                long segmentCenter = (absoluteStart + absoluteEnd) / 2L;
                long distance = Math.abs(entryCenter - segmentCenter);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = binding;
                }
            }
            if (bindings.length() == 0 && best != null && bestDistance <= BIND_TOLERANCE_MS) {
                bindings.put(best);
            }
            if (bindings.length() == 0) return false;
            entry.put("segmentId", segmentId);
            entry.put("segmentStartedAtMs", segmentStartedAtMs);
            entry.put("canonicalBindings", bindings);
            entry.put("boundAtMs", System.currentTimeMillis());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONObject bindingFor(JSONObject segment, long startMs, long endMs,
                                         String sourceText) throws Exception {
        JSONObject binding = new JSONObject();
        binding.put("startMs", startMs);
        binding.put("endMs", endMs);
        binding.put("sourceText", sourceText);
        binding.put("editKey", TranscriptEditRepository.chunkKey(startMs, endMs, sourceText));
        binding.put("sourceSpeaker", canonicalSpeaker(segment));
        return binding;
    }

    private static void applyEntryEdits(Context context, JSONObject entry) {
        try {
            String segmentId = entry.optString("segmentId", "");
            JSONArray bindings = entry.optJSONArray("canonicalBindings");
            if (segmentId.isEmpty() || bindings == null || bindings.length() == 0
                    || !TranscriptionRepository.exists(context, segmentId)) return;

            boolean deleted = entry.optBoolean("deleted", false);
            String manualText = entry.isNull("manualText") ? null : entry.optString("manualText", "");
            String manualSpeaker = entry.isNull("manualSpeaker")
                    ? null : entry.optString("manualSpeaker", "判定不能").trim();
            if (!deleted && manualText == null && manualSpeaker == null) return;

            Map<String, TranscriptEdit> existing = TranscriptEditRepository.load(context, segmentId);
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject binding = bindings.optJSONObject(i);
                if (binding == null) continue;
                String editKey = binding.optString("editKey", "");
                if (editKey.isEmpty()) continue;
                String sourceText = binding.optString("sourceText", "");
                String sourceSpeaker = binding.optString("sourceSpeaker", "判定不能");
                TranscriptEdit prior = existing.get(editKey);
                String speaker = manualSpeaker != null && !manualSpeaker.isEmpty()
                        ? manualSpeaker
                        : (prior != null && prior.getSpeaker() != null && !prior.getSpeaker().trim().isEmpty()
                        ? prior.getSpeaker() : sourceSpeaker);
                String text;
                if (deleted) {
                    text = "";
                } else if (manualText != null) {
                    text = i == 0 ? manualText : "";
                } else {
                    text = prior != null && prior.getText() != null ? prior.getText() : sourceText;
                }
                TranscriptEditRepository.save(context, segmentId, editKey, text, speaker);
            }
        } catch (Exception ignored) {
        }
    }

    private static String canonicalSpeaker(JSONObject segment) {
        String explicit = segment.optString("speaker", "").trim();
        if (!explicit.isEmpty()) return explicit;
        explicit = segment.optString("speakerId", "").trim();
        if (!explicit.isEmpty()) return explicit;
        String automatic = segment.optString("autoSpeaker", "unknown");
        if ("self".equalsIgnoreCase(automatic)) return "自分";
        if ("other".equalsIgnoreCase(automatic)) return "他人";
        return "判定不能";
    }

    private static boolean overlaps(long leftStart, long leftEnd, long rightStart, long rightEnd) {
        long safeLeftEnd = Math.max(leftStart, leftEnd);
        long safeRightEnd = Math.max(rightStart, rightEnd);
        return safeLeftEnd >= rightStart && leftStart <= safeRightEnd;
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
