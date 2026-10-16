package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Cross-process persistent Silero streaming VAD tuning. */
public final class StreamingVadSettings {
    public static final String PRESET_OFFICIAL = "official";
    public static final String PRESET_CONVERSATION = "conversation";
    public static final String PRESET_SENSITIVE = "sensitive";

    private static final Object LOCK = new Object();
    private static final String DIR = "settings";
    private static final String FILE = "streaming-vad.json";

    private StreamingVadSettings() {}

    public static Snapshot snapshot(Context context) {
        synchronized (LOCK) {
            File file = file(context);
            if (!file.isFile()) return preset(PRESET_CONVERSATION);
            try {
                JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                String id = normalizePreset(json.optString("presetId", PRESET_CONVERSATION));
                Snapshot fallback = preset(id);
                return new Snapshot(
                        id,
                        clamp(json.optDouble("startThreshold", fallback.startThreshold), 0.05, 0.95),
                        clamp(json.optDouble("endThreshold", fallback.endThreshold), 0.01, 0.90),
                        clampLong(json.optLong("minSpeechMs", fallback.minSpeechMs), 32L, 5_000L),
                        clampLong(json.optLong("minSilenceMs", fallback.minSilenceMs), 32L, 5_000L),
                        clampLong(json.optLong("speechPadMs", fallback.speechPadMs), 0L, 2_000L));
            } catch (Exception ignored) {
                return preset(PRESET_CONVERSATION);
            }
        }
    }

    public static boolean setPreset(Context context, String presetId) {
        synchronized (LOCK) {
            Snapshot snapshot = preset(normalizePreset(presetId));
            try {
                writeAtomic(file(context), snapshot.toJson().toString());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    public static Snapshot preset(String presetId) {
        String id = normalizePreset(presetId);
        if (PRESET_OFFICIAL.equals(id)) {
            return new Snapshot(id, 0.50, 0.35, 250L, 100L, 30L);
        }
        if (PRESET_SENSITIVE.equals(id)) {
            return new Snapshot(id, 0.35, 0.20, 150L, 300L, 300L);
        }
        return new Snapshot(PRESET_CONVERSATION, 0.40, 0.25, 250L, 300L, 300L);
    }

    public static String label(String presetId) {
        String id = normalizePreset(presetId);
        if (PRESET_OFFICIAL.equals(id)) return "公式基準";
        if (PRESET_SENSITIVE.equals(id)) return "高感度";
        return "会話向け・推奨";
    }

    private static String normalizePreset(String value) {
        if (PRESET_OFFICIAL.equals(value) || PRESET_SENSITIVE.equals(value)) return value;
        return PRESET_CONVERSATION;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static File file(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE);
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Unable to replace VAD settings");
        if (!temp.renameTo(target)) throw new IllegalStateException("Unable to finalize VAD settings");
    }

    public static final class Snapshot {
        public final String presetId;
        public final double startThreshold;
        public final double endThreshold;
        public final long minSpeechMs;
        public final long minSilenceMs;
        public final long speechPadMs;

        Snapshot(String presetId, double startThreshold, double endThreshold,
                 long minSpeechMs, long minSilenceMs, long speechPadMs) {
            this.presetId = presetId;
            this.startThreshold = startThreshold;
            this.endThreshold = Math.min(startThreshold, endThreshold);
            this.minSpeechMs = minSpeechMs;
            this.minSilenceMs = minSilenceMs;
            this.speechPadMs = speechPadMs;
        }

        public String label() {
            return StreamingVadSettings.label(presetId);
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("presetId", presetId);
                json.put("presetLabel", label());
                json.put("startThreshold", startThreshold);
                json.put("endThreshold", endThreshold);
                json.put("minSpeechMs", minSpeechMs);
                json.put("minSilenceMs", minSilenceMs);
                json.put("speechPadMs", speechPadMs);
            } catch (Exception ignored) {}
            return json;
        }
    }
}
