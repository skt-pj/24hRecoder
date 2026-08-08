package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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

    public static void save(Context context, String segmentId, File audioFile, String model, String text)
            throws Exception {
        synchronized (LOCK) {
            File target = fileFor(context, segmentId);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");

            JSONObject row = new JSONObject();
            row.put("segmentId", segmentId);
            row.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile.getName());
            row.put("model", model);
            row.put("transcribedAtMs", System.currentTimeMillis());
            row.put("text", text == null ? "" : text);

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
