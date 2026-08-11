package com.sktpj.recorder24h.transcription;

import android.content.Context;

import com.sktpj.recorder24h.audio.AacSegmentRecorder;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * High-recall, low-cost activity gate that runs on the PCM already read by AudioRecord.
 *
 * This is intentionally not the final speech detector. It only identifies regions that might
 * contain speech so the expensive Silero pass does not need to scan an entire five-minute file.
 * Timestamps are kept on the original AudioRecord PTS timeline. Persisted ranges are always
 * relative to the original segment; removed silence is never compacted out of the timebase.
 */
public final class RealtimeSpeechGateStore {
    private static final Object LOCK = new Object();

    private static final long PRE_ROLL_US = 800_000L;
    private static final long POST_ROLL_US = 1_200_000L;
    private static final long MERGE_GAP_US = 1_000_000L;

    // Conservative thresholds: the realtime gate should prefer false positives over missed speech.
    private static final double ACTIVITY_RMS = 0.0012;
    private static final double ACTIVITY_PEAK = 0.0045;
    private static final double DEFINITE_SILENCE_RMS = 0.0008;
    private static final double DEFINITE_SILENCE_PEAK = 0.0030;

    private static final List<IntervalUs> intervals = new ArrayList<>();
    private static final List<FrameSummary> frames = new ArrayList<>();
    private static long activeStartUs = -1L;
    private static long activeEndUs = -1L;
    private static double noiseFloorRms = 0.0010;

    private RealtimeSpeechGateStore() {
    }

    /** Reset the in-memory stream timeline when a new AudioRecord session starts. */
    public static void resetStream() {
        synchronized (LOCK) {
            intervals.clear();
            frames.clear();
            activeStartUs = -1L;
            activeEndUs = -1L;
            noiseFloorRms = 0.0010;
        }
    }

    /** Observe little-endian PCM16 from AudioRecord before it is queued into the AAC encoder. */
    public static void observePcm16(byte[] pcm, int length, long startPtsUs) {
        if (pcm == null || length < 2 || startPtsUs < 0L) return;
        int safeLength = Math.min(length, pcm.length) & ~1;
        if (safeLength <= 0) return;

        int samples = safeLength / 2;
        double sumSquares = 0.0;
        double peak = 0.0;
        for (int i = 0; i < safeLength; i += 2) {
            int lo = pcm[i] & 0xff;
            int hi = pcm[i + 1];
            short value = (short) ((hi << 8) | lo);
            double normalized = value / 32768.0;
            sumSquares += normalized * normalized;
            peak = Math.max(peak, Math.abs(normalized));
        }
        double rms = Math.sqrt(sumSquares / Math.max(1, samples));
        long durationUs = Math.max(1L,
                Math.round(samples * 1_000_000.0 / AacSegmentRecorder.SAMPLE_RATE_HZ));
        long endPtsUs = startPtsUs + durationUs;

        synchronized (LOCK) {
            double dynamicRms = Math.max(ACTIVITY_RMS, noiseFloorRms * 1.7);
            double dynamicPeak = Math.max(ACTIVITY_PEAK, noiseFloorRms * 5.0);
            boolean candidate = rms >= dynamicRms || peak >= dynamicPeak;

            // The noise estimate only learns quickly from frames already considered quiet. Loud or
            // continuous speech therefore cannot train itself into the background during a segment.
            if (!candidate) {
                noiseFloorRms = clamp(noiseFloorRms * 0.96 + rms * 0.04, 0.0002, 0.05);
            } else if (rms < noiseFloorRms) {
                noiseFloorRms = clamp(noiseFloorRms * 0.995 + rms * 0.005, 0.0002, 0.05);
            }

            frames.add(new FrameSummary(startPtsUs, endPtsUs, rms, peak, candidate));
            if (candidate) {
                addCandidateLocked(Math.max(0L, startPtsUs - PRE_ROLL_US), endPtsUs + POST_ROLL_US);
            }
        }
    }

