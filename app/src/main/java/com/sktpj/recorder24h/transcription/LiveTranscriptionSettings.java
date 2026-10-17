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

/** Cross-process settings that are intentionally independent from the normal five-minute model. */
public final class LiveTranscriptionSettings {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "live_transcription_settings.json";

    private LiveTranscriptionSettings() {
    }

    public static Snapshot snapshot(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            File file = file(app);
            if (!file.isFile()) {
                // Preserve an upgrade user's current model on first migration. After this point the
                // live model and normal/final model are independent settings.
                Snapshot migrated = new Snapshot(
                        WhisperModelManager.selectedModelId(app), true);
                writeLocked(app, migrated);
                return migrated;
            }
            try {
                JSONObject row = new JSONObject(new String(
                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                return new Snapshot(
                        normalizeModel(row.optString("liveModelId", WhisperModelManager.MODEL_DEFAULT)),
                        row.optBoolean("fiveMinuteFinalEnabled", true));
            } catch (Exception ignored) {
                Snapshot recovered = new Snapshot(
                        WhisperModelManager.selectedModelId(app), true);
                writeLocked(app, recovered);
                return recovered;
            }
        }
    }

    public static String selectedLiveModelId(Context context) {
        return snapshot(context).liveModelId;
    }

    public static boolean isFiveMinuteFinalEnabled(Context context) {
        return snapshot(context).fiveMinuteFinalEnabled;
    }

    public static void setLiveModelId(Context context, String modelId) {
        String normalized = normalizeModel(modelId);
        if (WhisperModelManager.modelSpec(modelId) == null) {
            throw new IllegalArgumentException("Unknown live Whisper model: " + modelId);
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Snapshot current = snapshot(app);
            writeLocked(app, new Snapshot(normalized, current.fiveMinuteFinalEnabled));
        }
    }

    public static void setFiveMinuteFinalEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Snapshot current = snapshot(app);
            writeLocked(app, new Snapshot(current.liveModelId, enabled));
        }
    }

    public static boolean isLiveModelReady(Context context) {
        String modelId = selectedLiveModelId(context);
        return WhisperModelManager.isComparisonReady(context, modelId);
    }

    private static String normalizeModel(String modelId) {
        return WhisperModelManager.modelSpec(modelId) == null
                ? WhisperModelManager.MODEL_DEFAULT : modelId;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void writeLocked(Context app, Snapshot snapshot) {
        JSONObject row = new JSONObject();
        try {
            row.put("schemaVersion", 1);
            row.put("liveModelId", snapshot.liveModelId);
            row.put("fiveMinuteFinalEnabled", snapshot.fiveMinuteFinalEnabled);
            row.put("updatedAtMs", System.currentTimeMillis());
        } catch (Exception error) {
            throw new IllegalStateException("LIVE_TRANSCRIPTION_SETTINGS_JSON_FAILED", error);
        }
        File target = file(app);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName()
                + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(row.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            throw new IllegalStateException("LIVE_TRANSCRIPTION_SETTINGS_WRITE_FAILED", error);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    public static final class Snapshot {
        public final String liveModelId;
        public final boolean fiveMinuteFinalEnabled;

        Snapshot(String liveModelId, boolean fiveMinuteFinalEnabled) {
            this.liveModelId = normalizeModel(liveModelId);
            this.fiveMinuteFinalEnabled = fiveMinuteFinalEnabled;
        }
    }
}
