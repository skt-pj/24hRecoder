package com.sktpj.recorder24h.transcription;

import android.content.Context;

import com.sktpj.recorder24h.ai.AiAnalysisScheduler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class TranscriptionRepository {
    private static final Object LOCK = new Object();

    private TranscriptionRepository() {
    }

    public static File getTranscriptDir(Context context) {
        File dir = new File(context.getFilesDir(), "transcripts");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File fileFor(Context context, String segmentId) {
        return new File(getTranscriptDir(context), safeSegmentId(segmentId) + ".json");
    }

    public static boolean exists(Context context, String segmentId) {
        return fileFor(context, segmentId).isFile();
    }

    public static boolean isCurrentEngine(Context context, String segmentId, String engineId) {
        // A live-owned segment bypasses the normal queue only when the user explicitly
        // disabled the five-minute final pass for that frozen segment. If finalization is ON, the
        // normal/final model is intentionally independent from the live model and must run.
        if (FullStreamingStateStore.isOwned(context, segmentId)
                && (!LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)
                    || !LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context))) {
            return true;
        }
        File file = fileFor(context, segmentId);
        if (!file.isFile()) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder json = new StringBuilder((int) Math.min(file.length(), 64 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) {
                    json.append(buffer, 0, read);
                }
            }
            JSONObject row = new JSONObject(json.toString());
            return engineId.equals(row.optString("model", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void save(Context context, String segmentId, File audioFile, String model, String text)
            throws Exception {
        save(context, segmentId, audioFile, model, text, null);
    }

    public static void save(Context context, String segmentId, File audioFile, String model, String text,
                            JSONArray segments) throws Exception {
        synchronized (LOCK) {
            File target = fileFor(context, segmentId);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");

            JSONObject row = new JSONObject();
            row.put("schemaVersion", 2);
            row.put("segmentId", segmentId);
            row.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile.getName());
            row.put("model", model);
            row.put("transcribedAtMs", System.currentTimeMillis());
            row.put("text", text == null ? "" : text);
            row.put("segments", segments == null ? new JSONArray() : new JSONArray(segments.toString()));

            byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }

            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace transcript file");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Unable to finalize transcript file");
            }
        }

        // Full-streaming live edits can happen before the five-minute transcript exists. Once the
        // authoritative transcript is durable, bind those rolling live rows to canonical chunk
        // edit keys and apply pending text/speaker/delete operations.
        applyPendingLiveEdits(context, segmentId);
        if (FullStreamingStateStore.isOwned(context, segmentId)
                && LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)) {
            FullStreamingStateStore.markFinal(context, segmentId, model);
        }

        // AI inference is never run here. This only wakes semantic AI queue items whose target
        // period overlaps the transcript that just became durable.
        try {
            AiAnalysisScheduler.wakeWaitingTargets(context, segmentId);
        } catch (RuntimeException ignored) {
        }
    }

    private static void applyPendingLiveEdits(Context context, String segmentId) {
        if (!FullStreamingStateStore.isOwned(context, segmentId)) return;
        File ownership = new File(context.getFilesDir(),
                "metadata/full-streaming/" + safeSegmentId(segmentId) + ".json");
        if (!ownership.isFile()) return;
        try (FileInputStream input = new FileInputStream(ownership);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder json = new StringBuilder((int) Math.min(ownership.length(), 32 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) json.append(buffer, 0, read);
            }
            JSONObject row = new JSONObject(json.toString());
            long startedAtMs = row.optLong("startedAtMs", 0L);
            long endedAtMs = row.optLong("endedAtMs", startedAtMs);
            if (startedAtMs > 0L) {
                FullStreamingStateStore.bindAndApplyRecentEditsToSegment(
                        context, segmentId, startedAtMs, Math.max(startedAtMs, endedAtMs));
            }
        } catch (Exception ignored) {
        }
    }

    public static int count(Context context) {
        File[] files = getTranscriptDir(context).listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    private static String safeSegmentId(String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) {
            return "unknown";
        }
        return segmentId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
