package com.sktpj.recorder24h.storage;

import android.content.Context;

import com.sktpj.recorder24h.transcription.TranscriptionScheduler;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class SegmentRepository {
    private static final Object LOCK = new Object();

    private SegmentRepository() {
    }

    public static void append(Context context, String segmentId, File file, long startedAtMs,
                              long endedAtMs, String status, String reason) {
        boolean newlyReady = "READY".equals(status)
                && reason == null
                && file != null
                && file.isFile()
                && file.getName().endsWith(".m4a");

        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), "metadata");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                File journal = new File(dir, "segments.jsonl");
                JSONObject row = new JSONObject();
                row.put("segmentId", segmentId);
                row.put("fileName", file == null ? JSONObject.NULL : file.getName());
                row.put("fileSize", file == null ? 0L : file.length());
                row.put("startedAtMs", startedAtMs);
                row.put("endedAtMs", endedAtMs);
                row.put("status", status);
                row.put("reason", reason == null ? JSONObject.NULL : reason);

                try (FileOutputStream out = new FileOutputStream(journal, true)) {
                    out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.getFD().sync();
                }
            } catch (Exception ignored) {
                return;
            }
        }

        if (newlyReady) {
            TranscriptionScheduler.notifySegmentReady(context, segmentId, file);
        }
    }
}
