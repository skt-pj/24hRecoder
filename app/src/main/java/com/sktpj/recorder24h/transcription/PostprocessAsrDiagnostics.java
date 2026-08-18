package com.sktpj.recorder24h.transcription;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DiagnosticLogSettings;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Records the last safe Java-side stage around native ASR work. */
public final class PostprocessAsrDiagnostics {
    private PostprocessAsrDiagnostics() {}

    public static String nativeBreadcrumbPath(Context context, String scope) {
        // Native breadcrumbs fsync every stage and are intentionally high-volume. Keep them
        // completely disabled unless the user has enabled detailed diagnostics.
        if (!DiagnosticLogSettings.isDetailedEnabled(context)) return "";
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        String safe = scope == null || scope.isEmpty() ? "asr" : scope.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, "native_breadcrumbs_" + safe + ".jsonl").getAbsolutePath();
    }

    public static void mark(Context context, String stage, String segmentId,
                            String backend, String modelId, JSONObject extra) {
        Context app = context.getApplicationContext();
        try {
            JSONObject details = new JSONObject();
            details.put("stage", stage);
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("backend", backend == null ? JSONObject.NULL : backend);
            details.put("modelId", modelId == null ? JSONObject.NULL : modelId);
            details.put("thread", Thread.currentThread().getName());
            Runtime runtime = Runtime.getRuntime();
            details.put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory());
            details.put("javaTotalBytes", runtime.totalMemory());
            details.put("javaMaxBytes", runtime.maxMemory());
            PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                details.put("interactive", power.isInteractive());
                details.put("powerSave", power.isPowerSaveMode());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    details.put("thermalStatus", power.getCurrentThermalStatus());
                }
            }
            if (extra != null) details.put("extra", extra);
            AppLogger.event(app, "POSTPROCESS_ASR_STAGE", details);
        } catch (Exception ignored) {}
        setProcessSummary(app, stage, segmentId, backend);
    }

    private static void setProcessSummary(Context context, String stage, String segmentId, String backend) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            String summary = stage + "|" + (segmentId == null ? "" : segmentId) + "|" + (backend == null ? "" : backend);
            if (summary.length() > 110) summary = summary.substring(0, 110);
            manager.setProcessStateSummary(summary.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }
}
