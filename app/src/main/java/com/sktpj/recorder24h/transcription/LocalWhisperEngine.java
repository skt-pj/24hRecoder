package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public final class LocalWhisperEngine {
    public static final String ENGINE_ID = "whisper.cpp-v1.9.1/large-v3-q5_0+frontend-v1+silero-v6.2.0";
    private static final String LANGUAGE = "ja";

    static {
        System.loadLibrary("whisper_jni");
    }

    private LocalWhisperEngine() {
    }

    public static synchronized Response transcribe(Context context, File audioFile) throws Exception {
        PreparedAudio prepared = prepareAudio(audioFile);
        return transcribePrepared(context, prepared, WhisperModelManager.MODEL_DEFAULT);
    }

    static PreparedAudio prepareAudio(File audioFile) throws Exception {
        long decodeStarted = System.currentTimeMillis();
        float[] decodedSamples = M4aPcmDecoder.decode(audioFile);
        long decodedAt = System.currentTimeMillis();
        AudioPreprocessor.Result frontEnd = AudioPreprocessor.process(decodedSamples);
        long preprocessedAt = System.currentTimeMillis();
        return new PreparedAudio(frontEnd, decodedAt - decodeStarted, preprocessedAt - decodedAt);
    }

    static VadDiagnostics analyzeVad(Context context, PreparedAudio prepared) throws Exception {
        File vadModel = WhisperModelManager.vadModelFile(context);
        if (!WhisperModelManager.isVadReady(context)) {
            throw new IllegalStateException("Silero VAD model is not ready");
        }
        int threads = threadCount();
        String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), prepared.frontEnd.samples, threads);
        if (raw == null) {
            throw new IllegalStateException("Local VAD diagnostics returned null");
        }
        return new VadDiagnostics(new JSONObject(raw));
    }

    static Response transcribePrepared(Context context, PreparedAudio prepared, String modelId) throws Exception {
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown model: " + modelId);
        }
        File model = WhisperModelManager.modelFile(context, modelId);
        File vadModel = WhisperModelManager.vadModelFile(context);
        if (!WhisperModelManager.isComparisonReady(context, modelId)) {
            throw new IllegalStateException("Whisper model is not ready: " + modelId);
        }

        int threads = threadCount();
        long inferenceStarted = System.currentTimeMillis();
        String raw = nativeTranscribeDetailed(model.getAbsolutePath(), vadModel.getAbsolutePath(),
                prepared.frontEnd.samples, LANGUAGE, threads);
        long inferenceMs = System.currentTimeMillis() - inferenceStarted;
        if (raw == null) {
            throw new IllegalStateException("Local Whisper returned null");
        }

        JSONObject nativeResult = new JSONObject(raw);
        String text = nativeResult.optString("text", "").trim();
        JSONArray segments = nativeResult.optJSONArray("segments");
        int segmentCount = segments == null ? 0 : segments.length();
        long outputSegmentDurationMs = 0L;
        if (segments != null) {
            for (int i = 0; i < segments.length(); i++) {
                JSONObject row = segments.optJSONObject(i);
                if (row != null) {
                    outputSegmentDurationMs += Math.max(0L, row.optLong("endMs") - row.optLong("startMs"));
                }
            }
        }

        return new Response(text, prepared.frontEnd.samples.length, threads,
                prepared.decodeMs, prepared.preprocessMs, inferenceMs, prepared.frontEnd,
                modelId, spec.label, model.length(), segmentCount, outputSegmentDurationMs,
                segments == null ? new JSONArray() : segments,
                nativeResult.optLong("modelLoadMs", -1L),
                nativeResult.optLong("whisperFullMs", -1L),
                nativeResult.optLong("lastOutputEndMs", 0L));
    }

    private static int threadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static native String nativeAnalyzeVadDetailed(String vadModelPath, float[] pcm, int threads);

    private static native String nativeTranscribeDetailed(String modelPath, String vadModelPath,
                                                           float[] pcm, String language, int threads);

    static final class PreparedAudio {
        final AudioPreprocessor.Result frontEnd;
        final long decodeMs;
        final long preprocessMs;

        PreparedAudio(AudioPreprocessor.Result frontEnd, long decodeMs, long preprocessMs) {
            this.frontEnd = frontEnd;
            this.decodeMs = decodeMs;
            this.preprocessMs = preprocessMs;
        }

        long durationMs() {
            return Math.round(frontEnd.samples.length * 1000.0 / 16_000.0);
        }
    }

    public static final class VadDiagnostics {
        public final long vadInitMs;
        public final long vadDetectMs;
        public final int probabilityCount;
        public final double meanSpeechProbability;
        public final double maxSpeechProbability;
        public final double aboveThresholdFraction;
        public final double threshold;
        public final int segmentCount;
        public final long totalSpeechMs;
        public final long lastEndMs;
        public final JSONArray segments;
        private final JSONObject json;

        VadDiagnostics(JSONObject json) {
            this.json = json;
            this.vadInitMs = json.optLong("vadInitMs", -1L);
            this.vadDetectMs = json.optLong("vadDetectMs", -1L);
            this.probabilityCount = json.optInt("probabilityCount", 0);
            this.meanSpeechProbability = json.optDouble("meanSpeechProbability", 0.0);
            this.maxSpeechProbability = json.optDouble("maxSpeechProbability", 0.0);
            this.aboveThresholdFraction = json.optDouble("aboveThresholdFraction", 0.0);
            this.threshold = json.optDouble("threshold", 0.5);
            this.segmentCount = json.optInt("segmentCount", 0);
            this.totalSpeechMs = json.optLong("totalSpeechMs", 0L);
            this.lastEndMs = json.optLong("lastEndMs", 0L);
            JSONArray rows = json.optJSONArray("segments");
            this.segments = rows == null ? new JSONArray() : rows;
        }

        public JSONObject toJson() {
            try {
                return new JSONObject(json.toString());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    public static final class Response {
        public final String text;
        public final int sampleCount;
        public final int threads;
        public final long decodeMs;
        public final long preprocessMs;
        public final long inferenceMs;
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
        public final String modelId;
        public final String modelLabel;
        public final long modelBytes;
        public final int segmentCount;
        public final long recognizedSpeechMs;
        public final JSONArray segments;
        public final long modelLoadMs;
        public final long whisperFullMs;
        public final long lastOutputEndMs;

        Response(String text, int sampleCount, int threads,
                 long decodeMs, long preprocessMs, long inferenceMs,
                 AudioPreprocessor.Result frontEnd, String modelId, String modelLabel,
                 long modelBytes, int segmentCount, long recognizedSpeechMs, JSONArray segments,
                 long modelLoadMs, long whisperFullMs, long lastOutputEndMs) {
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
            this.modelId = modelId;
            this.modelLabel = modelLabel;
            this.modelBytes = modelBytes;
            this.segmentCount = segmentCount;
            this.recognizedSpeechMs = recognizedSpeechMs;
            this.segments = segments;
            this.modelLoadMs = modelLoadMs;
            this.whisperFullMs = whisperFullMs;
            this.lastOutputEndMs = lastOutputEndMs;
        }
    }
}
