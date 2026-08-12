package com.sktpj.recorder24h.transcription;

import android.content.Context;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Stateful Silero VAD path. During recording it feeds AudioRecord PCM into whisper.cpp's
 * no-reset streaming VAD and stores ranges on the original AudioRecord PTS timebase.
 *
 * If a historical segment has no complete realtime sidecar, the same selected streaming
 * algorithm can be replayed offline. That is not a fallback to candidate VAD.
 */
public final class StreamingVadStore {
    private static final Object LOCK = new Object();
    private static final String DIR_NAME = "streaming-vad";
    private static final int SAMPLE_RATE = 16_000;
    private static final double THRESHOLD = 0.5;
    private static final long MIN_SPEECH_US = 250_000L;
    private static final long MIN_SILENCE_US = 200_000L;
    private static final long SPEECH_PAD_US = 80_000L;
    private static final long MAX_SPEECH_US = 30_000_000L;

    private static boolean nativeReady;
    private static String activeMode = "";
    private static String initError;
    private static long streamBasePtsUs = -1L;
    private static long streamConfiguredAtPtsUs = -1L;
    private static long processedFrameCount;
    private static int frameWindowSamples = 512;
    private static DetectorState detector = new DetectorState();

    static {
        System.loadLibrary("whisper_jni");
    }

    private StreamingVadStore() {
    }

    public static void resetStream(Context context) {
        synchronized (LOCK) {
            closeNativeLocked();
            activeMode = "";
            initError = null;
            streamBasePtsUs = -1L;
            streamConfiguredAtPtsUs = -1L;
            processedFrameCount = 0L;
            frameWindowSamples = 512;
            detector = new DetectorState();
            ensureConfiguredLocked(context, 0L);
        }
    }

    public static void observePcm16(Context context, byte[] bytes, int length, long startPtsUs) {
        if (bytes == null || length < 2) return;
        synchronized (LOCK) {
            ensureConfiguredLocked(context, startPtsUs);
            if (!TranscriptionPipelineSettings.VAD_STREAMING_SILERO.equals(activeMode) || !nativeReady) {
                return;
            }
            if (streamBasePtsUs < 0L) streamBasePtsUs = startPtsUs;

            int sampleCount = length / 2;
            float[] samples = new float[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int lo = bytes[i * 2] & 0xff;
                int hi = bytes[i * 2 + 1];
                short pcm = (short) ((hi << 8) | lo);
                samples[i] = pcm / 32768.0f;
            }

            try {
                String raw = nativeStreamingVadProcess(samples);
                if (raw == null) throw new IllegalStateException("streaming VAD returned null");
                JSONObject json = new JSONObject(raw);
                int window = json.optInt("windowSamples", frameWindowSamples);
                if (window > 0) frameWindowSamples = window;
                JSONArray probabilities = json.optJSONArray("probabilities");
                if (probabilities == null) return;
                long frameUs = Math.max(1L, frameWindowSamples * 1_000_000L / SAMPLE_RATE);
                for (int i = 0; i < probabilities.length(); i++) {
                    long frameStartUs = streamBasePtsUs + processedFrameCount * frameUs;
                    detector.accept(probabilities.optDouble(i, 0.0), frameStartUs, frameStartUs + frameUs);
                    processedFrameCount++;
                }
            } catch (Exception error) {
                initError = error.getClass().getSimpleName() + ": " + safeMessage(error);
                nativeReady = false;
                closeNativeLocked();
                log(context, "STREAMING_VAD_RUNTIME_FAILED", details(
                        "error", error.getClass().getSimpleName(),
                        "message", safeMessage(error)));
            }
        }
    }