    /**
     * Persist candidate metadata before the READY segment event is published. This guarantees a
     * transcription worker can see the realtime decision even if it starts immediately.
     */
    public static Snapshot persistSegment(Context context,
                                          String segmentId,
                                          long segmentBasePtsUs,
                                          long segmentEndPtsUs,
                                          long segmentStartedAtMs,
                                          long segmentEndedAtMs) {
        if (context == null || segmentId == null || segmentId.isEmpty()) return Snapshot.missing();
        long safeEndUs = Math.max(segmentBasePtsUs, segmentEndPtsUs);
        long durationMs = Math.max(0L, Math.round((safeEndUs - segmentBasePtsUs) / 1000.0));

        JSONObject root = new JSONObject();
        Snapshot snapshot;
        synchronized (LOCK) {
            List<IntervalUs> all = new ArrayList<>(intervals);
            if (activeStartUs >= 0L && activeEndUs > activeStartUs) {
                all.add(new IntervalUs(activeStartUs, activeEndUs));
            }

            JSONArray rows = new JSONArray();
            long candidateMs = 0L;
            int candidateCount = 0;
            for (IntervalUs interval : all) {
                long startUs = Math.max(segmentBasePtsUs, interval.startUs);
                long endUs = Math.min(safeEndUs, interval.endUs);
                if (endUs <= startUs) continue;
                long startMs = Math.max(0L, Math.round((startUs - segmentBasePtsUs) / 1000.0));
                long endMs = Math.min(durationMs, Math.round((endUs - segmentBasePtsUs) / 1000.0));
                if (endMs <= startMs) continue;
                candidateCount++;
                candidateMs += endMs - startMs;
                try {
                    rows.put(new JSONObject()
                            .put("startMs", startMs)
                            .put("endMs", endMs)
                            .put("durationMs", endMs - startMs)
                            .put("startAtMs", segmentStartedAtMs > 0L
                                    ? segmentStartedAtMs + startMs : JSONObject.NULL)
                            .put("endAtMs", segmentStartedAtMs > 0L
                                    ? segmentStartedAtMs + endMs : JSONObject.NULL));
                } catch (Exception ignored) {
                }
            }

            int frameCount = 0;
            int candidateFrameCount = 0;
            double maxRms = 0.0;
            double maxPeak = 0.0;
            double rmsSum = 0.0;
            for (FrameSummary frame : frames) {
                if (frame.endUs <= segmentBasePtsUs || frame.startUs >= safeEndUs) continue;
                frameCount++;
                if (frame.candidate) candidateFrameCount++;
                maxRms = Math.max(maxRms, frame.rms);
                maxPeak = Math.max(maxPeak, frame.peak);
                rmsSum += frame.rms;
            }
            double meanRms = frameCount == 0 ? 0.0 : rmsSum / frameCount;
            boolean definiteSilence = frameCount > 0
                    && maxRms <= DEFINITE_SILENCE_RMS
                    && maxPeak <= DEFINITE_SILENCE_PEAK;

            try {
                root.put("schemaVersion", 1);
                root.put("segmentId", segmentId);
                root.put("timebase", "original-audio-segment-ms");
                root.put("segmentStartedAtMs", segmentStartedAtMs);
                root.put("segmentEndedAtMs", segmentEndedAtMs);
                root.put("segmentBasePtsUs", segmentBasePtsUs);
                root.put("segmentEndPtsUs", safeEndUs);
                root.put("segmentDurationMs", durationMs);
                root.put("candidateCount", candidateCount);
                root.put("candidateMs", candidateMs);
                root.put("definiteSilence", definiteSilence);
                root.put("frameCount", frameCount);
                root.put("candidateFrameCount", candidateFrameCount);
                root.put("meanRms", meanRms);
                root.put("maxRms", maxRms);
                root.put("maxPeak", maxPeak);
                root.put("noiseFloorRms", noiseFloorRms);
                root.put("ranges", rows);
            } catch (Exception ignored) {
            }
            snapshot = new Snapshot(root, true);

            // Everything fully before this segment end is no longer needed in RAM. Keep an active
            // padded interval that crosses the boundary so the next segment gets the overlap too.
            Iterator<IntervalUs> intervalIterator = intervals.iterator();
            while (intervalIterator.hasNext()) {
                if (intervalIterator.next().endUs <= safeEndUs) intervalIterator.remove();
            }
            Iterator<FrameSummary> frameIterator = frames.iterator();
            while (frameIterator.hasNext()) {
                if (frameIterator.next().endUs <= safeEndUs) frameIterator.remove();
            }
            if (activeEndUs <= safeEndUs) {
                activeStartUs = -1L;
                activeEndUs = -1L;
            }
        }

        writeAtomic(fileFor(context, segmentId), root.toString());
        try {
            JSONObject log = snapshot.toJson();
            log.put("realtimeGate", true);
            AppLogger.event(context, "REALTIME_SPEECH_GATE_SEGMENT", log);
        } catch (Exception ignored) {
        }
        return snapshot;
    }

