package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LocalWhisperEngine {
    /** Legacy large-v3 engine identifier retained for old transcript compatibility. */
    public static final String ENGINE_ID =
            "whisper.cpp-v1.9.1/large-v3-q5_0+frontend-v1+silero-v6.2.0+speech-chunks-v1";
    private static final String LANGUAGE = "ja";

    // Fewer whisper_full() calls are substantially faster on Android. Keep short conversational
    // pauses inside one original-timeline chunk, but cap one call to a reasonable long-form span.
    private static final long SPEECH_CHUNK_MERGE_GAP_MS = 2_000L;
    private static final long SPEECH_CHUNK_MAX_SPAN_MS = 60_000L;

    static {
        System.loadLibrary("whisper_jni");
    }

    private LocalWhisperEngine() {
    }

    public static String engineId(Context context) {
        TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
        return engineId(context, WhisperModelManager.selectedModelId(context), pipeline);
    }

    public static String engineId(Context context, String modelId,
                                  TranscriptionPipelineSettings.Snapshot pipeline) {
        return engineId(modelId) + "+" + pipeline.signature();
    }

    public static String engineId(String modelId) {
        String normalized = modelId == null || modelId.isEmpty()
                ? WhisperModelManager.MODEL_DEFAULT : modelId;
        return "whisper.cpp-v1.9.1/" + normalized
                + "+frontend-v1+silero-v6.2.0+speech-chunks-v1";
    }

    public static synchronized Response transcribe(Context context, File audioFile) throws Exception {
        return transcribe(context, audioFile, WhisperModelManager.selectedModelId(context));
    }

    public static synchronized Response transcribe(Context context, File audioFile,
                                                   String modelId) throws Exception {
        TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
        return transcribe(context, audioFile, modelId, pipeline);
    }

    public static synchronized Response transcribe(Context context, File audioFile,
                                                   String modelId,
                                                   TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {
        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);
        String segmentId = TranscriptionScheduler.extractSegmentId(audioFile.getName());
        RealtimeSpeechGateStore.Snapshot gate = segmentId == null
                ? RealtimeSpeechGateStore.Snapshot.missing()
                : RealtimeSpeechGateStore.read(context, segmentId);

        // New recordings can skip even M4A decode when AudioRecord PCM already proved definite
        // silence. This happens before Silero and before the Whisper model is touched.
        if (gate.available && gate.isRealtime() && gate.definiteSilence) {
            return gateSilenceResponse(context, modelId, gate, null);
        }

        PreparedAudio prepared = prepareAudio(audioFile);

        // Retained audio from older builds has no realtime sidecar. Run the same cheap gate on the
        // decoded PCM so backlog items also avoid scanning all five minutes with Silero.
        if (!gate.available) {
            gate = RealtimeSpeechGateStore.analyzeFloatPcm(prepared.frontEnd.samples);
        }
        if (gate.available && gate.definiteSilence) {
            return gateSilenceResponse(context, modelId, gate, prepared);
        }

        VadDiagnostics vad;
        if (TranscriptionPipelineSettings.VAD_STREAMING_SILERO.equals(pipeline.vadBackend)) {
            StreamingVadStore.Snapshot streaming = segmentId == null
                    ? StreamingVadStore.Snapshot.missing()
                    : StreamingVadStore.read(context, segmentId);
            if (!streaming.available || !streaming.complete) {
                streaming = StreamingVadStore.analyzeOffline(context, prepared.frontEnd.samples);
            }
            vad = analyzeStreamingVad(streaming, prepared.durationMs());
        } else {
            vad = gate.available && !gate.ranges.isEmpty()
                    ? analyzeVad(context, prepared, gate)
                    : analyzeVad(context, prepared);
        }
        return transcribePrepared(context, prepared, modelId, vad, gate, false, segmentId, pipeline);
    }

    static PreparedAudio prepareAudio(File audioFile) throws Exception {
        long decodeStarted = System.currentTimeMillis();
        float[] decodedSamples = M4aPcmDecoder.decode(audioFile);
        long decodedAt = System.currentTimeMillis();
        AudioPreprocessor.Result frontEnd = AudioPreprocessor.process(decodedSamples);
        long preprocessedAt = System.currentTimeMillis();
        return new PreparedAudio(frontEnd, decodedAt - decodeStarted, preprocessedAt - decodedAt);
    }

    /** Full-audio safety fallback for an activity gate that cannot produce usable candidate ranges. */
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
        JSONObject json = new JSONObject(raw);
        json.put("vadScope", "full-audio-fallback");
        json.put("vadInputMs", prepared.durationMs());
        json.put("activityGateSource", "none-or-ambiguous");
        return new VadDiagnostics(json);
    }

    /** Run Silero only over the original-timeline ranges selected by the cheap activity gate. */
    private static VadDiagnostics analyzeVad(Context context,
                                             PreparedAudio prepared,
                                             RealtimeSpeechGateStore.Snapshot gate) throws Exception {
        File vadModel = WhisperModelManager.vadModelFile(context);
        if (!WhisperModelManager.isVadReady(context)) {
            throw new IllegalStateException("Silero VAD model is not ready");
        }
        int threads = threadCount();
        long audioDurationMs = prepared.durationMs();
        long totalInitMs = 0L;
        long totalDetectMs = 0L;
        long vadInputMs = 0L;
        int probabilityCount = 0;
        double probabilitySum = 0.0;
        double probabilityMax = 0.0;
        double aboveThresholdCount = 0.0;
        JSONArray mappedSegments = new JSONArray();
        long totalSpeechMs = 0L;
        long lastEndMs = 0L;
        int candidateIndex = 0;

        for (RealtimeSpeechGateStore.Range candidate : gate.ranges) {
            long startMs = Math.max(0L, Math.min(audioDurationMs, candidate.startMs));
            long endMs = Math.max(0L, Math.min(audioDurationMs, candidate.endMs));
            if (endMs <= startMs) {
                candidateIndex++;
                continue;
            }

            int startSample = (int) Math.max(0L,
                    Math.min(prepared.frontEnd.samples.length, startMs * 16L));
            int endSample = (int) Math.max(0L,
                    Math.min(prepared.frontEnd.samples.length, endMs * 16L));
            if (endSample <= startSample) {
                candidateIndex++;
                continue;
            }

            float[] slice = Arrays.copyOfRange(prepared.frontEnd.samples, startSample, endSample);
            String raw = nativeAnalyzeVadDetailed(vadModel.getAbsolutePath(), slice, threads);
            if (raw == null) {
                throw new IllegalStateException("Candidate Silero VAD returned null");
            }
            JSONObject local = new JSONObject(raw);
            int localProbabilityCount = local.optInt("probabilityCount", 0);
            totalInitMs += Math.max(0L, local.optLong("vadInitMs", 0L));
            totalDetectMs += Math.max(0L, local.optLong("vadDetectMs", 0L));
            vadInputMs += endMs - startMs;
            probabilityCount += localProbabilityCount;
            probabilitySum += local.optDouble("meanSpeechProbability", 0.0) * localProbabilityCount;
            probabilityMax = Math.max(probabilityMax,
                    local.optDouble("maxSpeechProbability", 0.0));
            aboveThresholdCount += local.optDouble("aboveThresholdFraction", 0.0)
                    * localProbabilityCount;

            JSONArray localSegments = local.optJSONArray("segments");
            if (localSegments != null) {
                for (int i = 0; i < localSegments.length(); i++) {
                    JSONObject row = localSegments.optJSONObject(i);
                    if (row == null) continue;
                    long mappedStart = Math.max(startMs,
                            Math.min(endMs, startMs + row.optLong("startMs", 0L)));
                    long mappedEnd = Math.max(mappedStart,
                            Math.min(endMs, startMs + row.optLong("endMs", 0L)));
                    if (mappedEnd <= mappedStart) continue;
                    totalSpeechMs += mappedEnd - mappedStart;
                    lastEndMs = Math.max(lastEndMs, mappedEnd);
                    mappedSegments.put(new JSONObject()
                            .put("index", mappedSegments.length())
                            .put("startMs", mappedStart)
                            .put("endMs", mappedEnd)
                            .put("durationMs", mappedEnd - mappedStart)
                            .put("sourceCandidateIndex", candidateIndex)
                            .put("sourceCandidateStartMs", startMs)
                            .put("sourceCandidateEndMs", endMs));
                }
            }
            candidateIndex++;
        }

        JSONObject json = new JSONObject();
        json.put("vadInitMs", totalInitMs);
        json.put("vadDetectMs", totalDetectMs);
        json.put("probabilityCount", probabilityCount);
        json.put("meanSpeechProbability",
                probabilityCount == 0 ? 0.0 : probabilitySum / probabilityCount);
        json.put("maxSpeechProbability", probabilityMax);
        json.put("aboveThresholdFraction",
                probabilityCount == 0 ? 0.0 : aboveThresholdCount / probabilityCount);
        json.put("threshold", 0.5);
        json.put("timebase", "original-audio-segment-ms");
        json.put("segmentCount", mappedSegments.length());
        json.put("segments", mappedSegments);
        json.put("totalSpeechMs", totalSpeechMs);
        json.put("lastEndMs", lastEndMs);
        json.put("vadScope", gate.isRealtime() ? "realtime-candidates" : "offline-candidates");
        json.put("vadInputMs", vadInputMs);
        json.put("activityGateSource", gate.source);
        json.put("activityCandidateCount", gate.candidateCount);
        json.put("activityCandidateMs", gate.candidateMs);
        return new VadDiagnostics(json);
    }

    private static VadDiagnostics analyzeStreamingVad(StreamingVadStore.Snapshot streaming,
                                                       long audioDurationMs) throws Exception {
        if (streaming == null || !streaming.available) {
            throw new IllegalStateException("STREAMING_SILERO_RESULT_UNAVAILABLE");
        }
        JSONArray segments = new JSONArray();
        long speechMs = 0L;
        long lastEndMs = 0L;
        for (StreamingVadStore.Range range : streaming.ranges) {
            long start = Math.max(0L, Math.min(audioDurationMs, range.startMs));
            long end = Math.max(start, Math.min(audioDurationMs, range.endMs));
            if (end <= start) continue;
            speechMs += end - start;
            lastEndMs = Math.max(lastEndMs, end);
            segments.put(new JSONObject()
                    .put("index", segments.length())
                    .put("startMs", start)
                    .put("endMs", end)
                    .put("durationMs", end - start));
        }
        JSONObject json = new JSONObject()
                .put("vadInitMs", 0L)
                .put("vadDetectMs", streaming.detectMs)
                .put("vadInputMs", streaming.realtime ? 0L : audioDurationMs)
                .put("vadScope", streaming.realtime ? "streaming-realtime" : "streaming-offline")
                .put("probabilityCount", 0)
                .put("meanSpeechProbability", 0.0)
                .put("maxSpeechProbability", 0.0)
                .put("aboveThresholdFraction", 0.0)
                .put("threshold", 0.5)
                .put("segmentCount", segments.length())
                .put("segments", segments)
                .put("totalSpeechMs", speechMs)
                .put("lastEndMs", lastEndMs)
                .put("activityGateSource", streaming.source);
        return new VadDiagnostics(json);
    }

    static Response transcribePrepared(Context context, PreparedAudio prepared, String modelId) throws Exception {
        VadDiagnostics vad = analyzeVad(context, prepared);
        TranscriptionPipelineSettings.Snapshot comparison = new TranscriptionPipelineSettings.Snapshot(
                TranscriptionPipelineSettings.ASR_WHISPER_CPU,
                TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO,
                TranscriptionPipelineSettings.DENOISE_DEEPFILTER,
                TranscriptionPipelineSettings.SPEAKER_OFF);
        return transcribePrepared(context, prepared, modelId, vad,
                RealtimeSpeechGateStore.Snapshot.missing(), false, null, comparison);
    }

    static Response transcribePrepared(Context context, PreparedAudio prepared, String modelId,
                                       VadDiagnostics vad) throws Exception {
        TranscriptionPipelineSettings.Snapshot comparison = new TranscriptionPipelineSettings.Snapshot(
                TranscriptionPipelineSettings.ASR_WHISPER_CPU,
                TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO,
                TranscriptionPipelineSettings.DENOISE_DEEPFILTER,
                TranscriptionPipelineSettings.SPEAKER_OFF);
        return transcribePrepared(context, prepared, modelId, vad,
                RealtimeSpeechGateStore.Snapshot.missing(), false, null, comparison);
    }

    private static Response transcribePrepared(Context context,
                                               PreparedAudio prepared,
                                               String modelId,
                                               VadDiagnostics vad,
                                               RealtimeSpeechGateStore.Snapshot gate,
                                               boolean skippedByActivityGate,
                                               String segmentId,
                                               TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown model: " + modelId);
        }

        SpeechChunks chunks = SpeechChunks.fromVad(vad, prepared.durationMs());
        int threads = threadCount();
        File model = WhisperModelManager.modelFile(context, modelId);
        long modelBytes = model.isFile() ? model.length() : 0L;
        if (chunks.count() == 0) {
            return new Response("", prepared.frontEnd.samples.length, threads,
                    prepared.decodeMs, prepared.preprocessMs, 0L, prepared.frontEnd,
                    modelId, spec.label, modelBytes, 0, 0L, new JSONArray(),
                    0L, 0L, 0L, vad, chunks, prepared.durationMs(), true,
                    gate, skippedByActivityGate);
        }

        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);

        float[] asrSamples = prepared.frontEnd.samples;
        if (TranscriptionPipelineSettings.DENOISE_DEEPFILTER.equals(pipeline.denoiseBackend)) {
            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(
                    context,
                    segmentId,
                    prepared.frontEnd.samples,
                    chunks.startsMs,
                    chunks.endsMs,
                    prepared.frontEnd.snrProxyDb);
            asrSamples = denoise.samples;
        }

        long inferenceStarted = System.currentTimeMillis();
        JSONObject nativeResult;
        String responseModelId = modelId;
        String responseModelLabel = spec.label;
        long responseModelBytes = modelBytes;
        if (TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) {
            AndroidOnDeviceAsr.Result androidResult = AndroidOnDeviceAsr.transcribe(
                    context, asrSamples, chunks.startsMs, chunks.endsMs);
            nativeResult = new JSONObject()
                    .put("text", androidResult.text)
                    .put("segments", androidResult.segments)
                    .put("modelLoadMs", 0L)
                    .put("whisperFullMs", -1L)
                    .put("lastOutputEndMs", androidResult.lastOutputEndMs);
            responseModelId = "android-on-device-ja-JP";
            responseModelLabel = "Android on-device ASR";
            responseModelBytes = 0L;
        } else {
            boolean useGpu = TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(pipeline.asrBackend);
            String raw = nativeTranscribeDetailed(model.getAbsolutePath(), asrSamples,
                    chunks.startsMs, chunks.endsMs, LANGUAGE, threads, useGpu);
            if (raw == null) {
                throw new IllegalStateException("Local Whisper returned null");
            }
            nativeResult = new JSONObject(raw);
        }
        long inferenceMs = System.currentTimeMillis() - inferenceStarted;
        String text = nativeResult.optString("text", "").trim();
        JSONArray segments = nativeResult.optJSONArray("segments");
        int segmentCount = segments == null ? 0 : segments.length();
        long outputSegmentDurationMs = 0L;
        if (segments != null) {
            for (int i = 0; i < segments.length(); i++) {
                JSONObject row = segments.optJSONObject(i);
                if (row != null) {
                    outputSegmentDurationMs += Math.max(0L,
                            row.optLong("endMs") - row.optLong("startMs"));
                }
            }
        }

        return new Response(text, prepared.frontEnd.samples.length, threads,
                prepared.decodeMs, prepared.preprocessMs, inferenceMs, prepared.frontEnd,
                responseModelId, responseModelLabel, responseModelBytes, segmentCount, outputSegmentDurationMs,
                segments == null ? new JSONArray() : segments,
                nativeResult.optLong("modelLoadMs", -1L),
                nativeResult.optLong("whisperFullMs", -1L),
                nativeResult.optLong("lastOutputEndMs", 0L),
                vad, chunks, prepared.durationMs(), false, gate, skippedByActivityGate);
    }

    private static Response gateSilenceResponse(Context context,
                                                String modelId,
                                                RealtimeSpeechGateStore.Snapshot gate,
                                                PreparedAudio prepared) {
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        String label = spec == null ? modelId : spec.label;
        File model = WhisperModelManager.modelFile(context, modelId);
        long modelBytes = model.isFile() ? model.length() : 0L;
        long durationMs = prepared == null
                ? Math.max(0L, gate.segmentDurationMs)
                : prepared.durationMs();
        int sampleCount = prepared == null
                ? (int) Math.min(Integer.MAX_VALUE, durationMs * 16L)
                : prepared.frontEnd.samples.length;
        JSONObject vadJson = new JSONObject();
        try {
            vadJson.put("vadInitMs", 0L);
            vadJson.put("vadDetectMs", 0L);
            vadJson.put("probabilityCount", 0);
            vadJson.put("meanSpeechProbability", 0.0);
            vadJson.put("maxSpeechProbability", 0.0);
            vadJson.put("aboveThresholdFraction", 0.0);
            vadJson.put("threshold", 0.5);
            vadJson.put("segmentCount", 0);
            vadJson.put("segments", new JSONArray());
            vadJson.put("totalSpeechMs", 0L);
            vadJson.put("lastEndMs", 0L);
            vadJson.put("vadScope", gate.isRealtime()
                    ? "realtime-definite-silence" : "offline-definite-silence");
            vadJson.put("vadInputMs", 0L);
            vadJson.put("activityGateSource", gate.source);
            vadJson.put("activityCandidateCount", gate.candidateCount);
            vadJson.put("activityCandidateMs", gate.candidateMs);
        } catch (Exception ignored) {
        }
        VadDiagnostics vad = new VadDiagnostics(vadJson);
        SpeechChunks chunks = new SpeechChunks(new int[0], new int[0], 0L);
        return new Response("", sampleCount, threadCount(),
                prepared == null ? 0L : prepared.decodeMs,
                prepared == null ? 0L : prepared.preprocessMs,
                0L,
                prepared == null ? null : prepared.frontEnd,
                modelId, label, modelBytes, 0, 0L, new JSONArray(),
                0L, 0L, 0L, vad, chunks, durationMs, true, gate, true);
    }

    private static int threadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static native String nativeAnalyzeVadDetailed(String vadModelPath, float[] pcm, int threads);

    private static native String nativeTranscribeDetailed(String modelPath, float[] pcm,
                                                           int[] chunkStartsMs, int[] chunkEndsMs,
                                                           String language, int threads, boolean useGpu);

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
        public final long vadInputMs;
        public final String vadScope;
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
            this.vadInputMs = json.optLong("vadInputMs", -1L);
            this.vadScope = json.optString("vadScope", "full-audio");
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

    private static final class SpeechChunks {
        final int[] startsMs;
        final int[] endsMs;
        final long totalMs;

        SpeechChunks(int[] startsMs, int[] endsMs, long totalMs) {
            this.startsMs = startsMs;
            this.endsMs = endsMs;
            this.totalMs = totalMs;
        }

        int count() {
            return startsMs.length;
        }

        static SpeechChunks fromVad(VadDiagnostics vad, long audioDurationMs) {
            if (vad == null || vad.segments.length() == 0 || audioDurationMs <= 0L) {
                return new SpeechChunks(new int[0], new int[0], 0L);
            }

            List<Long> starts = new ArrayList<>();
            List<Long> ends = new ArrayList<>();
            long currentStart = -1L;
            long currentEnd = -1L;
            for (int i = 0; i < vad.segments.length(); i++) {
                JSONObject row = vad.segments.optJSONObject(i);
                if (row == null) continue;
                long start = Math.max(0L, Math.min(audioDurationMs, row.optLong("startMs", -1L)));
                long end = Math.max(0L, Math.min(audioDurationMs, row.optLong("endMs", -1L)));
                if (end <= start) continue;

                if (currentStart < 0L) {
                    currentStart = start;
                    currentEnd = end;
                } else if (start <= currentEnd + SPEECH_CHUNK_MERGE_GAP_MS
                        && Math.max(currentEnd, end) - currentStart <= SPEECH_CHUNK_MAX_SPAN_MS) {
                    currentEnd = Math.max(currentEnd, end);
                } else {
                    starts.add(currentStart);
                    ends.add(currentEnd);
                    currentStart = start;
                    currentEnd = end;
                }
            }
            if (currentStart >= 0L && currentEnd > currentStart) {
                starts.add(currentStart);
                ends.add(currentEnd);
            }

            int[] startArray = new int[starts.size()];
            int[] endArray = new int[ends.size()];
            long totalMs = 0L;
            for (int i = 0; i < starts.size(); i++) {
                long start = starts.get(i);
                long end = ends.get(i);
                startArray[i] = (int) Math.min(Integer.MAX_VALUE, start);
                endArray[i] = (int) Math.min(Integer.MAX_VALUE, end);
                totalMs += Math.max(0L, end - start);
            }
            return new SpeechChunks(startArray, endArray, totalMs);
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
        public final long vadInitMs;
        public final long vadDetectMs;
        public final long vadInputMs;
        public final String vadScope;
        public final int vadSegmentCount;
        public final long vadSpeechMs;
        public final int speechChunkCount;
        public final long speechInputMs;
        public final long skippedSilenceMs;
        public final long audioDurationMs;
        public final boolean skippedNoSpeech;
        public final String activityGateSource;
        public final int activityCandidateCount;
        public final long activityCandidateMs;
        public final boolean skippedByActivityGate;
        public final boolean realtimeGateUsed;
        public final int realtimeCandidateCount;
        public final long realtimeCandidateMs;
        public final boolean skippedByRealtimeGate;

        Response(String text, int sampleCount, int threads,
                 long decodeMs, long preprocessMs, long inferenceMs,
                 AudioPreprocessor.Result frontEnd, String modelId, String modelLabel,
                 long modelBytes, int segmentCount, long recognizedSpeechMs, JSONArray segments,
                 long modelLoadMs, long whisperFullMs, long lastOutputEndMs,
                 VadDiagnostics vad, SpeechChunks chunks, long audioDurationMs,
                 boolean skippedNoSpeech, RealtimeSpeechGateStore.Snapshot gate,
                 boolean skippedByActivityGate) {
            this.text = text;
            this.sampleCount = sampleCount;
            this.threads = threads;
            this.decodeMs = decodeMs;
            this.preprocessMs = preprocessMs;
            this.inferenceMs = inferenceMs;
            this.rms = frontEnd == null ? 0.0 : frontEnd.output.rms;
            this.peak = frontEnd == null ? 0.0 : frontEnd.output.peak;
            this.clippedFraction = frontEnd == null ? 0.0 : frontEnd.output.clippedFraction;
            this.inputRms = frontEnd == null ? 0.0 : frontEnd.input.rms;
            this.inputPeak = frontEnd == null ? 0.0 : frontEnd.input.peak;
            this.inputClippedFraction = frontEnd == null ? 0.0 : frontEnd.input.clippedFraction;
            this.dcOffset = frontEnd == null ? 0.0 : frontEnd.dcOffset;
            this.estimatedNoiseRms = frontEnd == null ? 0.0 : frontEnd.estimatedNoiseRms;
            this.estimatedSpeechRms = frontEnd == null ? 0.0 : frontEnd.estimatedSpeechRms;
            this.snrProxyDb = frontEnd == null ? 0.0 : frontEnd.snrProxyDb;
            this.appliedGainDb = frontEnd == null ? 0.0 : frontEnd.appliedGainDb;
            this.activeFrameFraction = frontEnd == null ? 0.0 : frontEnd.activeFrameFraction;
            this.limitedSampleFraction = frontEnd == null ? 0.0 : frontEnd.limitedSampleFraction;
            this.boostSuppressedForLowSnr = frontEnd != null && frontEnd.boostSuppressedForLowSnr;
            this.modelId = modelId;
            this.modelLabel = modelLabel;
            this.modelBytes = modelBytes;
            this.segmentCount = segmentCount;
            this.recognizedSpeechMs = recognizedSpeechMs;
            this.segments = segments;
            this.modelLoadMs = modelLoadMs;
            this.whisperFullMs = whisperFullMs;
            this.lastOutputEndMs = lastOutputEndMs;
            this.vadInitMs = vad == null ? -1L : vad.vadInitMs;
            this.vadDetectMs = vad == null ? -1L : vad.vadDetectMs;
            this.vadInputMs = vad == null ? -1L : vad.vadInputMs;
            this.vadScope = vad == null ? "none" : vad.vadScope;
            this.vadSegmentCount = vad == null ? 0 : vad.segmentCount;
            this.vadSpeechMs = vad == null ? 0L : vad.totalSpeechMs;
            this.speechChunkCount = chunks == null ? 0 : chunks.count();
            this.speechInputMs = chunks == null ? 0L : chunks.totalMs;
            this.audioDurationMs = Math.max(0L, audioDurationMs);
            this.skippedSilenceMs = Math.max(0L, this.audioDurationMs - this.speechInputMs);
            this.skippedNoSpeech = skippedNoSpeech;
            this.activityGateSource = gate == null ? "missing" : gate.source;
            this.activityCandidateCount = gate == null ? 0 : gate.candidateCount;
            this.activityCandidateMs = gate == null ? 0L : gate.candidateMs;
            this.skippedByActivityGate = skippedByActivityGate;
            this.realtimeGateUsed = gate != null && gate.available && gate.isRealtime();
            this.realtimeCandidateCount = this.realtimeGateUsed ? gate.candidateCount : 0;
            this.realtimeCandidateMs = this.realtimeGateUsed ? gate.candidateMs : 0L;
            this.skippedByRealtimeGate = skippedByActivityGate && this.realtimeGateUsed;
        }
    }
}
