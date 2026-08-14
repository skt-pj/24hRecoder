package com.sktpj.recorder24h.transcription;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maps a silence-compacted Whisper timebase back to the original audio timeline. */
public final class SpeechTimeMap {
    private static final int SAMPLE_RATE = 16_000;

    private SpeechTimeMap() {}

    public static final class Span {
        public final long compactStartMs;
        public final long compactEndMs;
        public final long originalStartMs;
        public final long originalEndMs;
        public final String sourceSegmentId;
        public final long sourceRelativeStartMs;
        public final long sourceRelativeEndMs;

        public Span(long compactStartMs, long compactEndMs,
                    long originalStartMs, long originalEndMs,
                    String sourceSegmentId,
                    long sourceRelativeStartMs, long sourceRelativeEndMs) {
            this.compactStartMs = compactStartMs;
            this.compactEndMs = compactEndMs;
            this.originalStartMs = originalStartMs;
            this.originalEndMs = originalEndMs;
            this.sourceSegmentId = sourceSegmentId;
            this.sourceRelativeStartMs = sourceRelativeStartMs;
            this.sourceRelativeEndMs = sourceRelativeEndMs;
        }

        JSONObject toJson() {
            JSONObject row = new JSONObject();
            try {
                row.put("compactStartMs", compactStartMs);
                row.put("compactEndMs", compactEndMs);
                row.put("originalStartMs", originalStartMs);
                row.put("originalEndMs", originalEndMs);
                row.put("deletedBeforeMs", Math.max(0L, originalStartMs - compactStartMs));
                row.put("sourceSegmentId", sourceSegmentId == null ? JSONObject.NULL : sourceSegmentId);
                row.put("sourceRelativeStartMs", sourceRelativeStartMs);
                row.put("sourceRelativeEndMs", sourceRelativeEndMs);
            } catch (Exception ignored) {}
            return row;
        }
    }

    public static final class MapResult {
        public final List<Span> spans;

        public MapResult(List<Span> spans) {
            this.spans = spans == null ? new ArrayList<>() : new ArrayList<>(spans);
        }

        public JSONArray toJson() {
            JSONArray rows = new JSONArray();
            for (Span span : spans) rows.put(span.toJson());
            return rows;
        }

        public JSONArray remapSegments(JSONArray compactSegments) {
            JSONArray out = new JSONArray();
            if (compactSegments == null || spans.isEmpty()) return out;
            for (int i = 0; i < compactSegments.length(); i++) {
                JSONObject source = compactSegments.optJSONObject(i);
                if (source == null) continue;
                long compactStart = Math.max(0L, source.optLong("startMs", 0L));
                long compactEnd = Math.max(compactStart, source.optLong("endMs", compactStart));
                Point start = mapPoint(compactStart, false);
                Point end = mapPoint(compactEnd, true);
                if (start == null || end == null) continue;
                try {
                    JSONObject row = new JSONObject(source.toString());
                    row.put("compactedStartMs", compactStart);
                    row.put("compactedEndMs", compactEnd);
                    row.put("startMs", start.originalMs);
                    row.put("endMs", Math.max(start.originalMs, end.originalMs));
                    row.put("sourceSegmentId", start.span.sourceSegmentId == null
                            ? JSONObject.NULL : start.span.sourceSegmentId);
                    row.put("sourceRelativeStartMs", start.sourceRelativeMs);
                    row.put("sourceRelativeEndMs", end.sourceRelativeMs);
                    row.put("silenceCompactionMapped", true);
                    out.put(row);
                } catch (Exception ignored) {}
            }
            return out;
        }

