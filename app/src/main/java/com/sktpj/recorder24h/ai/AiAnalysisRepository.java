package com.sktpj.recorder24h.ai;

import android.content.Context;

import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.ui.TranscriptChunk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AiAnalysisRepository {
    private static final Object LOCK = new Object();
    private static final DateTimeFormatter PROMPT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
    private static final DateTimeFormatter DAY_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter HOUR_KEY =
            DateTimeFormatter.ofPattern("HH", Locale.ROOT);

    private AiAnalysisRepository() {
    }

    public static SourceWindow buildSource(Context context, long periodStartMs, long periodEndMs) {
        List<SegmentRecord> records = new ArrayList<>(SegmentHistoryRepository.load(context));
        records.sort(Comparator.comparingLong(SegmentRecord::getStartedAtMs));

        List<SourceEntry> entries = new ArrayList<>();
        Set<String> segmentIds = new LinkedHashSet<>();
        int transcriptCount = 0;

        for (SegmentRecord record : records) {
            String transcript = record.getTranscriptText();
            if (transcript == null || transcript.trim().isEmpty()) {
                continue;
            }

            boolean includedRecord = false;
            List<TranscriptChunk> chunks = record.getTranscriptChunks();
            if (chunks != null && !chunks.isEmpty() && record.getStartedAtMs() > 0L) {
                for (TranscriptChunk chunk : chunks) {
                    String text = chunk.getText() == null ? "" : chunk.getText().trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    long absoluteStart = record.getStartedAtMs() + chunk.getStartMs();
                    long absoluteEnd = record.getStartedAtMs() + chunk.getEndMs();
                    if (absoluteEnd <= periodStartMs || absoluteStart >= periodEndMs) {
                        continue;
                    }
                    entries.add(new SourceEntry(
                            Math.max(periodStartMs, absoluteStart),
                            Math.min(periodEndMs, Math.max(absoluteStart, absoluteEnd)),
                            record.getSegmentId(),
                            chunk.getSpeaker(),
                            text));
                    includedRecord = true;
                }
            } else {
                long recordStart = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
                long recordEnd = record.getEndedAtMs() > recordStart ? record.getEndedAtMs() : recordStart + 1L;
                if (recordStart < periodEndMs && recordEnd > periodStartMs) {
                    String speaker = record.getTranscriptSpeaker();
                    if (speaker == null || speaker.trim().isEmpty()) speaker = "判定不能";
                    entries.add(new SourceEntry(
                            Math.max(periodStartMs, recordStart),
                            Math.min(periodEndMs, recordEnd),
                            record.getSegmentId(),
                            speaker,
                            transcript.trim()));
                    includedRecord = true;
                }
            }

            if (includedRecord) {
                transcriptCount++;
                segmentIds.add(record.getSegmentId());
            }
        }

        entries.sort(Comparator
                .comparingLong((SourceEntry entry) -> entry.startMs)
                .thenComparing(entry -> entry.segmentId));
        List<String> ids = new ArrayList<>(segmentIds);
        String sourceHash = hash(entries);
        return new SourceWindow(periodStartMs, periodEndMs, entries, ids, transcriptCount, sourceHash);
    }

    public static File hourlyFile(Context context, long periodStartMs) {
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime time = Instant.ofEpochMilli(periodStartMs).atZone(zone);
        File dayDir = new File(new File(getAnalysisDir(context), "hourly"), DAY_KEY.format(time));
        if (!dayDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dayDir.mkdirs();
        }
        return new File(dayDir, HOUR_KEY.format(time) + ".json");
    }

    public static File dailyFile(Context context, long periodStartMs) {
        ZoneId zone = ZoneId.systemDefault();
        String day = DAY_KEY.format(Instant.ofEpochMilli(periodStartMs).atZone(zone));
        File dir = new File(getAnalysisDir(context), "daily");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, day + ".json");
    }

    public static boolean isCurrent(File file, String sourceHash) {
        return isCurrent(file, sourceHash, OpenAiLunaClient.MODEL);
    }

    public static boolean isCurrent(File file, String sourceHash, String modelId) {
        if (file == null || !file.isFile() || sourceHash == null || sourceHash.isEmpty()
                || modelId == null || modelId.isEmpty()) {
            return false;
        }
        try {
            JSONObject row = new JSONObject(readUtf8(file));
            return sourceHash.equals(row.optString("sourceHash", ""))
                    && modelId.equals(row.optString("model", ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void save(File target, String kind, SourceWindow source,
                            OpenAiLunaClient.Response response) throws Exception {
        save(target, kind, source, response, OpenAiLunaClient.MODEL);
    }

    public static void save(File target, String kind, SourceWindow source,
                            OpenAiLunaClient.Response response, String modelId) throws Exception {
        synchronized (LOCK) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Unable to create analysis directory");
            }

            JSONObject row = new JSONObject();
            row.put("schemaVersion", 1);
            row.put("kind", kind);
            row.put("model", modelId);
            row.put("periodStartMs", source.periodStartMs);
            row.put("periodEndMs", source.periodEndMs);
            row.put("generatedAtMs", System.currentTimeMillis());
            row.put("sourceHash", source.sourceHash);
            row.put("sourceSegmentIds", new JSONArray(source.segmentIds));
            row.put("sourceTranscriptCount", source.transcriptCount);
            row.put("sourceEntryCount", source.entries.size());
            row.put("responseId", response.responseId == null ? JSONObject.NULL : response.responseId);
            row.put("usage", response.usage == null ? JSONObject.NULL : new JSONObject(response.usage.toString()));
            row.put("analysis", new JSONObject(response.analysis.toString()));

            File temp = new File(parent, target.getName() + ".tmp");
            byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("Unable to replace analysis file");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("Unable to finalize analysis file");
            }
        }
    }

    public static File getAnalysisDir(Context context) {
        File dir = new File(context.getFilesDir(), "analysis");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static String promptTranscript(SourceWindow source) {
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder text = new StringBuilder();
        for (SourceEntry entry : source.entries) {
            String start = PROMPT_TIME.format(Instant.ofEpochMilli(entry.startMs).atZone(zone));
            String end = PROMPT_TIME.format(Instant.ofEpochMilli(entry.endMs).atZone(zone));
            text.append('[').append(start).append(" - ").append(end).append("] [")
                    .append(entry.speaker).append("] ")
                    .append(entry.text).append('\n');
        }
        return text.toString();
    }

    private static String hash(List<SourceEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SourceEntry entry : entries) {
                digest.update(Long.toString(entry.startMs).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(entry.endMs).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.segmentId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.speaker.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.text.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash transcript source", error);
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder((int) Math.min(file.length(), 128 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }

    public static final class SourceWindow {
        public final long periodStartMs;
        public final long periodEndMs;
        public final List<SourceEntry> entries;
        public final List<String> segmentIds;
        public final int transcriptCount;
        public final String sourceHash;

        SourceWindow(long periodStartMs, long periodEndMs, List<SourceEntry> entries,
                     List<String> segmentIds, int transcriptCount, String sourceHash) {
            this.periodStartMs = periodStartMs;
            this.periodEndMs = periodEndMs;
            this.entries = entries;
            this.segmentIds = segmentIds;
            this.transcriptCount = transcriptCount;
            this.sourceHash = sourceHash;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    public static final class SourceEntry {
        public final long startMs;
        public final long endMs;
        public final String segmentId;
        public final String speaker;
        public final String text;

        SourceEntry(long startMs, long endMs, String segmentId, String speaker, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.segmentId = segmentId;
            this.speaker = speaker;
            this.text = text;
        }
    }
}