    public static Snapshot read(Context context, String segmentId) {
        if (context == null || segmentId == null || segmentId.isEmpty()) return Snapshot.missing();
        File file = fileFor(context, segmentId);
        if (!file.isFile()) return Snapshot.missing();
        try {
            return new Snapshot(new JSONObject(readUtf8(file)), true);
        } catch (Exception ignored) {
            return Snapshot.missing();
        }
    }

    private static void addCandidateLocked(long startUs, long endUs) {
        if (endUs <= startUs) return;
        if (activeStartUs < 0L) {
            activeStartUs = startUs;
            activeEndUs = endUs;
            return;
        }
        if (startUs <= activeEndUs + MERGE_GAP_US) {
            activeEndUs = Math.max(activeEndUs, endUs);
            return;
        }
        intervals.add(new IntervalUs(activeStartUs, activeEndUs));
        activeStartUs = startUs;
        activeEndUs = endUs;
    }

    private static File fileFor(Context context, String segmentId) {
        File dir = new File(new File(context.getFilesDir(), "metadata"), "realtime-speech");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, segmentId + ".json");
    }

    private static void writeAtomic(File target, String text) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveFailed) {
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
        }
    }

    private static String readUtf8(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return new String(data, 0, offset, StandardCharsets.UTF_8);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class IntervalUs {
        final long startUs;
        final long endUs;

        IntervalUs(long startUs, long endUs) {
            this.startUs = startUs;
            this.endUs = endUs;
        }
    }

    private static final class FrameSummary {
        final long startUs;
        final long endUs;
        final double rms;
        final double peak;
        final boolean candidate;

        FrameSummary(long startUs, long endUs, double rms, double peak, boolean candidate) {
            this.startUs = startUs;
            this.endUs = endUs;
            this.rms = rms;
            this.peak = peak;
            this.candidate = candidate;
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
        public final long segmentDurationMs;
        public final int candidateCount;
        public final long candidateMs;
        public final boolean definiteSilence;
        public final List<Range> ranges;
        private final JSONObject json;

        Snapshot(JSONObject json, boolean available) {
            this.json = json == null ? new JSONObject() : json;
            this.available = available;
            this.segmentDurationMs = this.json.optLong("segmentDurationMs", 0L);
            this.candidateCount = this.json.optInt("candidateCount", 0);
            this.candidateMs = this.json.optLong("candidateMs", 0L);
            this.definiteSilence = this.json.optBoolean("definiteSilence", false);
            this.ranges = new ArrayList<>();
            JSONArray rows = this.json.optJSONArray("ranges");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long start = row.optLong("startMs", -1L);
                    long end = row.optLong("endMs", -1L);
                    if (start >= 0L && end > start) ranges.add(new Range(start, end));
                }
            }
        }

        static Snapshot missing() {
            return new Snapshot(new JSONObject(), false);
        }

        public JSONObject toJson() {
            try {
                return new JSONObject(json.toString());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }
}
