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
        // A full-streaming-owned segment must never be silently re-routed into the normal
        // post-segment queue, including after a live-ASR failure. Explicit force-retranscription
        // bypasses this check at the scheduler/runner layer and remains available to the user.
        if (FullStreamingStateStore.isOwned(context, segmentId)) {
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

        // AI inference is never run here. This only wakes semantic AI queue items whose target
        // period overlaps the transcript that just became durable.
        try {
            AiAnalysisScheduler.wakeWaitingTargets(context, segmentId);
        } catch (RuntimeException ignored) {
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
