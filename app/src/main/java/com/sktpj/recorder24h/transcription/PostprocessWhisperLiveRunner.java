package com.sktpj.recorder24h.transcription;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Full-streaming Whisper route that deliberately reuses the normal five-minute JNI entrypoint.
 * It does not keep a live Whisper context. Each utterance loads, runs and frees the model through
 * nativeTranscribeDetailed(), exactly like the proven postprocess path, while still receiving
 * already-captured live PCM. No backend fallback is performed here.
 */
final class PostprocessWhisperLiveRunner {
    private static final String LANGUAGE = "ja";

    private PostprocessWhisperLiveRunner() {
    }

    static Result transcribe(Context context, float[] samples, String modelId,
                             TranscriptionPipelineSettings.Snapshot pipeline) throws Exception {
        if (pipeline == null || TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) {
            throw new IllegalArgumentException("POSTPROCESS_LIVE_WHISPER_REQUIRES_WHISPER_BACKEND");
        }
        TranscriptionPipelineSettings.requireRunnable(context, pipeline, modelId);
        if (samples == null || samples.length == 0) {
            return new Result("", new JSONArray(), 0L, 0L, 0L);
        }

        LiveWhisperNoSpeechGuard.InputStats input = LiveWhisperNoSpeechGuard.measure(samples);
        if (LiveWhisperNoSpeechGuard.shouldSkipBeforeAsr(
                context, TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE, input)) {
            return new Result("", new JSONArray(), 0L, 0L, 0L);
        }

        File model = WhisperModelManager.modelFile(context, modelId);
        if (!model.isFile()) throw new IllegalStateException("LOCAL_WHISPER_MODEL_MISSING");

        int durationMs = Math.max(1, (int) (samples.length * 1000L / 16_000L));
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        boolean useGpu = TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(pipeline.asrBackend);
        long cancellationToken = TranscriptionCancellation.snapshot();
        LocalWhisperEngine.setNativePostprocessCancellationGeneration(cancellationToken);

        String raw = LocalWhisperEngine.nativeTranscribeDetailed(
                model.getAbsolutePath(), samples,
                new int[]{0}, new int[]{durationMs},
                LANGUAGE, threads, useGpu, cancellationToken);
        if (raw == null) throw new IllegalStateException("POSTPROCESS_LIVE_WHISPER_RETURNED_NULL");
        JSONObject json = new JSONObject(raw);
        JSONArray segments = json.optJSONArray("segments");
        LiveWhisperNoSpeechGuard.Output filtered = LiveWhisperNoSpeechGuard.filterAfterAsr(
                context,
                TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE,
                input,
                json.optString("text", ""),
                segments == null ? new JSONArray() : segments);
        return new Result(
                filtered.text,
                filtered.segments,
                json.optLong("modelLoadMs", -1L),
                json.optLong("whisperFullMs", -1L),
                json.optLong("lastOutputEndMs", 0L));
    }

    static final class Result {
        final String text;
        final JSONArray segments;
        final long modelLoadMs;
        final long whisperFullMs;
        final long lastOutputEndMs;

        Result(String text, JSONArray segments, long modelLoadMs,
               long whisperFullMs, long lastOutputEndMs) {
            this.text = text;
            this.segments = segments;
            this.modelLoadMs = modelLoadMs;
            this.whisperFullMs = whisperFullMs;
            this.lastOutputEndMs = lastOutputEndMs;
        }
    }
}
