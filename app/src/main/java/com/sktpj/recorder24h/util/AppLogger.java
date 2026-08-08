package com.sktpj.recorder24h.util;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class AppLogger {
    private static final long MAX_LOG_BYTES = 5L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private AppLogger() {
    }

    public static void event(Context context, String event, JSONObject details) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }

                String processName = Application.getProcessName();
                String safeProcess = processName == null ? "unknown" : processName.replace(':', '_').replace('.', '_');
                File log = new File(dir, safeProcess + ".jsonl");
                rotateIfNeeded(log);

                JSONObject row = new JSONObject();
                row.put("timestampMs", System.currentTimeMillis());
                row.put("event", event);
                row.put("pid", Process.myPid());
                row.put("process", processName == null ? "unknown" : processName);
                if (details != null) {
                    row.put("details", details);
                }

                byte[] bytes = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(log, true)) {
                    out.write(bytes);
                    out.flush();
                }
            } catch (Exception ignored) {
                // Logging must never stop recording.
            }
        }
    }

    public static void event(Context context, String event) {
        event(context, event, null);
    }

    private static void rotateIfNeeded(File log) {
        if (!log.exists() || log.length() < MAX_LOG_BYTES) {
            return;
        }
        File rotated = new File(log.getParentFile(), log.getName() + ".1");
        if (rotated.exists()) {
            //noinspection ResultOfMethodCallIgnored
            rotated.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        log.renameTo(rotated);
    }
}
