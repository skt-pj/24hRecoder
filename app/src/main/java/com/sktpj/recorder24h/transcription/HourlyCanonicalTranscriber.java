package com.sktpj.recorder24h.transcription;

import android.content.Context;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical nightly transcription for one local hour.
 *
 * The durable recorder still writes five-minute M4A recovery units. This class first composes all
 * speech from those source files into ONE silence-compacted hour input, persists the reversible
 * compact->original time map, and then invokes the selected ASR exactly once for the hour.
 */
public final class HourlyCanonicalTranscriber {
    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final String ENGINE_SUFFIX = "+hourly-one-shot-v1";

    private HourlyCanonicalTranscriber() {}

    public static String engineId(Context context, String modelId,
                                  TranscriptionPipelineSettings.Snapshot pipeline) {
        return LocalWhisperEngine.engineId(context, modelId, pipeline) + ENGINE_SUFFIX;
    }

    public static boolean process(Context context, List<SegmentRecord> batch, String runner) {
        if (batch == null || batch.isEmpty()) return true;
        Context app = context.getApplicationContext();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (TranscriptionScheduler.isQueuePaused(app)) return false;
            if (!NightlyHourlyTranscriptionScheduler.isCharging(app)) {
                requeueAll(app, batch, NightlyHourlyTranscriptionScheduler.ENQUEUED_REASON);
                log(app, "NIGHTLY_HOUR_ONE_SHOT_HELD_NOT_CHARGING", details(
                        "hour", hourKey(batch), "attempt", attempt, "runner", runner));
                return false;
            }

            String modelId = WhisperModelManager.selectedModelId(app);
            TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(app);
            String unavailable = TranscriptionPipelineSettings.unavailableReason(app, pipeline, modelId);
            if (unavailable != null) {
                boolean modelWait = "SILERO_VAD_MODEL_MISSING".equals(unavailable)
                        || "LOCAL_WHISPER_MODEL_MISSING".equals(unavailable);
                if (modelWait) WhisperModelManager.enqueueModelDownload(app, modelId);
                setAllState(app, batch, modelWait ? "READY" : "FAILED", unavailable);
                log(app, "NIGHTLY_HOUR_ONE_SHOT_PIPELINE_NOT_READY", details(
                        "hour", hourKey(batch), "reason", unavailable, "runner", runner));
                return false;
            }

            try {
                runOnce(app, batch, modelId, pipeline, runner, attempt);
                return true;
            } catch (OutOfMemoryError oom) {
                setAllState(app, batch, "FAILED", "NIGHTLY_HOURLY_ONE_SHOT_OOM");
                log(app, "NIGHTLY_HOUR_ONE_SHOT_OOM", details(
                        "hour", hourKey(batch), "attempt", attempt,
                        "error", oom.getClass().getSimpleName(), "runner", runner));
                return false;
            } catch (Exception error) {
                if (TranscriptionCancellation.isCancellation(error)) {
                    requeueAll(app, batch, NightlyHourlyTranscriptionScheduler.ENQUEUED_REASON);
                    log(app, "NIGHTLY_HOUR_ONE_SHOT_CANCELLED", details(
                            "hour", hourKey(batch), "attempt", attempt,
                            "runner", runner));
                    return false;
                }
                boolean retry = attempt < MAX_ATTEMPTS;
                setAllState(app, batch, retry ? "RETRY_WAIT" : "FAILED",
                        retry ? "NIGHTLY_HOURLY_ONE_SHOT_RETRY" : "NIGHTLY_HOURLY_ONE_SHOT_FAILED");
                log(app, retry ? "NIGHTLY_HOUR_ONE_SHOT_RETRY" : "NIGHTLY_HOUR_ONE_SHOT_FAILED",
                        details("hour", hourKey(batch), "attempt", attempt,
                                "error", error.getClass().getSimpleName(),
                                "message", safeMessage(error), "runner", runner));
                if (!retry || !sleepBackoff(attempt)) return false;
            }
        }
        return false;
    }

    private static void runOnce(Context context,
                                List<SegmentRecord> batch,
                                String modelId,
                                TranscriptionPipelineSettings.Snapshot pipeline,
                                String runner,
                                int attempt) throws Exception {
        long cancellationToken = TranscriptionCancellation.snapshot();
        TranscriptionCancellation.throwIfCancelled(cancellationToken);
        long hourStartAtMs = hourStartAtMs(batch);
        String hourKey = NightlyHourlyTranscriptionScheduler.localHourKey(hourStartAtMs);
        String selectedEngineId = engineId(context, modelId, pipeline);

        List<SourcePlan> sources = new ArrayList<>();
        long plannedSamples = 0L;
        long sourceAudioMs = 0L;
        long vadDetectMs = 0L;
        for (SegmentRecord record : batch) {
            String path = record.getAudioPath();
            File audio = path == null ? null : new File(path);
            if (audio == null || !audio.isFile()) {
                throw new IllegalStateException("SOURCE_AUDIO_MISSING:" + record.getSegmentId());
            }
            SourcePlan source = planSource(context, record, audio, hourStartAtMs, cancellationToken);
            sources.add(source);
            sourceAudioMs += source.durationMs;
            vadDetectMs += source.vadDetectMs;
            for (StreamingVadStore.Range range : source.ranges) {
                long start = Math.max(0L, Math.min(source.durationMs, range.startMs));
                long end = Math.max(start, Math.min(source.durationMs, range.endMs));
                plannedSamples += Math.max(0L, end - start) * 16L;
            }
        }
        if (plannedSamples > Integer.MAX_VALUE) {
            throw new IllegalStateException("HOURLY_SPEECH_INPUT_TOO_LARGE");
        }

        setAllState(context, batch, "TRANSCRIBING", "NIGHTLY_HOURLY_ONE_SHOT_TRANSCRIBING");
        float[] compacted = new float[(int) plannedSamples];
        List<SpeechTimeMap.Span> mapSpans = new ArrayList<>();
        int cursor = 0;
        long decodeMs = 0L;
        long preprocessMs = 0L;
        long denoiseMs = 0L;

        for (SourcePlan source : sources) {
            TranscriptionCancellation.throwIfCancelled(cancellationToken);
            long decodeStart = System.currentTimeMillis();
            float[] decoded = M4aPcmDecoder.decode(source.audioFile);
            decodeMs += Math.max(0L, System.currentTimeMillis() - decodeStart);
            long preprocessStart = System.currentTimeMillis();
            AudioPreprocessor.Result front = AudioPreprocessor.process(decoded);
            preprocessMs += Math.max(0L, System.currentTimeMillis() - preprocessStart);
            float[] selected = front.samples;

            int[] starts = new int[source.ranges.size()];
            int[] ends = new int[source.ranges.size()];
            for (int i = 0; i < source.ranges.size(); i++) {
                starts[i] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, source.ranges.get(i).startMs));
                ends[i] = (int) Math.max(starts[i], Math.min(Integer.MAX_VALUE, source.ranges.get(i).endMs));
            }
            if (starts.length > 0
                    && TranscriptionPipelineSettings.DENOISE_DEEPFILTER.equals(pipeline.denoiseBackend)) {
                long denoiseStart = System.currentTimeMillis();
                DeepFilterNetSpeechDenoiser.Result denoise = DeepFilterNetSpeechDenoiser.denoiseSelected(
                        context, source.record.getSegmentId(), selected, starts, ends,
                        front.snrProxyDb, cancellationToken);
                selected = denoise.samples;
                denoiseMs += Math.max(0L, System.currentTimeMillis() - denoiseStart);
            }

            long actualDurationMs = selected.length * 1000L / SAMPLE_RATE;
            for (StreamingVadStore.Range range : source.ranges) {
                long startMs = Math.max(0L, Math.min(actualDurationMs, range.startMs));
                long endMs = Math.max(startMs, Math.min(actualDurationMs, range.endMs));
                int startSample = (int) Math.max(0L, Math.min(selected.length, startMs * 16L));
                int endSample = (int) Math.max(startSample, Math.min(selected.length, endMs * 16L));
                if (endSample <= startSample) continue;
                int count = endSample - startSample;
                if (cursor + count > compacted.length) {
                    compacted = Arrays.copyOf(compacted, cursor + count);
                }
                long compactStartMs = cursor * 1000L / SAMPLE_RATE;
                System.arraycopy(selected, startSample, compacted, cursor, count);
                cursor += count;
                long compactEndMs = cursor * 1000L / SAMPLE_RATE;
                long originalStartMs = source.hourOffsetMs + startMs;
                long originalEndMs = source.hourOffsetMs + endMs;
                mapSpans.add(new SpeechTimeMap.Span(
                        compactStartMs, compactEndMs,
                        originalStartMs, originalEndMs,
                        source.record.getSegmentId(), startMs, endMs));
            }
        }
        if (cursor != compacted.length) compacted = Arrays.copyOf(compacted, cursor);

        SpeechTimeMap.MapResult timeMap = new SpeechTimeMap.MapResult(mapSpans);
        long speechInputMs = compacted.length * 1000L / SAMPLE_RATE;
        long removedSilenceMs = Math.max(0L, sourceAudioMs - speechInputMs);
        File mapFile = persistMap(context, hourKey, hourStartAtMs, sources, timeMap,
                sourceAudioMs, speechInputMs, removedSilenceMs, vadDetectMs,
                selectedEngineId, runner, attempt, null);

        log(context, "NIGHTLY_HOUR_AUDIO_ASSEMBLED", details(
                "hour", hourKey,
                "sourceSegmentCount", sources.size(),
                "sourceAudioMs", sourceAudioMs,
                "speechInputMs", speechInputMs,
                "removedSilenceMs", removedSilenceMs,
                "mappingSpanCount", mapSpans.size(),
                "mappingFile", mapFile.getAbsolutePath(),
                "whisperCallsPlanned", compacted.length == 0 ? 0 : 1,
                "runner", runner,
                "automaticFallback", false));

        JSONArray compactSegments = new JSONArray();
        String fullText = "";
        long modelLoadMs = 0L;
        long whisperFullMs = 0L;
        long inferenceStarted = System.currentTimeMillis();
        if (compacted.length > 0) {
            int compactDurationMs = Math.max(1, (int) (compacted.length * 1000L / SAMPLE_RATE));
            if (TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE.equals(pipeline.asrBackend)) {
                AndroidOnDeviceAsr.Result androidResult = AndroidOnDeviceAsr.transcribe(
                        context, compacted, new int[]{0}, new int[]{compactDurationMs}, cancellationToken);
                fullText = androidResult.text == null ? "" : androidResult.text.trim();
                compactSegments = new JSONArray(androidResult.segments.toString());
                modelLoadMs = 0L;
                whisperFullMs = -1L;
            } else {
                boolean useGpu = TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(pipeline.asrBackend);
                File model = WhisperModelManager.modelFile(context, modelId);
                String raw = LocalWhisperEngine.nativeTranscribeDetailed(
                        model.getAbsolutePath(), compacted,
                        new int[]{0}, new int[]{compactDurationMs},
                        "ja", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())),
                        useGpu, cancellationToken);
                if (raw == null) throw new IllegalStateException("HOURLY_ONE_SHOT_ASR_RETURNED_NULL");
                JSONObject nativeResult = new JSONObject(raw);
                fullText = nativeResult.optString("text", "").trim();
                JSONArray rows = nativeResult.optJSONArray("segments");
                compactSegments = rows == null ? new JSONArray() : rows;
                modelLoadMs = nativeResult.optLong("modelLoadMs", -1L);
                whisperFullMs = nativeResult.optLong("whisperFullMs", -1L);
            }
        }
        TranscriptionCancellation.throwIfCancelled(cancellationToken);
        long inferenceMs = Math.max(0L, System.currentTimeMillis() - inferenceStarted);

        JSONArray mappedHourSegments = timeMap.remapSegments(compactSegments);
        if (mappedHourSegments.length() == 0 && !fullText.isEmpty() && !mapSpans.isEmpty()) {
            SpeechTimeMap.Span first = mapSpans.get(0);
            SpeechTimeMap.Span last = mapSpans.get(mapSpans.size() - 1);
            mappedHourSegments.put(new JSONObject()
                    .put("startMs", first.originalStartMs)
                    .put("endMs", last.originalEndMs)
                    .put("sourceSegmentId", first.sourceSegmentId)
                    .put("sourceRelativeStartMs", first.sourceRelativeStartMs)
                    .put("sourceRelativeEndMs", first.sourceRelativeEndMs)
                    .put("silenceCompactionMapped", true)
                    .put("text", fullText));
        }

        Map<String, JSONArray> perSegment = new LinkedHashMap<>();
        for (SourcePlan source : sources) perSegment.put(source.record.getSegmentId(), new JSONArray());
        for (int i = 0; i < mappedHourSegments.length(); i++) {
            JSONObject row = mappedHourSegments.optJSONObject(i);
            if (row == null) continue;
            String segmentId = row.optString("sourceSegmentId", "");
            JSONArray target = perSegment.get(segmentId);
            if (target == null) continue;
            SourcePlan source = sourceFor(sources, segmentId);
            if (source == null) continue;
            JSONObject relative = new JSONObject(row.toString());
            long startMs = Math.max(0L, row.optLong("sourceRelativeStartMs", 0L));
            long endMs = Math.max(startMs, row.optLong("sourceRelativeEndMs", startMs));
            startMs = Math.min(source.durationMs, startMs);
            endMs = Math.min(source.durationMs, endMs);
            relative.put("hourStartMs", row.optLong("startMs", 0L));
            relative.put("hourEndMs", row.optLong("endMs", 0L));
            relative.put("startMs", startMs);
            relative.put("endMs", Math.max(startMs, endMs));
            target.put(relative);
        }

        int savedSegments = 0;
        int savedTextChars = 0;
        for (SourcePlan source : sources) {
            JSONArray rows = perSegment.get(source.record.getSegmentId());
            if (rows == null) rows = new JSONArray();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String piece = row.optString("text", "").trim();
                if (!piece.isEmpty()) {
                    if (text.length() > 0) text.append(' ');
                    text.append(piece);
                }
            }
            JSONArray savedRows = rows;
            if (rows.length() > 0
                    && TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU.equals(pipeline.speakerBackend)) {
                savedRows = SpeakerIdentifier.annotate(
                        context, source.audioFile, rows, cancellationToken);
            }
            TranscriptionCancellation.throwIfCancelled(cancellationToken);
            TranscriptionRepository.save(context, source.record.getSegmentId(), source.audioFile,
                    selectedEngineId, text.toString().trim(), savedRows);
            SegmentRepository.appendWithoutNotify(context, source.record.getSegmentId(), source.audioFile,
                    source.audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED",
                    rows.length() == 0 ? "NO_SPEECH_DETECTED" : null);
            savedSegments += savedRows.length();
            savedTextChars += text.length();
        }

        persistMap(context, hourKey, hourStartAtMs, sources, timeMap,
                sourceAudioMs, speechInputMs, removedSilenceMs, vadDetectMs,
                selectedEngineId, runner, attempt,
                details("completedAtMs", System.currentTimeMillis(),
                        "modelLoadMs", modelLoadMs,
                        "whisperFullMs", whisperFullMs,
                        "inferenceMs", inferenceMs,
                        "outputSegmentCount", mappedHourSegments.length(),
                        "textChars", savedTextChars));

        log(context, "NIGHTLY_HOUR_ONE_SHOT_SAVED", details(
                "hour", hourKey,
                "sourceSegmentCount", sources.size(),
                "sourceAudioMs", sourceAudioMs,
                "speechInputMs", speechInputMs,
                "removedSilenceMs", removedSilenceMs,
                "mappingSpanCount", mapSpans.size(),
                "whisperCallCount", compacted.length == 0 ? 0 : 1,
                "modelLoadMs", modelLoadMs,
                "whisperFullMs", whisperFullMs,
                "inferenceMs", inferenceMs,
                "savedOutputSegments", savedSegments,
                "textChars", savedTextChars,
                "engineId", selectedEngineId,
                "asrBackend", pipeline.asrBackend,
                "automaticFallback", false,
                "runner", runner));
    }

    private static SourcePlan planSource(Context context, SegmentRecord record, File audio,
                                         long hourStartAtMs, long cancellationToken) throws Exception {
        long startedAtMs = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
        long durationMs = record.getEndedAtMs() > startedAtMs
                ? record.getEndedAtMs() - startedAtMs : 5L * 60L * 1000L;
        StreamingVadStore.Snapshot vad = StreamingVadStore.read(context, record.getSegmentId());
        if (!vad.available || !vad.complete) {
            long decodeStart = System.currentTimeMillis();
            float[] decoded = M4aPcmDecoder.decode(audio);
            AudioPreprocessor.Result front = AudioPreprocessor.process(decoded);
            durationMs = Math.max(0L, front.samples.length * 1000L / SAMPLE_RATE);
            vad = StreamingVadStore.analyzeOffline(context, front.samples, cancellationToken);
            log(context, "NIGHTLY_HOUR_VAD_REPLAYED", details(
                    "segmentId", record.getSegmentId(),
                    "decodeAndPreprocessMs", Math.max(0L, System.currentTimeMillis() - decodeStart),
                    "rangeCount", vad.ranges.size()));
        }
        long offsetMs = Math.max(0L, startedAtMs - hourStartAtMs);
        return new SourcePlan(record, audio, offsetMs, durationMs,
                new ArrayList<>(vad.ranges), Math.max(0L, vad.detectMs));
    }

    private static SourcePlan sourceFor(List<SourcePlan> sources, String id) {
        for (SourcePlan source : sources) {
            if (source.record.getSegmentId().equals(id)) return source;
        }
        return null;
    }

    private static File persistMap(Context context, String hourKey, long hourStartAtMs,
                                   List<SourcePlan> sources, SpeechTimeMap.MapResult timeMap,
                                   long sourceAudioMs, long speechInputMs, long removedSilenceMs,
                                   long vadDetectMs, String engineId, String runner, int attempt,
                                   JSONObject completion) throws Exception {
        File dir = new File(context.getFilesDir(), "metadata/hourly-audio");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create hourly-audio metadata directory");
        }
        File target = new File(dir, hourKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        File temp = new File(dir, target.getName() + ".tmp");
        JSONArray sourceRows = new JSONArray();
        for (SourcePlan source : sources) {
            long speechMs = 0L;
            JSONArray ranges = new JSONArray();
            for (StreamingVadStore.Range range : source.ranges) {
                long start = Math.max(0L, Math.min(source.durationMs, range.startMs));
                long end = Math.max(start, Math.min(source.durationMs, range.endMs));
                if (end <= start) continue;
                speechMs += end - start;
                ranges.put(new JSONObject().put("startMs", start).put("endMs", end));
            }
            sourceRows.put(new JSONObject()
                    .put("segmentId", source.record.getSegmentId())
                    .put("audioFile", source.audioFile.getName())
                    .put("hourOffsetMs", source.hourOffsetMs)
                    .put("durationMs", source.durationMs)
                    .put("speechMs", speechMs)
                    .put("removedSilenceMs", Math.max(0L, source.durationMs - speechMs))
                    .put("ranges", ranges));
        }
        JSONObject root = new JSONObject()
                .put("schemaVersion", 1)
                .put("hour", hourKey)
                .put("hourStartAtMs", hourStartAtMs)
                .put("sourceSegmentCount", sources.size())
                .put("sourceAudioMs", sourceAudioMs)
                .put("speechInputMs", speechInputMs)
                .put("removedSilenceMs", removedSilenceMs)
                .put("vadDetectMs", vadDetectMs)
                .put("timebase", "hour-original-ms")
                .put("compactedTimebase", "silence-removed-ms")
                .put("mapping", timeMap.toJson())
                .put("sources", sourceRows)
                .put("engineId", engineId)
                .put("runner", runner)
                .put("attempt", attempt)
                .put("whisperCallCount", speechInputMs > 0L ? 1 : 0)
                .put("automaticFallback", false);
        if (completion != null) root.put("completion", completion);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace hourly audio map");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Unable to finalize hourly audio map");
        }
        return target;
    }

    private static long hourStartAtMs(List<SegmentRecord> batch) {
        long anchor = Long.MAX_VALUE;
        for (SegmentRecord record : batch) {
            long value = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
            if (value > 0L) anchor = Math.min(anchor, value);
        }
        if (anchor == Long.MAX_VALUE) anchor = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(anchor);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String hourKey(List<SegmentRecord> batch) {
        return NightlyHourlyTranscriptionScheduler.localHourKey(hourStartAtMs(batch));
    }

    private static void requeueAll(Context context, List<SegmentRecord> batch, String reason) {
        setAllState(context, batch, "QUEUED", reason);
    }

    private static void setAllState(Context context, List<SegmentRecord> batch,
                                    String status, String reason) {
        long now = System.currentTimeMillis();
        int index = 0;
        for (SegmentRecord record : batch) {
            String path = record.getAudioPath();
            File audio = path == null ? null : new File(path);
            SegmentRepository.appendWithoutNotify(context, record.getSegmentId(), audio,
                    audio != null && audio.isFile() ? audio.lastModified() : 0L,
                    now + index, status, reason);
            index++;
        }
    }

    private static boolean sleepBackoff(int attempt) {
        long delayMs = attempt == 1 ? 30_000L : 60_000L;
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static JSONObject details(Object... values) {
        JSONObject json = new JSONObject();
        for (int i = 0; i + 1 < values.length; i += 2) {
            try { json.put(String.valueOf(values[i]), values[i + 1]); } catch (Exception ignored) {}
        }
        return json;
    }

    private static void log(Context context, String event, JSONObject details) {
        try { AppLogger.event(context, event, details); } catch (Exception ignored) {}
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null ? "" : value;
    }

    private static final class SourcePlan {
        final SegmentRecord record;
        final File audioFile;
        final long hourOffsetMs;
        final long durationMs;
        final List<StreamingVadStore.Range> ranges;
        final long vadDetectMs;

        SourcePlan(SegmentRecord record, File audioFile, long hourOffsetMs, long durationMs,
                   List<StreamingVadStore.Range> ranges, long vadDetectMs) {
            this.record = record;
            this.audioFile = audioFile;
            this.hourOffsetMs = hourOffsetMs;
            this.durationMs = durationMs;
            this.ranges = ranges;
            this.vadDetectMs = vadDetectMs;
        }
    }
}
