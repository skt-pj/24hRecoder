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

    public static synchronized void write(Context context, String state, String segmentId, String error) {
        JSONObject json = read(context);
        long now = System.currentTimeMillis();
        try {
            String previousState = json.optString("state", "STOPPED");
            json.put("state", state);
            if (!state.equals(previousState)) {
                json.put("stateChangedAtMs", now);
            }
            json.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            json.put("heartbeatMs", now);
            json.put("error", error == null ? JSONObject.NULL : error);
            if ("STOPPED".equals(state) || "STARTING".equals(state) || "ERROR".equals(state)) {
                json.put("currentSegmentStartedAtMs", 0L);
            }
            if ("STOPPED".equals(state) || "ERROR".equals(state)) {
                json.put("captureSilenced", false);
            }
        } catch (Exception ignored) {
        }
        writeJson(context, json);
    }

    public static synchronized void segmentStarted(Context context, String segmentId,
                                                   long startedAtMs, long lastAudioReadMs) {
        JSONObject json = read(context);
        long now = System.currentTimeMillis();
        try {
            String previousState = json.optString("state", "STOPPED");
            json.put("state", "RECORDING");
            if (!"RECORDING".equals(previousState)) {
                json.put("stateChangedAtMs", now);
            }
            json.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            json.put("currentSegmentStartedAtMs", startedAtMs);
            json.put("heartbeatMs", now);
            if (lastAudioReadMs > 0L) {
                json.put("lastAudioReadMs", lastAudioReadMs);
            }
            json.put("error", JSONObject.NULL);
        } catch (Exception ignored) {
        }
        writeJson(context, json);
    }

    public static synchronized void segmentFinalized(Context context, String segmentId,
                                                     long endedAtMs, long durationMs) {
        JSONObject json = read(context);
        try {
            json.put("lastSegmentId", segmentId == null ? JSONObject.NULL : segmentId);
            json.put("lastSegmentFinalizedAtMs", endedAtMs);
            json.put("lastSegmentDurationMs", Math.max(0L, durationMs));
            json.put("segmentId", JSONObject.NULL);
            json.put("currentSegmentStartedAtMs", 0L);
            json.put("heartbeatMs", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        writeJson(context, json);
    }

    public static void heartbeat(Context context, String state, String segmentId) {
        JSONObject current = read(context);
        heartbeat(context, state, segmentId,
                current.optLong("currentSegmentStartedAtMs", 0L),
                current.optLong("lastAudioReadMs", 0L));
    }

    public static synchronized void heartbeat(Context context, String state, String segmentId,
                                              long currentSegmentStartedAtMs, long lastAudioReadMs) {
        JSONObject current = read(context);
        long now = System.currentTimeMillis();
        try {
            String previousState = current.optString("state", "STOPPED");
            current.put("state", state);
            if (!state.equals(previousState)) {
                current.put("stateChangedAtMs", now);
            }
            current.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            current.put("currentSegmentStartedAtMs", Math.max(0L, currentSegmentStartedAtMs));
            current.put("heartbeatMs", now);
            if (lastAudioReadMs > 0L) {
                current.put("lastAudioReadMs", lastAudioReadMs);
            }
        } catch (Exception ignored) {
        }
        writeJson(context, current);
    }

    public static synchronized void setCaptureSilenced(Context context, boolean silenced) {
        JSONObject current = read(context);
        try {
            current.put("captureSilenced", silenced);
        } catch (Exception ignored) {
        }
        writeJson(context, current);
    }

    private static JSONObject defaultState() {
        JSONObject json = new JSONObject();
        try {
            json.put("state", "STOPPED");
            json.put("stateChangedAtMs", 0L);
            json.put("segmentId", JSONObject.NULL);
            json.put("currentSegmentStartedAtMs", 0L);
            json.put("heartbeatMs", 0L);
            json.put("lastAudioReadMs", 0L);
            json.put("lastSegmentId", JSONObject.NULL);
            json.put("lastSegmentFinalizedAtMs", 0L);
            json.put("lastSegmentDurationMs", 0L);
            json.put("captureSilenced", false);
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
