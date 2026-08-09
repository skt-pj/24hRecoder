package com.sktpj.recorder24h.transcription;

import android.content.Context;

import java.io.File;

public final class LocalWhisperEngine {
    public static final String ENGINE_ID = "whisper.cpp-v1.9.1/base+silero-v6.2.0";
    private static final String LANGUAGE = "ja";

    static {
        System.loadLibrary("whisper_jni");
    }

    private LocalWhisperEngine() {
    }

    public static synchronized Response transcribe(Context context, File audioFile) throws Exception {
        File model = WhisperModelManager.modelFile(context);
        File vadModel = WhisperModelManager.vadModelFile(context);
        if (!WhisperModelManager.isReady(context)) {
            throw new IllegalStateException("Whisper ASR/VAD models are not ready");
        }

        long decodeStarted = System.currentTimeMillis();
        float[] samples = M4aPcmDecoder.decode(audioFile);
        long decodedAt = System.currentTimeMillis();
        AudioStats stats = measure(samples);
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String text = nativeTranscribe(model.getAbsolutePath(), vadModel.getAbsolutePath(),
                samples, LANGUAGE, threads);
        long completedAt = System.currentTimeMillis();
        if (text == null) {
            throw new IllegalStateException("Local Whisper returned null");
        }
        return new Response(text.trim(), samples.length, threads,
                decodedAt - decodeStarted, completedAt - decodedAt,
                stats.rms, stats.peak, stats.clippedFraction);
    }

    private static AudioStats measure(float[] samples) {
        if (samples.length == 0) {
            return new AudioStats(0.0, 0.0, 0.0);
        }
        double sumSquares = 0.0;
        double peak = 0.0;
        long clipped = 0L;
        for (float sample : samples) {
            double absolute = Math.abs(sample);
            sumSquares += (double) sample * sample;
            peak = Math.max(peak, absolute);
            if (absolute >= 0.99) {
                clipped++;
            }
        }
        return new AudioStats(Math.sqrt(sumSquares / samples.length), peak,
                clipped / (double) samples.length);
    }

    private static native String nativeTranscribe(String modelPath, String vadModelPath,
                                                   float[] pcm, String language, int threads);

    private static final class AudioStats {
        final double rms;
        final double peak;
        final double clippedFraction;

        AudioStats(double rms, double peak, double clippedFraction) {
            this.rms = rms;
            this.peak = peak;
            this.clippedFraction = clippedFraction;
        }
    }

    public static final class Response {
        public final String text;
        public final int sampleCount;
        public final int threads;
        public final long decodeMs;
        public final long inferenceMs;
        public final double rms;
        public final double peak;
        public final double clippedFraction;

        Response(String text, int sampleCount, int threads, long decodeMs, long inferenceMs,
                 double rms, double peak, double clippedFraction) {
            this.text = text;
            this.sampleCount = sampleCount;
            this.threads = threads;
            this.decodeMs = decodeMs;
            this.inferenceMs = inferenceMs;
            this.rms = rms;
            this.peak = peak;
            this.clippedFraction = clippedFraction;
        }
    }
}
