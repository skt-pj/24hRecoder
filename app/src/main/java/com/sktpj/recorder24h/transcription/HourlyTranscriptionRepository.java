package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Durable canonical result of the single ASR invocation for one local hour. */
public final class HourlyTranscriptionRepository {
    private HourlyTranscriptionRepository() {}

    public static File save(Context context,
                            String hourKey,
                            long hourStartAtMs,
                            String engineId,
                            String text,
                            JSONArray hourSegments,
                            SpeechTimeMap.MapResult timeMap,
                            List<String> sourceSegmentIds,
                            long sourceAudioMs,
                            long speechInputMs,
                            long removedSilenceMs) throws Exception {
        File dir = new File(context.getFilesDir(), "transcripts/hourly");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create hourly transcript directory");
        }
        String safe = hourKey == null || hourKey.isEmpty()
                ? String.valueOf(hourStartAtMs)
                : hourKey.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(dir, safe + ".json");
        File temp = new File(dir, target.getName() + ".tmp");
        JSONArray sourceIds = new JSONArray();
        if (sourceSegmentIds != null) {
            for (String id : sourceSegmentIds) sourceIds.put(id);
        }
        JSONObject root = new JSONObject()
                .put("schemaVersion", 1)
                .put("canonicalUnit", "local-hour")
                .put("hour", hourKey)
                .put("hourStartAtMs", hourStartAtMs)
                .put("hourEndAtMs", hourStartAtMs + 60L * 60L * 1000L)
                .put("model", engineId)
                .put("transcribedAtMs", System.currentTimeMillis())
                .put("text", text == null ? "" : text)
                .put("segments", hourSegments == null ? new JSONArray() : new JSONArray(hourSegments.toString()))
                .put("sourceSegmentIds", sourceIds)
                .put("sourceAudioMs", sourceAudioMs)
                .put("speechInputMs", speechInputMs)
                .put("removedSilenceMs", removedSilenceMs)
                .put("timebase", "hour-original-ms")
                .put("compactedTimebase", "silence-removed-ms")
                .put("mapping", timeMap == null ? new JSONArray() : timeMap.toJson())
                .put("whisperCallCount", speechInputMs > 0L ? 1 : 0)
                .put("silenceCompactionMapped", true);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace hourly transcript");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Unable to finalize hourly transcript");
        }
        return target;
    }
}
