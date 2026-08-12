package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Persistent whisper.cpp context used only by the full-streaming service process. */
final class LiveWhisperSession {
    private static boolean open;
    private static String openModelPath;
    private static boolean openGpu;
    private static long modelLoadMs;

    static {
        System.loadLibrary("whisper_jni");
    }

    private LiveWhisperSession() {
    }

    static synchronized long open(Context context, String modelId,
                                  TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {
        if (pipeline == null || TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) {
            close();
            return 0L;
        }
        boolean useGpu = TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(pipeline.asrBackend);
        File model = WhisperModelManager.modelFile(context, modelId);
        if (!model.isFile()) throw new IllegalStateException("LOCAL_WHISPER_MODEL_MISSING");
        String path = model.getAbsolutePath();
        if (open && path.equals(openModelPath) && useGpu == openGpu) return modelLoadMs;
        close();
        long loaded = nativeLiveWhisperOpen(path, useGpu);
        if (loaded < 0L) throw new IllegalStateException("LIVE_WHISPER_OPEN_FAILED");
        open = true;
        openModelPath = path;
        openGpu = useGpu;
        modelLoadMs = loaded;
        return loaded;
    }

    static synchronized Result transcribe(float[] samples) throws Exception {
        if (!open) throw new IllegalStateException("LIVE_WHISPER_NOT_OPEN");
        if (samples == null || samples.length == 0) return new Result("", new JSONArray(), 0L, 0L);
        String raw = nativeLiveWhisperTranscribe(samples, "ja", threadCount());
        if (raw == null) throw new IllegalStateException("LIVE_WHISPER_RETURNED_NULL");
        JSONObject json = new JSONObject(raw);
        JSONArray segments = json.optJSONArray("segments");
        return new Result(
                json.optString("text", "").trim(),
                segments == null ? new JSONArray() : segments,
                json.optLong("whisperFullMs", -1L),
                json.optLong("lastOutputEndMs", 0L));
    }

    static synchronized void close() {
        try {
            nativeLiveWhisperClose();
        } catch (Throwable ignored) {
        }
        open = false;
        openModelPath = null;
        openGpu = false;
        modelLoadMs = 0L;
    }

    private static int threadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static native long nativeLiveWhisperOpen(String modelPath, boolean useGpu);
    private static native String nativeLiveWhisperTranscribe(float[] pcm, String language, int threads);
    private static native void nativeLiveWhisperClose();

    static final class Result {
        final String text;
        final JSONArray segments;
        final long whisperFullMs;
        final long lastOutputEndMs;

        Result(String text, JSONArray segments, long whisperFullMs, long lastOutputEndMs) {
            this.text = text;
            this.segments = segments;
            this.whisperFullMs = whisperFullMs;
            this.lastOutputEndMs = lastOutputEndMs;
        }
    }
}