    public static void persistSegment(
            Context context,
            String segmentId,
            long segmentBasePtsUs,
            long segmentEndPtsUs,
            long startedAtMs,
            long endedAtMs) {
        if (segmentId == null || segmentId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                List<RangeUs> source = detector.snapshotRanges(segmentEndPtsUs);
                JSONArray ranges = new JSONArray();
                long totalMs = 0L;
                for (RangeUs range : source) {
                    long start = Math.max(segmentBasePtsUs, range.startUs);
                    long end = Math.min(segmentEndPtsUs, range.endUs);
                    if (end <= start) continue;
                    long relativeStartMs = Math.max(0L, (start - segmentBasePtsUs) / 1000L);
                    long relativeEndMs = Math.max(relativeStartMs, (end - segmentBasePtsUs) / 1000L);
                    totalMs += relativeEndMs - relativeStartMs;
                    ranges.put(new JSONObject()
                            .put("startMs", relativeStartMs)
                            .put("endMs", relativeEndMs)
                            .put("durationMs", relativeEndMs - relativeStartMs)
                            .put("startAtMs", startedAtMs + relativeStartMs)
                            .put("endAtMs", startedAtMs + relativeEndMs));
                }
                boolean modeSelected = TranscriptionPipelineSettings.VAD_STREAMING_SILERO.equals(activeMode);
                boolean complete = modeSelected && nativeReady
                        && streamConfiguredAtPtsUs >= 0L
                        && streamConfiguredAtPtsUs <= segmentBasePtsUs;
                JSONObject root = new JSONObject()
                        .put("schemaVersion", 1)
                        .put("source", "whisper-silero-streaming")
                        .put("timebase", "original-audiorecord-pts-ms")
                        .put("available", modeSelected && nativeReady)
                        .put("completeForSegment", complete)
                        .put("error", initError == null ? JSONObject.NULL : initError)
                        .put("segmentStartedAtMs", startedAtMs)
                        .put("segmentEndedAtMs", endedAtMs)
                        .put("segmentBasePtsUs", segmentBasePtsUs)
                        .put("segmentEndPtsUs", segmentEndPtsUs)
                        .put("rangeCount", ranges.length())
                        .put("speechMs", totalMs)
                        .put("ranges", ranges);
                writeAtomic(sidecarFile(context, segmentId), root.toString());
                log(context, "STREAMING_VAD_SEGMENT", new JSONObject(root.toString())
                        .put("segmentId", segmentId));

                detector.pruneBefore(segmentBasePtsUs - SPEECH_PAD_US);
            } catch (Exception error) {
                log(context, "STREAMING_VAD_SEGMENT_PERSIST_FAILED", details(
                        "segmentId", segmentId,
                        "error", error.getClass().getSimpleName(),
                        "message", safeMessage(error)));
            }
        }
    }

    public static Snapshot read(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return Snapshot.missing();
        File file = sidecarFile(context, segmentId);
        if (!file.isFile()) return Snapshot.missing();
        try {
            JSONObject root = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return Snapshot.fromJson(root, true, 0L);
        } catch (Exception ignored) {
            return Snapshot.missing();
        }
    }

    public static Snapshot analyzeOffline(Context context, float[] samples) throws Exception {
        if (samples == null || samples.length == 0) {
            return new Snapshot(true, false, true, "streaming-silero-offline", 0L,
                    new ArrayList<>(), null);
        }
        synchronized (LOCK) {
            closeNativeLocked();
            File model = WhisperModelManager.vadModelFile(context);
            if (!WhisperModelManager.isVadReady(context)) {
                throw new IllegalStateException("SILERO_VAD_MODEL_MISSING");
            }
            if (!nativeStreamingVadOpen(model.getAbsolutePath(), threadCount())) {
                throw new IllegalStateException("STREAMING_SILERO_INIT_FAILED");
            }
            nativeReady = true;
            DetectorState localDetector = new DetectorState();
            long startedAt = System.currentTimeMillis();
            long frameIndex = 0L;
            int offset = 0;
            try {
                while (offset < samples.length) {
                    int count = Math.min(1600, samples.length - offset);
                    float[] chunk = new float[count];
                    System.arraycopy(samples, offset, chunk, 0, count);
                    String raw = nativeStreamingVadProcess(chunk);
                    if (raw == null) throw new IllegalStateException("STREAMING_SILERO_OFFLINE_NULL");
                    JSONObject json = new JSONObject(raw);
                    int window = json.optInt("windowSamples", 512);
                    long frameUs = Math.max(1L, window * 1_000_000L / SAMPLE_RATE);
                    JSONArray probabilities = json.optJSONArray("probabilities");
                    if (probabilities != null) {
                        for (int i = 0; i < probabilities.length(); i++) {
                            long startUs = frameIndex * frameUs;
                            localDetector.accept(probabilities.optDouble(i, 0.0), startUs, startUs + frameUs);
                            frameIndex++;
                        }
                    }
                    offset += count;
                }
                long durationUs = samples.length * 1_000_000L / SAMPLE_RATE;
                List<Range> ranges = toMsRanges(localDetector.snapshotRanges(durationUs));
                return new Snapshot(true, false, true, "streaming-silero-offline",
                        Math.max(0L, System.currentTimeMillis() - startedAt), ranges, null);
            } finally {
                closeNativeLocked();
                nativeReady = false;
                activeMode = "";
            }
        }
    }

    private static void ensureConfiguredLocked(Context context, long currentPtsUs) {
        String selected = TranscriptionPipelineSettings.snapshot(context).vadBackend;
        if (selected.equals(activeMode)) return;

        closeNativeLocked();
        nativeReady = false;
        activeMode = selected;
        initError = null;
        streamBasePtsUs = -1L;
        streamConfiguredAtPtsUs = currentPtsUs;
        processedFrameCount = 0L;
        detector = new DetectorState();

        if (!TranscriptionPipelineSettings.VAD_STREAMING_SILERO.equals(selected)) return;
        if (!WhisperModelManager.isVadReady(context)) {
            initError = "SILERO_VAD_MODEL_MISSING";
            log(context, "STREAMING_VAD_UNAVAILABLE", details("reason", initError));
            return;
        }
        try {
            nativeReady = nativeStreamingVadOpen(
                    WhisperModelManager.vadModelFile(context).getAbsolutePath(), threadCount());
            if (!nativeReady) initError = "STREAMING_SILERO_INIT_FAILED";
            log(context, nativeReady ? "STREAMING_VAD_READY" : "STREAMING_VAD_UNAVAILABLE",
                    details("reason", initError == null ? JSONObject.NULL : initError));
        } catch (Exception error) {
            nativeReady = false;
            initError = error.getClass().getSimpleName() + ": " + safeMessage(error);
            log(context, "STREAMING_VAD_UNAVAILABLE", details("reason", initError));
        }
    }

    private static List<Range> toMsRanges(List<RangeUs> rangesUs) {
        List<Range> result = new ArrayList<>();
        for (RangeUs row : rangesUs) {
            long startMs = Math.max(0L, row.startUs / 1000L);
            long endMs = Math.max(startMs, row.endUs / 1000L);
            if (endMs > startMs) result.add(new Range(startMs, endMs));
        }
        return result;
    }

    private static File sidecarFile(Context context, String segmentId) {
        File dir = new File(context.getFilesDir(), "metadata/" + DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, segmentId + ".json");
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace streaming VAD sidecar");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Unable to finalize streaming VAD sidecar");
        }
    }

    private static void closeNativeLocked() {
        try {
            nativeStreamingVadClose();
        } catch (Throwable ignored) {
        }
    }

    private static int threadCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static JSONObject details(Object... values) {
        JSONObject json = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try {
                json.put(String.valueOf(values[i]), values[i + 1]);
            } catch (Exception ignored) {
            }
        }
        return json;
    }

    private static void log(Context context, String event, JSONObject details) {
        try {
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null ? "" : value;
    }

    private static native boolean nativeStreamingVadOpen(String modelPath, int threads);
    private static native String nativeStreamingVadProcess(float[] pcm);
    private static native void nativeStreamingVadClose();

    private static final class DetectorState {
        private final List<RangeUs> closed = new ArrayList<>();
        private long pendingStartUs = -1L;
        private long pendingSpeechUs;
        private long activeStartUs = -1L;
        private long lastSpeechEndUs = -1L;
        private long silenceUs;

        void accept(double probability, long frameStartUs, long frameEndUs) {
            long frameUs = Math.max(1L, frameEndUs - frameStartUs);
            if (probability >= THRESHOLD) {
                silenceUs = 0L;
                if (activeStartUs < 0L) {
                    if (pendingStartUs < 0L) pendingStartUs = frameStartUs;
                    pendingSpeechUs += frameUs;
                    if (pendingSpeechUs >= MIN_SPEECH_US) {
                        activeStartUs = Math.max(0L, pendingStartUs - SPEECH_PAD_US);
                        lastSpeechEndUs = frameEndUs;
                    }
                } else {
                    lastSpeechEndUs = frameEndUs;
                    if (frameEndUs - activeStartUs >= MAX_SPEECH_US) {
                        closeAt(frameEndUs + SPEECH_PAD_US);
                        pendingStartUs = frameStartUs;
                        pendingSpeechUs = frameUs;
                    }
                }
            } else if (activeStartUs >= 0L) {
                silenceUs += frameUs;
                if (silenceUs >= MIN_SILENCE_US) {
                    closeAt(lastSpeechEndUs + SPEECH_PAD_US);
                }
            } else {
                pendingStartUs = -1L;
                pendingSpeechUs = 0L;
            }
        }

        List<RangeUs> snapshotRanges(long currentEndUs) {
            List<RangeUs> result = new ArrayList<>(closed);
            if (activeStartUs >= 0L) {
                long end = Math.max(activeStartUs, Math.min(currentEndUs, lastSpeechEndUs + SPEECH_PAD_US));
                if (end > activeStartUs) result.add(new RangeUs(activeStartUs, end));
            }
            return result;
        }

        void pruneBefore(long cutoffUs) {
            Iterator<RangeUs> iterator = closed.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().endUs < cutoffUs) iterator.remove();
            }
        }

        private void closeAt(long endUs) {
            if (activeStartUs >= 0L && endUs > activeStartUs) {
                closed.add(new RangeUs(activeStartUs, endUs));
            }
            pendingStartUs = -1L;
            pendingSpeechUs = 0L;
            activeStartUs = -1L;
            lastSpeechEndUs = -1L;
            silenceUs = 0L;
        }
    }

    private static final class RangeUs {
        final long startUs;
        final long endUs;

        RangeUs(long startUs, long endUs) {
            this.startUs = startUs;
            this.endUs = endUs;
        }
    }

    public static final class Range {
        public final long startMs;
        public final long endMs;

        Range(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class Snapshot {
        public final boolean available;
        public final boolean realtime;
        public final boolean complete;
        public final String source;
        public final long detectMs;
        public final List<Range> ranges;
        public final String error;

        Snapshot(boolean available, boolean realtime, boolean complete, String source,
                 long detectMs, List<Range> ranges, String error) {
            this.available = available;
            this.realtime = realtime;
            this.complete = complete;
            this.source = source;
            this.detectMs = detectMs;
            this.ranges = ranges;
            this.error = error;
        }

        static Snapshot missing() {
            return new Snapshot(false, false, false, "missing", 0L, new ArrayList<>(), null);
        }

        static Snapshot fromJson(JSONObject root, boolean realtime, long detectMs) {
            List<Range> ranges = new ArrayList<>();
            JSONArray rows = root.optJSONArray("ranges");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long start = row.optLong("startMs", -1L);
                    long end = row.optLong("endMs", -1L);
                    if (start >= 0L && end > start) ranges.add(new Range(start, end));
                }
            }
            return new Snapshot(
                    root.optBoolean("available", false),
                    realtime,
                    root.optBoolean("completeForSegment", false),
                    root.optString("source", realtime ? "whisper-silero-streaming" : "streaming-silero-offline"),
                    detectMs,
                    ranges,
                    root.isNull("error") ? null : root.optString("error", null));
        }
    }
}
