package com.sktpj.recorder24h.storage;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class RecorderStateStore {
    private static final String FILE_NAME = "recorder_state.json";

    private RecorderStateStore() {
    }

    public static JSONObject read(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists()) {
                return defaultState();
            }
            return new JSONObject(readUtf8(file));
        } catch (Exception e) {
            return defaultState();
        }
    }

    private static String readUtf8(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return new String(buffer, 0, offset, StandardCharsets.UTF_8);
    }

    public static void write(Context context, String state, String segmentId, String error) {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state);
            json.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            json.put("heartbeatMs", System.currentTimeMillis());
            json.put("error", error == null ? JSONObject.NULL : error);
        } catch (Exception ignored) {
        }
        writeJson(context, json);
    }

    public static void heartbeat(Context context, String state, String segmentId) {
        JSONObject current = read(context);
        try {
            current.put("state", state);
            current.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            current.put("heartbeatMs", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        writeJson(context, current);
    }

    private static JSONObject defaultState() {
        JSONObject json = new JSONObject();
        try {
            json.put("state", "STOPPED");
            json.put("segmentId", JSONObject.NULL);
            json.put("heartbeatMs", 0L);
            json.put("error", JSONObject.NULL);
        } catch (Exception ignored) {
        }
        return json;
    }

    private static void writeJson(Context context, JSONObject json) {
        File target = new File(context.getFilesDir(), FILE_NAME);
        File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        }
    }
}