        private Point mapPoint(long compactMs, boolean endBoundary) {
            if (spans.isEmpty()) return null;
            Span chosen = null;
            if (endBoundary) {
                for (Span span : spans) {
                    if (compactMs > span.compactStartMs && compactMs <= span.compactEndMs) {
                        chosen = span;
                        break;
                    }
                }
            } else {
                for (Span span : spans) {
                    if (compactMs >= span.compactStartMs && compactMs < span.compactEndMs) {
                        chosen = span;
                        break;
                    }
                }
            }
            if (chosen == null) {
                chosen = compactMs <= spans.get(0).compactStartMs
                        ? spans.get(0) : spans.get(spans.size() - 1);
            }
            long compactSpan = Math.max(1L, chosen.compactEndMs - chosen.compactStartMs);
            long originalSpan = Math.max(1L, chosen.originalEndMs - chosen.originalStartMs);
            long delta = Math.max(0L, Math.min(compactSpan, compactMs - chosen.compactStartMs));
            long original = chosen.originalStartMs + Math.round(delta * (double) originalSpan / compactSpan);
            long sourceRelativeSpan = Math.max(1L, chosen.sourceRelativeEndMs - chosen.sourceRelativeStartMs);
            long sourceRelative = chosen.sourceRelativeStartMs
                    + Math.round(delta * (double) sourceRelativeSpan / compactSpan);
            return new Point(chosen, original, sourceRelative);
        }
    }

    private static final class Point {
        final Span span;
        final long originalMs;
        final long sourceRelativeMs;

        Point(Span span, long originalMs, long sourceRelativeMs) {
            this.span = span;
            this.originalMs = originalMs;
            this.sourceRelativeMs = sourceRelativeMs;
        }
    }

    public static final class Compaction {
        public final float[] samples;
        public final MapResult map;
        public final long originalDurationMs;
        public final long speechDurationMs;
        public final long removedSilenceMs;

        Compaction(float[] samples, MapResult map, long originalDurationMs,
                   long speechDurationMs, long removedSilenceMs) {
            this.samples = samples;
            this.map = map;
            this.originalDurationMs = originalDurationMs;
            this.speechDurationMs = speechDurationMs;
            this.removedSilenceMs = removedSilenceMs;
        }
    }

    /** Compacts one contiguous PCM window using VAD ranges while retaining a reversible time map. */
    public static Compaction compactSingle(float[] source,
                                           List<StreamingVadStore.Range> ranges,
                                           long originalDurationMs) {
        if (source == null || source.length == 0 || ranges == null || ranges.isEmpty()) {
            return new Compaction(new float[0], new MapResult(new ArrayList<>()),
                    Math.max(0L, originalDurationMs), 0L, Math.max(0L, originalDurationMs));
        }
        long sampleTotal = 0L;
        for (StreamingVadStore.Range range : ranges) {
            long start = Math.max(0L, Math.min(originalDurationMs, range.startMs));
            long end = Math.max(start, Math.min(originalDurationMs, range.endMs));
            long startSample = Math.max(0L, Math.min(source.length, start * 16L));
            long endSample = Math.max(startSample, Math.min(source.length, end * 16L));
            sampleTotal += endSample - startSample;
        }
        if (sampleTotal <= 0L) {
            return new Compaction(new float[0], new MapResult(new ArrayList<>()),
                    Math.max(0L, originalDurationMs), 0L, Math.max(0L, originalDurationMs));
        }
        if (sampleTotal > Integer.MAX_VALUE) {
            throw new IllegalStateException("Compacted speech exceeds Java array limit");
        }
        float[] compact = new float[(int) sampleTotal];
        List<Span> spans = new ArrayList<>();
        int cursor = 0;
        for (StreamingVadStore.Range range : ranges) {
            long start = Math.max(0L, Math.min(originalDurationMs, range.startMs));
            long end = Math.max(start, Math.min(originalDurationMs, range.endMs));
            int startSample = (int) Math.max(0L, Math.min(source.length, start * 16L));
            int endSample = (int) Math.max(startSample, Math.min(source.length, end * 16L));
            if (endSample <= startSample) continue;
            int count = endSample - startSample;
            long compactStart = cursor * 1000L / SAMPLE_RATE;
            System.arraycopy(source, startSample, compact, cursor, count);
            cursor += count;
            long compactEnd = cursor * 1000L / SAMPLE_RATE;
            spans.add(new Span(compactStart, compactEnd, start, end,
                    null, start, end));
        }
        if (cursor != compact.length) compact = Arrays.copyOf(compact, cursor);
        long speechMs = cursor * 1000L / SAMPLE_RATE;
        return new Compaction(compact, new MapResult(spans),
                Math.max(0L, originalDurationMs), speechMs,
                Math.max(0L, originalDurationMs - speechMs));
    }
}
