package com.sktpj.recorder24h.transcription;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated :streaming_asr process. Receives already-captured PCM from the recorder process and
 * performs selected live ASR without opening another microphone. Final utterances are never
 * dropped; partial updates are intentionally skipped when inference is already backlogged.
 */
public final class StreamingTranscriptionService extends Service {
    public static final int MSG_RESET = 1;
    public static final int MSG_PCM = 2;
    public static final int MSG_BOUNDARY = 3;

    private static final long MIN_PARTIAL_SPEECH_US = 2_000_000L;
    private static final long PARTIAL_INTERVAL_US = 5_000_000L;
    private static final int BACKPRESSURE_WARN_DEPTH = 4;

    private final ExecutorService inference = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingInference = new AtomicInteger();
    private final PcmTimelineBuffer pcmBuffer = new PcmTimelineBuffer();
    private Messenger messenger;
    private SegmentAccumulator current;
    private long latestActiveSpeechStartUs = -1L;
    private long lastPartialRequestedEndUs;
    private long lastScheduledFinalEndUs;

    @Override
    public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleMessage));
        log("FULL_STREAMING_SERVICE_CREATED", null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        inference.shutdownNow();
        LiveWhisperSession.close();
        FullStreamingStateStore.writeLiveState(this, "OFF", null,
                "", "", "", null, 0, null);
        log("FULL_STREAMING_SERVICE_DESTROYED", null);
        super.onDestroy();
    }

    private boolean handleMessage(Message message) {
        try {
            switch (message.what) {
                case MSG_RESET:
                    handleReset(message.getData());
                    return true;
                case MSG_PCM:
                    handlePcm(message.getData());
                    return true;
                case MSG_BOUNDARY:
                    handleBoundary(message.getData());
                    return true;
                default:
                    return false;
            }
        } catch (Throwable error) {
            failCurrent("FULL_STREAMING_MESSAGE_FAILED", error);
            return true;
        }
    }

    private void handleReset(Bundle data) {
        long basePtsUs = data.getLong("basePtsUs", 0L);
        PipelineConfig config = PipelineConfig.fromBundle(data, "pipeline");
        pcmBuffer.reset(basePtsUs);
        current = new SegmentAccumulator(basePtsUs, config);
        latestActiveSpeechStartUs = -1L;
        lastPartialRequestedEndUs = basePtsUs;
        lastScheduledFinalEndUs = basePtsUs;
        enqueueConfigure(current);
        writeState("WAITING", "", "", current, null);
        log("FULL_STREAMING_LIVE_SESSION_RESET", config.toJson());
    }

    private void handlePcm(Bundle data) {
        if (current == null || !current.config.isLive()) return;
        byte[] pcm = data.getByteArray("pcm");
        if (pcm == null || pcm.length == 0) return;
        long startPtsUs = data.getLong("startPtsUs", -1L);
        long currentEndUs = data.getLong("currentEndUs", startPtsUs);
        pcmBuffer.append(startPtsUs, pcm);
        latestActiveSpeechStartUs = data.getLong("activeSpeechStartUs", -1L);

        long[] starts = data.getLongArray("closedStartsUs");
        long[] ends = data.getLongArray("closedEndsUs");
        if (starts != null && ends != null) {
            int count = Math.min(starts.length, ends.length);
            for (int i = 0; i < count; i++) {
                scheduleFinal(starts[i], ends[i], current);
            }
        }

        if (latestActiveSpeechStartUs >= 0L) {
            long speechStart = Math.max(current.basePtsUs,
                    Math.max(latestActiveSpeechStartUs, lastScheduledFinalEndUs));
            if (currentEndUs - speechStart >= MIN_PARTIAL_SPEECH_US
                    && currentEndUs - lastPartialRequestedEndUs >= PARTIAL_INTERVAL_US) {
                if (pendingInference.get() == 0) {
                    lastPartialRequestedEndUs = currentEndUs;
                    schedulePartial(speechStart, currentEndUs, current);
                } else {
                    logBackpressure("partial-skipped", currentEndUs);
                }
            }
        }
    }

    private void handleBoundary(Bundle data) {
        SegmentAccumulator old = current;
        long endPtsUs = data.getLong("segmentEndPtsUs", old == null ? 0L : old.basePtsUs);
        PipelineConfig nextConfig = PipelineConfig.fromBundle(data, "next");

        if (old != null && old.config.isLive()) {
            old.segmentId = data.getString("segmentId");
            old.startedAtMs = data.getLong("startedAtMs", 0L);
            old.endedAtMs = data.getLong("endedAtMs", 0L);
            old.endPtsUs = endPtsUs;
            if (latestActiveSpeechStartUs >= 0L) {
                long start = Math.max(old.basePtsUs,
                        Math.max(latestActiveSpeechStartUs, lastScheduledFinalEndUs));
                if (endPtsUs > start) scheduleFinal(start, endPtsUs, old);
            }
            submit("segment-finalize", () -> finalizeSegment(old));
        }

        pcmBuffer.trimBefore(endPtsUs);
        lastScheduledFinalEndUs = endPtsUs;
        lastPartialRequestedEndUs = endPtsUs;

        if (nextConfig.isLive()) {
            current = new SegmentAccumulator(endPtsUs, nextConfig);
            enqueueConfigure(current);
            writeState("WAITING", "", old == null ? "" : old.latestFinalText, current, null);
        } else {
            current = null;
            submit("live-session-close", LiveWhisperSession::close);
            FullStreamingStateStore.writeLiveState(this, "OFF", nextConfig.asrBackend,
                    "", old == null ? "" : old.latestFinalText,
                    old == null ? "" : old.accumulatedText.toString(),
                    old == null ? null : old.segments, pendingInference.get(), null);
        }
        log("FULL_STREAMING_SERVICE_BOUNDARY", new JSONObject()
                .put("segmentId", data.getString("segmentId"))
                .put("nextPipeline", nextConfig.toJson()));
    }

    private void enqueueConfigure(SegmentAccumulator accumulator) {
        submit("backend-configure", () -> {
            if (!accumulator.config.isLive()) return;
            try {
                TranscriptionPipelineSettings.requireRunnable(
                        this, accumulator.config.snapshot, accumulator.config.modelId);
                long loadMs = 0L;
                if (TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(accumulator.config.asrBackend)) {
                    LiveWhisperSession.close();
                } else {
                    loadMs = LiveWhisperSession.open(this, accumulator.config.modelId, accumulator.config.snapshot);
                }
                JSONObject details = accumulator.config.toJson().put("modelLoadMs", loadMs);
                log("FULL_STREAMING_BACKEND_READY", details);
                writeState("WAITING", "", accumulator.latestFinalText, accumulator, null);
            } catch (Throwable error) {
                markAccumulatorFailed(accumulator, "BACKEND_INIT_FAILED", error);
            }
        });
    }

    private void schedulePartial(long globalStartUs, long globalEndUs, SegmentAccumulator accumulator) {
        if (globalEndUs <= globalStartUs || accumulator.failed) return;
        float[] samples = pcmBuffer.sliceFloat(globalStartUs, globalEndUs);
        if (samples.length == 0) return;
        submit("partial", () -> {
            if (accumulator.failed) return;
            try {
                Recognition result = recognize(samples, accumulator, true);
                writeState("LIVE_PARTIAL", result.text, accumulator.latestFinalText, accumulator, null);
                log("FULL_STREAMING_PARTIAL", new JSONObject()
                        .put("startPtsUs", globalStartUs)
                        .put("endPtsUs", globalEndUs)
                        .put("textChars", result.text.length())
                        .put("inferenceMs", result.inferenceMs)
                        .put("queueDepth", pendingInference.get()));
            } catch (Throwable error) {
                markAccumulatorFailed(accumulator, "PARTIAL_ASR_FAILED", error);
            }
        });
    }

    private void scheduleFinal(long requestedStartUs, long requestedEndUs, SegmentAccumulator accumulator) {
        if (accumulator == null || accumulator.failed) return;
        long startUs = Math.max(accumulator.basePtsUs,
                Math.max(requestedStartUs, lastScheduledFinalEndUs));
        long endUs = Math.max(startUs, requestedEndUs);
        if (endUs <= startUs) return;
        float[] samples = pcmBuffer.sliceFloat(startUs, endUs);
        if (samples.length == 0) return;
        lastScheduledFinalEndUs = endUs;
        submit("final", () -> {
            if (accumulator.failed) return;
            try {
                Recognition result = recognize(samples, accumulator, false);
                accumulator.addFinal(result, startUs, endUs);
                writeState("FINAL", "", accumulator.latestFinalText, accumulator, null);
                log("FULL_STREAMING_FINAL", new JSONObject()
                        .put("startPtsUs", startUs)
                        .put("endPtsUs", endUs)
                        .put("textChars", result.text.length())
                        .put("inferenceMs", result.inferenceMs)
                        .put("queueDepth", pendingInference.get()));
            } catch (Throwable error) {
                markAccumulatorFailed(accumulator, "FINAL_ASR_FAILED", error);
            }
        });
        if (pendingInference.get() > BACKPRESSURE_WARN_DEPTH) {
            logBackpressure("final-queued", endUs);
        }
    }

    private Recognition recognize(float[] rawSamples, SegmentAccumulator accumulator, boolean partial)
            throws Exception {
        long started = System.currentTimeMillis();
        float[] copy = Arrays.copyOf(rawSamples, rawSamples.length);
        AudioPreprocessor.Result front = AudioPreprocessor.process(copy);
        float[] asrSamples = front.samples;
        int durationMs = Math.max(1, (int) (asrSamples.length * 1000L / 16_000L));

        if (TranscriptionPipelineSettings.DENOISE_DEEPFILTER.equals(accumulator.config.denoiseBackend)) {
            DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(
                    this, accumulator.segmentId, asrSamples,
                    new int[]{0}, new int[]{durationMs}, front.snrProxyDb);
            asrSamples = denoise.samples;
        }

        String text;
        JSONArray segments;
        long whisperFullMs = -1L;
        if (TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(accumulator.config.asrBackend)) {
            AndroidOnDeviceAsr.Result android = AndroidOnDeviceAsr.transcribe(
                    this, asrSamples, new int[]{0}, new int[]{durationMs});
            text = android.text;
            segments = new JSONArray(android.segments.toString());
        } else {
            LiveWhisperSession.Result whisper = LiveWhisperSession.transcribe(asrSamples);
            text = whisper.text;
            segments = new JSONArray(whisper.segments.toString());
            whisperFullMs = whisper.whisperFullMs;
        }

        if (!partial && TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU.equals(accumulator.config.speakerBackend)) {
            segments = SpeakerIdentifier.annotatePcm(this, asrSamples, segments);
        }
        return new Recognition(text == null ? "" : text.trim(), segments,
                Math.max(0L, System.currentTimeMillis() - started), whisperFullMs);
    }

    private void finalizeSegment(SegmentAccumulator accumulator) {
        if (accumulator.segmentId == null || accumulator.segmentId.isEmpty()) return;
        File audio = waitForPublishedAudio(accumulator.segmentId);
        String engineId = LocalWhisperEngine.engineId(this, accumulator.config.modelId, accumulator.config.snapshot);
        if (audio == null) {
            accumulator.failed = true;
            accumulator.error = "SEGMENT_READY_PUBLISH_TIMEOUT";
        }
        if (accumulator.failed) {
            String reason = accumulator.error == null ? "FULL_STREAMING_ASR_FAILED" : accumulator.error;
            FullStreamingStateStore.markFailed(this, accumulator.segmentId, engineId, reason);
            if (audio != null) {
                SegmentRepository.appendWithoutNotify(this, accumulator.segmentId, audio,
                        accumulator.startedAtMs, System.currentTimeMillis(),
                        "FAILED", "FULL_STREAMING_ASR_FAILED:" + reason);
            }
            log("FULL_STREAMING_SEGMENT_FAILED", new JSONObject()
                    .put("segmentId", accumulator.segmentId)
                    .put("reason", reason)
                    .put("automaticFallback", false));
            return;
        }

        try {
            TranscriptionRepository.save(this, accumulator.segmentId, audio, engineId,
                    accumulator.accumulatedText.toString().trim(), accumulator.segments);
            SegmentRepository.appendWithoutNotify(this, accumulator.segmentId, audio,
                    accumulator.startedAtMs, System.currentTimeMillis(), "TRANSCRIBED", null);
            FullStreamingStateStore.markFinal(this, accumulator.segmentId, engineId);
            log("FULL_STREAMING_SEGMENT_SAVED", new JSONObject()
                    .put("segmentId", accumulator.segmentId)
                    .put("textChars", accumulator.accumulatedText.length())
                    .put("segmentCount", accumulator.segments.length())
                    .put("engineId", engineId)
                    .put("automaticFallback", false));
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName() + ":" + safeMessage(error);
            FullStreamingStateStore.markFailed(this, accumulator.segmentId, engineId, reason);
            SegmentRepository.appendWithoutNotify(this, accumulator.segmentId, audio,
                    accumulator.startedAtMs, System.currentTimeMillis(),
                    "FAILED", "FULL_STREAMING_SAVE_FAILED");
            logError("FULL_STREAMING_SEGMENT_SAVE_FAILED", error, accumulator.segmentId);
        }
    }

    private File waitForPublishedAudio(String segmentId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            List<SegmentRecord> records = SegmentHistoryRepository.load(this);
            for (SegmentRecord record : records) {
                if (!segmentId.equals(record.getSegmentId())) continue;
                if (record.getAudioAvailable() && record.getAudioPath() != null
                        && "READY".equals(record.getStatus())) {
                    return new File(record.getAudioPath());
                }
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void submit(String kind, Runnable runnable) {
        int depth = pendingInference.incrementAndGet();
        try {
            inference.submit(() -> {
                try {
                    runnable.run();
                } finally {
                    pendingInference.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            pendingInference.decrementAndGet();
            failCurrent("INFERENCE_EXECUTOR_REJECTED:" + kind, rejected);
        }
        if (depth > BACKPRESSURE_WARN_DEPTH) logBackpressure(kind, 0L);
    }

    private void markAccumulatorFailed(SegmentAccumulator accumulator, String reason, Throwable error) {
        accumulator.failed = true;
        accumulator.error = reason + ":" + error.getClass().getSimpleName() + ":" + safeMessage(error);
        writeState("ERROR", "", accumulator.latestFinalText, accumulator, accumulator.error);
        logError("FULL_STREAMING_ASR_FAILED", error, accumulator.segmentId);
    }

    private void failCurrent(String reason, Throwable error) {
        SegmentAccumulator accumulator = current;
        if (accumulator != null) markAccumulatorFailed(accumulator, reason,
                error == null ? new IllegalStateException(reason) : error);
        else logError("FULL_STREAMING_SERVICE_FAILED",
                error == null ? new IllegalStateException(reason) : error, null);
    }

    private void writeState(String state, String partial, String latestFinal,
                            SegmentAccumulator accumulator, String error) {
        FullStreamingStateStore.writeLiveState(this, state,
                accumulator == null ? null : accumulator.config.asrBackend,
                partial, latestFinal,
                accumulator == null ? "" : accumulator.accumulatedText.toString(),
                accumulator == null ? null : accumulator.segments,
                pendingInference.get(), error);
    }

    private void logBackpressure(String kind, long ptsUs) {
        try {
            AppLogger.event(this, "FULL_STREAMING_ASR_BACKPRESSURE", new JSONObject()
                    .put("kind", kind)
                    .put("ptsUs", ptsUs)
                    .put("queueDepth", pendingInference.get())
                    .put("partialMayBeSkipped", true)
                    .put("finalDropped", false)
                    .put("automaticFallback", false));
        } catch (Exception ignored) {
        }
    }

    private void log(String event, JSONObject details) {
        try {
            JSONObject row = details == null ? new JSONObject() : details;
            row.put("process", "streaming_asr");
            row.put("automaticFallback", false);
            AppLogger.event(this, event, row);
        } catch (Exception ignored) {
        }
    }

    private void logError(String event, Throwable error, String segmentId) {
        try {
            log(event, new JSONObject()
                    .put("segmentId", segmentId == null ? JSONObject.NULL : segmentId)
                    .put("error", error.getClass().getSimpleName())
                    .put("message", safeMessage(error)));
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null ? "" : value;
    }

    private static final class PipelineConfig {
        final String mode;
        final String asrBackend;
        final String vadBackend;
        final String denoiseBackend;
        final String speakerBackend;
        final String modelId;
        final TranscriptionPipelineSettings.Snapshot snapshot;

        PipelineConfig(String mode, String asrBackend, String vadBackend,
                       String denoiseBackend, String speakerBackend, String modelId) {
            this.mode = mode;
            this.asrBackend = asrBackend;
            this.vadBackend = vadBackend;
            this.denoiseBackend = denoiseBackend;
            this.speakerBackend = speakerBackend;
            this.modelId = modelId;
            this.snapshot = new TranscriptionPipelineSettings.Snapshot(
                    mode, asrBackend, vadBackend, denoiseBackend, speakerBackend);
        }

        static PipelineConfig fromBundle(Bundle data, String prefix) {
            return new PipelineConfig(
                    data.getString(prefix + "Mode", TranscriptionPipelineSettings.MODE_SEGMENT_POSTPROCESS),
                    data.getString(prefix + "Asr", TranscriptionPipelineSettings.ASR_WHISPER_CPU),
                    data.getString(prefix + "Vad", TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO),
                    data.getString(prefix + "Denoise", TranscriptionPipelineSettings.DENOISE_DEEPFILTER),
                    data.getString(prefix + "Speaker", TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU),
                    data.getString(prefix + "ModelId", WhisperModelManager.MODEL_DEFAULT));
        }

        boolean isLive() {
            return TranscriptionPipelineSettings.MODE_LIVE_STREAMING.equals(snapshot.executionMode);
        }

        JSONObject toJson() {
            JSONObject json = snapshot.toJson();
            try { json.put("modelId", modelId); } catch (Exception ignored) { }
            return json;
        }
    }

    private static final class SegmentAccumulator {
        final long basePtsUs;
        final PipelineConfig config;
        final StringBuilder accumulatedText = new StringBuilder();
        final JSONArray segments = new JSONArray();
        volatile boolean failed;
        volatile String error;
        volatile String latestFinalText = "";
        String segmentId;
        long startedAtMs;
        long endedAtMs;
        long endPtsUs;

        SegmentAccumulator(long basePtsUs, PipelineConfig config) {
            this.basePtsUs = basePtsUs;
            this.config = config;
        }

        void addFinal(Recognition result, long globalStartUs, long globalEndUs) throws Exception {
            latestFinalText = result.text;
            if (!result.text.isEmpty()) {
                if (accumulatedText.length() > 0) accumulatedText.append(' ');
                accumulatedText.append(result.text);
            }
            long utteranceStartMs = Math.max(0L, (globalStartUs - basePtsUs) / 1000L);
            long utteranceDurationMs = Math.max(0L, (globalEndUs - globalStartUs) / 1000L);
            if (result.segments.length() == 0 && !result.text.isEmpty()) {
                segments.put(new JSONObject()
                        .put("startMs", utteranceStartMs)
                        .put("endMs", utteranceStartMs + utteranceDurationMs)
                        .put("durationMs", utteranceDurationMs)
                        .put("text", result.text)
                        .put("asrBackend", config.asrBackend)
                        .put("streamingFinal", true));
                return;
            }
            for (int i = 0; i < result.segments.length(); i++) {
                JSONObject local = result.segments.optJSONObject(i);
                if (local == null) continue;
                long localStart = Math.max(0L, local.optLong("startMs", 0L));
                long localEnd = Math.max(localStart, local.optLong("endMs", utteranceDurationMs));
                long mappedStart = utteranceStartMs + Math.min(utteranceDurationMs, localStart);
                long mappedEnd = utteranceStartMs + Math.min(utteranceDurationMs, localEnd);
                segments.put(new JSONObject(local.toString())
                        .put("startMs", mappedStart)
                        .put("endMs", Math.max(mappedStart, mappedEnd))
                        .put("durationMs", Math.max(0L, mappedEnd - mappedStart))
                        .put("asrBackend", config.asrBackend)
                        .put("streamingFinal", true));
            }
        }
    }

    private static final class Recognition {
        final String text;
        final JSONArray segments;
        final long inferenceMs;
        final long whisperFullMs;

        Recognition(String text, JSONArray segments, long inferenceMs, long whisperFullMs) {
            this.text = text;
            this.segments = segments;
            this.inferenceMs = inferenceMs;
            this.whisperFullMs = whisperFullMs;
        }
    }

    /** Single-segment PCM timeline buffer; final tasks copy slices before the buffer is trimmed. */
    private static final class PcmTimelineBuffer {
        private static final long BYTES_PER_SECOND = 16_000L * 2L;
        private byte[] data = new byte[64 * 1024];
        private int size;
        private long basePtsUs;

        synchronized void reset(long basePtsUs) {
            this.basePtsUs = basePtsUs;
            this.size = 0;
        }

        synchronized void append(long startPtsUs, byte[] bytes) {
            if (bytes == null || bytes.length == 0) return;
            long desiredLong = (startPtsUs - basePtsUs) * BYTES_PER_SECOND / 1_000_000L;
            if (desiredLong < 0L) return;
            int desired = (int) Math.min(Integer.MAX_VALUE, desiredLong & ~1L);
            int sourceOffset = 0;
            if (desired < size) {
                sourceOffset = Math.min(bytes.length, size - desired);
                desired = size;
            }
            if (desired > size) {
                ensure(desired);
                Arrays.fill(data, size, desired, (byte) 0);
                size = desired;
            }
            int count = bytes.length - sourceOffset;
            if (count <= 0) return;
            ensure(size + count);
            System.arraycopy(bytes, sourceOffset, data, size, count);
            size += count;
        }

        synchronized float[] sliceFloat(long startPtsUs, long endPtsUs) {
            if (endPtsUs <= startPtsUs || size <= 1) return new float[0];
            long startLong = (startPtsUs - basePtsUs) * BYTES_PER_SECOND / 1_000_000L;
            long endLong = (endPtsUs - basePtsUs) * BYTES_PER_SECOND / 1_000_000L;
            int start = (int) Math.max(0L, Math.min(size, startLong)) & ~1;
            int end = (int) Math.max(start, Math.min(size, endLong)) & ~1;
            int samples = (end - start) / 2;
            float[] output = new float[samples];
            for (int i = 0; i < samples; i++) {
                int lo = data[start + i * 2] & 0xff;
                int hi = data[start + i * 2 + 1];
                short pcm = (short) ((hi << 8) | lo);
                output[i] = pcm / 32768.0f;
            }
            return output;
        }

        synchronized void trimBefore(long ptsUs) {
            long cutLong = (ptsUs - basePtsUs) * BYTES_PER_SECOND / 1_000_000L;
            int cut = (int) Math.max(0L, Math.min(size, cutLong)) & ~1;
            if (cut > 0 && cut < size) System.arraycopy(data, cut, data, 0, size - cut);
            size = Math.max(0, size - cut);
            basePtsUs = ptsUs;
        }

        private void ensure(int needed) {
            if (needed <= data.length) return;
            int next = data.length;
            while (next < needed) next = Math.max(next * 2, needed);
            data = Arrays.copyOf(data, next);
        }
    }
}
