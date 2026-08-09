package com.sktpj.recorder24h.transcription;

import android.content.Context;

import java.io.File;

public final class LocalWhisperEngine {
    public static final String ENGINE_ID = "whisper.cpp-v1.9.1/base+frontend-v1+silero-v6.2.0";
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
        float[] decodedSamples = M4aPcmDecoder.decode(audioFile);
        long decodedAt = System.currentTimeMillis();

        AudioPreprocessor.Result frontEnd = AudioPreprocessor.process(decodedSamples);
        long preprocessedAt = System.currentTimeMillis();

        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String text = nativeTranscribe(model.getAbsolutePath(), vadModel.getAbsolutePath(),
                frontEnd.samples, LANGUAGE, threads);
        long completedAt = System.currentTimeMillis();
        if (text == null) {
            throw new IllegalStateException("Local Whisper returned null");
        }

        return new Response(
                text.trim(),
                frontEnd.samples.length,
                threads,
                decodedAt - decodeStarted,
                preprocessedAt - decodedAt,
                completedAt - preprocessedAt,
                frontEnd);
    }

    private static native String nativeTranscribe(String modelPath, String vadModelPath,
                                                   float[] pcm, String language, int threads);

    public static final class Response {
        public final String text;
        public final int sampleCount;
        public final int threads;
        public final long decodeMs;
        public final long preprocessMs;
        public final long inferenceMs;

        // Post-front-end metrics kept under the previous names for compatibility with existing logs.
        public final double rms;
        public final double peak;
        public final double clippedFraction;

        public final double inputRms;
        public final double inputPeak;
        public final double inputClippedFraction;
        public final double dcOffset;
        public final double estimatedNoiseRms;
        public final double estimatedSpeechRms;
        public final double snrProxyDb;
        public final double appliedGainDb;
        public final double activeFrameFraction;
        public final double limitedSampleFraction;
        public final boolean boostSuppressedForLowSnr;

        Response(String text, int sampleCount, int threads,
                 long decodeMs, long preprocessMs, long inferenceMs,
                 AudioPreprocessor.Result frontEnd) {
            this.text = text;
            this.sampleCount = sampleCount;
            this.threads = threads;
            this.decodeMs = decodeMs;
            this.preprocessMs = preprocessMs;
            this.inferenceMs = inferenceMs;

            this.rms = frontEnd.output.rms;
            this.peak = frontEnd.output.peak;
            this.clippedFraction = frontEnd.output.clippedFraction;

            this.inputRms = frontEnd.input.rms;
            this.inputPeak = frontEnd.input.peak;
            this.inputClippedFraction = frontEnd.input.clippedFraction;
            this.dcOffset = frontEnd.dcOffset;
            this.estimatedNoiseRms = frontEnd.estimatedNoiseRms;
            this.estimatedSpeechRms = frontEnd.estimatedSpeechRms;
            this.snrProxyDb = frontEnd.snrProxyDb;
            this.appliedGainDb = frontEnd.appliedGainDb;
            this.activeFrameFraction = frontEnd.activeFrameFraction;
            this.limitedSampleFraction = frontEnd.limitedSampleFraction;
            this.boostSuppressedForLowSnr = frontEnd.boostSuppressedForLowSnr;
        }
    }
}
