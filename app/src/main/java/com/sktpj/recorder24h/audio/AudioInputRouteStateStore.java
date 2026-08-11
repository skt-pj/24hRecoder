package com.sktpj.recorder24h.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Cross-process snapshot of the requested and actual recording input route. */
public final class AudioInputRouteStateStore {
    private static final String FILE_NAME = "audio_input_route.json";
    private static final Object LOCK = new Object();

    private AudioInputRouteStateStore() {
    }

    public static JSONObject read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(readUtf8(file));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static void writeRequested(Context context,
                                      AudioInputSettingsStore.Settings settings,
                                      AudioDeviceInfo preferred,
                                      boolean preferredAccepted,
                                      String fallbackReason) {
        synchronized (LOCK) {
            JSONObject root = read(context);
            try {
                root.put("updatedAtMs", System.currentTimeMillis());
                root.put("requestedMode", settings.mode);
                root.put("manualDeviceKey", settings.manualDeviceKey);
                root.put("manualDeviceLabel", settings.manualDeviceLabel);
                root.put("preferredAccepted", preferredAccepted);
                root.put("fallbackReason", fallbackReason == null ? JSONObject.NULL : fallbackReason);
                putDevice(root, "preferred", preferred);
            } catch (Exception ignored) {
            }
            writeAtomic(context, root);
        }
    }

    public static void writeActual(Context context, AudioDeviceInfo device) {
        synchronized (LOCK) {
            JSONObject root = read(context);
            try {
                root.put("updatedAtMs", System.currentTimeMillis());
                putDevice(root, "actual", device);
            } catch (Exception ignored) {
            }
            writeAtomic(context, root);
        }
    }

    private static void putDevice(JSONObject root, String prefix, AudioDeviceInfo device) throws Exception {
        if (device == null) {
            root.put(prefix + "DeviceKey", JSONObject.NULL);
            root.put(prefix + "DeviceLabel", JSONObject.NULL);
            root.put(prefix + "DeviceType", JSONObject.NULL);
            root.put(prefix + "DeviceId", JSONObject.NULL);
            root.put(prefix + "Bluetooth", false);
            return;
        }
        root.put(prefix + "DeviceKey", AudioInputRouter.deviceKey(device));
        root.put(prefix + "DeviceLabel", AudioInputRouter.deviceLabel(device));
        root.put(prefix + "DeviceType", device.getType());
        root.put(prefix + "DeviceId", device.getId());
        root.put(prefix + "Bluetooth", AudioInputRouter.isBluetoothMic(device));
    }

    private static void writeAtomic(Context context, JSONObject root) {
        File target = new File(context.getFilesDir(), FILE_NAME);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
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
