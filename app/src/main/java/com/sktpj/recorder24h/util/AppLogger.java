package com.sktpj.recorder24h.util;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AppLogger {
    private static final long MAX_LOG_BYTES = 5L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private AppLogger() {
    }

    public static void event(Context context, String event, JSONObject details) {
        boolean detailed = isDetailedOnlyEvent(event);
        if (detailed && !DiagnosticLogSettings.isDetailedEnabled(context)) return;
        write(context, event, details, detailed ? "diagnostic" : "core");
    }

    public static void event(Context context, String event) {
        event(context, event, null);
    }

    public static void diagnostic(Context context, String event, JSONObject details) {
        if (!DiagnosticLogSettings.isDetailedEnabled(context)) return;
        write(context, event, details, "diagnostic");
    }

    public static void diagnostic(Context context, String event) {
        diagnostic(context, event, null);
    }

    private static void write(Context context, String event, JSONObject details, String logClass) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) return;

                String processName = Application.getProcessName();
                String safeProcess = processName == null ? "unknown" : processName.replace(':', '_').replace('.', '_');
                File log = new File(dir, safeProcess + ".jsonl");
                rotateIfNeeded(log);

                JSONObject row = new JSONObject();
                row.put("timestampMs", System.currentTimeMillis());
                row.put("event", event);
                row.put("logClass", logClass);
                row.put("pid", Process.myPid());
                row.put("process", processName == null ? "unknown" : processName);
                if (details != null) row.put("details", details);

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

    private static boolean isDetailedOnlyEvent(String event) {
        if (event == null || event.isEmpty()) return false;
        String value = event.toUpperCase(Locale.ROOT);

        // Failures and destructive/state-changing events remain available even when
        // detailed diagnostics are disabled.
        if (value.contains("FAILED") || value.contains("ERROR") || value.contains("EXCEPTION")
                || value.contains("CORRUPT") || value.contains("UNAVAILABLE")
                || value.contains("TIMEOUT") || value.contains("DEVICE_LOST")
                || value.contains("OOM") || value.contains("CRASH")) {
            return false;
        }
        if (value.startsWith("UI_") && !value.equals("UI_STREAMING_VAD_PRESET_CHANGED")) return false;
        if (value.startsWith("RECORDER_") || value.startsWith("AUDIO_INPUT_ROUTE_")) return false;
        if (value.equals("AI_ANALYSIS_SAVED") || value.equals("LOCAL_TRANSCRIPTION_SAVED")) return false;

        // High-frequency tracing and successful polling/housekeeping are diagnostic-only.
        if (value.startsWith("FULL_STREAMING_") || value.startsWith("STREAMING_VAD_")
                || value.startsWith("DEEPFILTERNET_")) return true;
        if (value.equals("POSTPROCESS_ASR_STAGE")
                || value.equals("DRIVE_LOG_SYNC_SUCCEEDED") || value.equals("MAIN_ACTIVITY_CREATED")
                || value.equals("TRANSCRIPTION_BACKEND_CAPABILITIES")
                || value.equals("REALTIME_ONLY_SEGMENT_NOT_STAGED_NIGHTLY")
                || value.equals("TRANSCRIPTION_SINGLE_RUNNER_RECOVERY_COMPLETED")) return true;
        if (value.startsWith("NIGHTLY_HOURLY_") || value.startsWith("AI_ROLLUP_SKIPPED_")
                || value.startsWith("AI_ANALYSIS_WAITING_") || value.startsWith("AI_ANALYSIS_SKIPPED_")) return true;
        if (value.endsWith("_BEGIN") || value.endsWith("_END") || value.contains("_30S")
                || value.contains("_COMPACTED") || value.contains("_BACKPRESSURE")
                || value.contains("_CAPABILITIES") || value.contains("_SLOT_WAIT")
                || value.contains("_SKIPPED_")) return true;
        return false;
    }

    private static void rotateIfNeeded(File log) {
        if (!log.exists() || log.length() < MAX_LOG_BYTES) return;
        File rotated = new File(log.getParentFile(), log.getName() + ".1");
        if (rotated.exists()) rotated.delete();
        log.renameTo(rotated);
    }
}
