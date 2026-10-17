package com.sktpj.recorder24h.util;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class DiagnosticLogSettings {
    private static final Object LOCK = new Object();
    private static final long CACHE_MS = 2_000L;
    private static volatile boolean cachedDetailed;
    private static volatile long lastReadElapsedMs;

    private DiagnosticLogSettings() {}

    public static boolean isDetailedEnabled(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastReadElapsedMs < CACHE_MS) return cachedDetailed;
        synchronized (LOCK) {
            now = SystemClock.elapsedRealtime();
            if (now - lastReadElapsedMs < CACHE_MS) return cachedDetailed;
            cachedDetailed = readDetailed(context);
            lastReadElapsedMs = now;
            return cachedDetailed;
        }
    }

    public static boolean setDetailedEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            try {
                File target = settingsFile(context);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
                File temp = new File(parent, target.getName() + ".tmp");
                JSONObject json = new JSONObject()
                        .put("schemaVersion", 1)
                        .put("detailed", enabled)
                        .put("updatedAtMs", System.currentTimeMillis());
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream out = new FileOutputStream(temp, false)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
                if (target.exists() && !target.delete()) return false;
                if (!temp.renameTo(target)) return false;
                cachedDetailed = enabled;
                lastReadElapsedMs = SystemClock.elapsedRealtime();
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private static boolean readDetailed(Context context) {
        try {
            File file = settingsFile(context);
            if (!file.isFile()) return false;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(text).optBoolean("detailed", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static File settingsFile(Context context) {
        return new File(new File(context.getFilesDir(), "metadata"), "diagnostic-log-settings.json");
    }
}
