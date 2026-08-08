package com.sktpj.recorder24h.transcription;

import android.content.Context;

import java.io.File;

public final class LocalWhisperEngine {
    public static final String ENGINE_ID = "whisper.cpp-v1.9.1/base";
    private static final String LANGUAGE = "ja";

    static {
        System.loadLibrary("whisper_jni");
    }

    private LocalWhisperEngine() {
    }

    public static synchronized Response transcribe(Context context, File audioFile) throws Exception {
        File model = WhisperModelManager.modelFile(context);
        if (!WhisperModelManager.isReady(context)) {
            throw new IllegalStateException("Whisper model is not ready");
        }

        long decodeStarted = System.currentTimeMillis();
        float[] samples = M4aPcmDecoder.decode(audioFile);
        long decodedAt = System.currentTimeMillis();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String text = nativeTranscribe(model.getAbsolutePath(), samples, LANGUAGE, threads);
        long completedAt = System.currentTimeMillis();
        if (text == null) {
            throw new IllegalStateException("Local Whisper returned null");
        }
        return new Response(text.trim(), samples.length, threads,
                decodedAt - decodeStarted, completedAt - decodedAt);
    }

    private static native String nativeTranscribe(String modelPath, float[] pcm,
                                                   String language, int threads);

    public static final class Response {
        public final String text;
        public final int sampleCount;
        public final int threads;
        public final long decodeMs;
        public final long inferenceMs;

        Response(String text, int sampleCount, int threads, long decodeMs, long inferenceMs) {
            this.text = text;
            this.sampleCount = sampleCount;
            this.threads = threads;
            this.decodeMs = decodeMs;
            this.inferenceMs = inferenceMs;
        }
    }
}
