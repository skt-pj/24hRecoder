package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Frozen per-five-minute policy for segments that were captured by the live pipeline. */
public final class LiveSegmentPolicyStore {
    private static final Object LOCK = new Object();
    private static final String DIR = "metadata/full-streaming-policy";
    private static final long RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;

    private LiveSegmentPolicyStore() {
    }

    public static void mark(Context context, String segmentId, String liveModelId,
                            boolean fiveMinuteFinalEnabled,
                            long startedAtMs, long endedAtMs) {
        if (segmentId == null || segmentId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                JSONObject row = new JSONObject();
                row.put("schemaVersion", 1);
                row.put("segmentId", segmentId);
                row.put("liveModelId", liveModelId == null ? JSONObject.NULL : liveModelId);
                row.put("fiveMinuteFinalEnabled", fiveMinuteFinalEnabled);
                row.put("startedAtMs", startedAtMs);
                row.put("endedAtMs", endedAtMs);
                row.put("updatedAtMs", System.currentTimeMillis());
                writeAtomic(file(context, segmentId), row.toString());
                cleanupOld(context);
            } catch (Exception ignored) {
            }
        }
    }

    public static Policy read(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return null;
        File file = file(context, segmentId);
        if (!file.isFile()) return null;
        try {
            JSONObject row = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            String liveModelId = row.isNull("liveModelId")
                    ? null : row.optString("liveModelId", null);
            return new Policy(
                    liveModelId,
                    row.optBoolean("fiveMinuteFinalEnabled", false),
                    row.optLong("startedAtMs", 0L),
                    row.optLong("endedAtMs", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isFiveMinuteFinalEnabled(Context context, String segmentId) {
        Policy policy = read(context, segmentId);
        return policy != null && policy.fiveMinuteFinalEnabled;
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file(Context context, String segmentId) {
        return new File(dir(context), safe(segmentId) + ".json");
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName()
                + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
        try {
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
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static void cleanupOld(Context context) {
        File[] files = dir(context).listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        for (File file : files) {
            if (file.lastModified() > 0L && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static final class Policy {
        public final String liveModelId;
        public final boolean fiveMinuteFinalEnabled;
        public final long startedAtMs;
        public final long endedAtMs;

        Policy(String liveModelId, boolean fiveMinuteFinalEnabled,
               long startedAtMs, long endedAtMs) {
            this.liveModelId = liveModelId;
            this.fiveMinuteFinalEnabled = fiveMinuteFinalEnabled;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }
    }
}
