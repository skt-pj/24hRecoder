package com.sktpj.recorder24h.audio;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Cross-process persistent recording-input preference. */
public final class AudioInputSettingsStore {
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String BUILTIN_KEY = "builtin";

    private static final String FILE_NAME = "audio_input_settings.json";

    private AudioInputSettingsStore() {
    }

    public static final class Settings {
        public final String mode;
        public final String manualDeviceKey;
        public final String manualDeviceLabel;
        public final int manualDeviceType;
        public final long updatedAtMs;

        Settings(String mode, String manualDeviceKey, String manualDeviceLabel,
                 int manualDeviceType, long updatedAtMs) {
            this.mode = mode;
            this.manualDeviceKey = manualDeviceKey;
            this.manualDeviceLabel = manualDeviceLabel;
            this.manualDeviceType = manualDeviceType;
            this.updatedAtMs = updatedAtMs;
        }

        public boolean isAuto() {
            return MODE_AUTO.equals(mode);
        }
    }

    public static Settings read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            return defaults();
        }
        try {
            JSONObject root = new JSONObject(readUtf8(file));
            String mode = root.optString("mode", MODE_AUTO);
            if (!MODE_MANUAL.equals(mode)) {
                mode = MODE_AUTO;
            }
            String key = root.optString("manualDeviceKey", BUILTIN_KEY);
            if (key == null || key.trim().isEmpty()) key = BUILTIN_KEY;
            String label = root.optString("manualDeviceLabel", "端末マイク");
            int type = root.optInt("manualDeviceType", 0);
            return new Settings(mode, key, label, type, root.optLong("updatedAtMs", 0L));
        } catch (Exception ignored) {
            return defaults();
        }
    }

    public static void setAuto(Context context) {
        Settings current = read(context);
        write(context, MODE_AUTO, current.manualDeviceKey, current.manualDeviceLabel,
                current.manualDeviceType);
    }

    public static void setManual(Context context, String key, String label, int type) {
        String safeKey = key == null || key.trim().isEmpty() ? BUILTIN_KEY : key;
        String safeLabel = label == null || label.trim().isEmpty() ? "端末マイク" : label;
        write(context, MODE_MANUAL, safeKey, safeLabel, type);
    }

    private static Settings defaults() {
        return new Settings(MODE_AUTO, BUILTIN_KEY, "端末マイク", 0, 0L);
    }

    private static void write(Context context, String mode, String key, String label, int type) {
        JSONObject root = new JSONObject();
        try {
            root.put("schemaVersion", 1);
            root.put("mode", mode);
            root.put("manualDeviceKey", key);
            root.put("manualDeviceLabel", label);
            root.put("manualDeviceType", type);
            root.put("updatedAtMs", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        writeAtomic(new File(context.getFilesDir(), FILE_NAME), root.toString());
    }

    private static void writeAtomic(File target, String text) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        }
    }

    private static String readUtf8(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return new String(buffer, 0, offset, StandardCharsets.UTF_8);
    }
}
