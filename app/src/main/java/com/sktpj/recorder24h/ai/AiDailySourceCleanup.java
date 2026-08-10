package com.sktpj.recorder24h.ai;

import android.content.Context;

import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.transcription.LocalWhisperEngine;
import com.sktpj.recorder24h.transcription.TranscriptionRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Finalizes a completed daily Luna note by removing the raw per-segment source for that day.
 *
 * The daily analysis JSON is always durable before cleanup begins. The append-only segment
 * journal is retained as small lifecycle metadata; source audio and transcript JSON are removed.
 * A segment crossing midnight is retained until every local calendar day touched by that segment
 * has its own daily analysis, which prevents deleting the next day's source prematurely.
 */
public final class AiDailySourceCleanup {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    private static final String DELETE_REASON = "AI_DAILY_SUMMARY_SOURCE_PURGED";
    private static final Object FILE_LOCK = new Object();

    private AiDailySourceCleanup() {
    }

    public static boolean hasPendingTranscription(Context context, long periodStartMs, long periodEndMs) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (!overlaps(record, periodStartMs, periodEndMs)) {
                continue;
            }
            if (!record.getAudioAvailable()) {
                continue;
            }
            String status = record.getStatus();
            if (!record.getHasTranscript()
                    || "READY".equals(status)
                    || "QUEUED".equals(status)
                    || "RETRY_WAIT".equals(status)
                    || "TRANSCRIBING".equals(status)) {
                return true;
            }
        }
        return false;
    }

    public static String cleanupStatus(File dailyFile) {
        if (dailyFile == null || !dailyFile.isFile()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(readUtf8(dailyFile));
            JSONObject cleanup = root.optJSONObject("sourceCleanup");
            return cleanup == null ? "" : cleanup.optString("status", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void markPending(File dailyFile) throws Exception {
        CleanupResult empty = new CleanupResult();
        updateCleanup(dailyFile, STATUS_PENDING, empty);
    }

    public static void markResult(File dailyFile, CleanupResult result) throws Exception {
        updateCleanup(dailyFile, result.failedCount == 0 ? STATUS_COMPLETE : STATUS_PENDING, result);
    }

    public static CleanupResult cleanup(Context context, long periodStartMs, long periodEndMs) {
        CleanupResult result = new CleanupResult();
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        for (SegmentRecord record : records) {
            if (!overlaps(record, periodStartMs, periodEndMs)) {
                continue;
            }
            boolean transcriptExists = TranscriptionRepository.exists(context, record.getSegmentId());
            if (!record.getAudioAvailable() && !transcriptExists) {
                continue;
            }

            result.candidateCount++;
            if (crossesDayBoundary(record, periodStartMs, periodEndMs)
                    && !allTouchedDaysAnalyzed(context, record)) {
                result.retainedBoundaryCount++;
                continue;
            }

            purgeOne(context, record, result);
        }
        logCleanup(context, periodStartMs, periodEndMs, result);
        return result;
    }

    private static void purgeOne(Context context, SegmentRecord record, CleanupResult result) {
        String segmentId = record.getSegmentId();
        WorkManager.getInstance(context.getApplicationContext())
                .cancelAllWorkByTag("segment:" + segmentId);

        // Both WorkManager and the direct transcription FGS hold this same lock through inference
        // and transcript save. Waiting here prevents a completed transcription from recreating a
        // transcript after cleanup has removed it.
        synchronized (LocalWhisperEngine.class) {
            File audio = null;
            String audioPath = record.getAudioPath();
            if (audioPath != null && !audioPath.isEmpty()) {
                audio = new File(audioPath);
            }

            if (audio != null && audio.exists()) {
                if (!audio.delete()) {
                    result.failedCount++;
                    logFailure(context, segmentId, "AUDIO_DELETE_FAILED", audio);
                    return;
                }
                result.deletedAudioCount++;
                result.deletedAudioBytes += Math.max(0L, record.getFileSizeBytes());
            }

            File transcript = TranscriptionRepository.fileFor(context, segmentId);
            if (transcript.exists()) {
                long transcriptBytes = transcript.length();
                if (!transcript.delete()) {
                    result.failedCount++;
                    logFailure(context, segmentId, "TRANSCRIPT_DELETE_FAILED", transcript);
                    return;
                }
                result.deletedTranscriptCount++;
                result.deletedTranscriptBytes += transcriptBytes;
            }
            File transcriptTemp = new File(transcript.getParentFile(), transcript.getName() + ".tmp");
            if (transcriptTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                transcriptTemp.delete();
            }

            SegmentRepository.appendWithoutNotify(
                    context,
                    segmentId,
                    audio,
                    record.getStartedAtMs(),
                    System.currentTimeMillis(),
                    "DELETED",
                    DELETE_REASON);
            result.purgedSegmentCount++;
        }
    }

    private static boolean overlaps(SegmentRecord record, long periodStartMs, long periodEndMs) {
        long start = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
        long end = record.getEndedAtMs();
        if (start <= 0L) {
            return false;
        }
        if (end <= start) {
            end = start + 1L;
        }
        return start < periodEndMs && end > periodStartMs;
    }

    private static boolean crossesDayBoundary(SegmentRecord record, long periodStartMs, long periodEndMs) {
        long start = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
        long end = record.getEndedAtMs() > start ? record.getEndedAtMs() : start + 1L;
        return start < periodStartMs || end > periodEndMs;
    }

    private static boolean allTouchedDaysAnalyzed(Context context, SegmentRecord record) {
        long startMs = record.getStartedAtMs() > 0L ? record.getStartedAtMs() : record.getSortTimeMs();
        long endMs = record.getEndedAtMs() > startMs ? record.getEndedAtMs() : startMs + 1L;
        if (startMs <= 0L) {
            return false;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(Math.max(startMs, endMs - 1L)).atZone(zone).toLocalDate();
        LocalDate day = first;
        while (!day.isAfter(last)) {
            long dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli();
            if (!hasDailyAnalysis(context, dayStartMs)) {
                return false;
            }
            day = day.plusDays(1);
        }
        return true;
    }

    private static boolean hasDailyAnalysis(Context context, long dayStartMs) {
        File file = AiAnalysisRepository.dailyFile(context, dayStartMs);
        if (!file.isFile()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(readUtf8(file));
            return "daily".equals(root.optString("kind", ""))
                    && root.optLong("periodStartMs", -1L) == dayStartMs
                    && root.optJSONObject("analysis") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void updateCleanup(File dailyFile, String status, CleanupResult result) throws Exception {
        synchronized (FILE_LOCK) {
            JSONObject root = new JSONObject(readUtf8(dailyFile));
            JSONObject cleanup = new JSONObject();
            cleanup.put("status", status);
            cleanup.put("updatedAtMs", System.currentTimeMillis());
            cleanup.put("candidateCount", result.candidateCount);
            cleanup.put("purgedSegmentCount", result.purgedSegmentCount);
            cleanup.put("deletedAudioCount", result.deletedAudioCount);
            cleanup.put("deletedAudioBytes", result.deletedAudioBytes);
            cleanup.put("deletedTranscriptCount", result.deletedTranscriptCount);
            cleanup.put("deletedTranscriptBytes", result.deletedTranscriptBytes);
            cleanup.put("retainedBoundaryCount", result.retainedBoundaryCount);
            cleanup.put("failedCount", result.failedCount);
            root.put("sourceCleanup", cleanup);
            writeAtomic(dailyFile, root.toString());
        }
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IllegalStateException("Daily analysis file has no parent");
        }
        File temp = new File(parent, target.getName() + ".cleanup.tmp");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Unable to replace daily analysis cleanup state");
        }
        if (!temp.renameTo(target)) {
            throw new IllegalStateException("Unable to finalize daily analysis cleanup state");
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder((int) Math.min(file.length(), 256 * 1024L));
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) {
                    text.append(buffer, 0, read);
                }
            }
            return text.toString();
        }
    }

    private static void logCleanup(Context context, long periodStartMs, long periodEndMs,
                                   CleanupResult result) {
        try {
            JSONObject details = result.toJson();
            details.put("periodStartMs", periodStartMs);
            details.put("periodEndMs", periodEndMs);
            details.put("reason", DELETE_REASON);
            AppLogger.event(context,
                    result.failedCount == 0 ? "AI_DAILY_SOURCE_PURGED" : "AI_DAILY_SOURCE_PURGE_PARTIAL",
                    details);
        } catch (Exception ignored) {
        }
    }

    private static void logFailure(Context context, String segmentId, String reason, File file) {
        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId);
            details.put("reason", reason);
            details.put("file", file == null ? JSONObject.NULL : file.getAbsolutePath());
            AppLogger.event(context, "AI_DAILY_SOURCE_PURGE_FAILED", details);
        } catch (Exception ignored) {
        }
    }

    public static final class CleanupResult {
        public int candidateCount;
        public int purgedSegmentCount;
        public int deletedAudioCount;
        public long deletedAudioBytes;
        public int deletedTranscriptCount;
        public long deletedTranscriptBytes;
        public int retainedBoundaryCount;
        public int failedCount;

        JSONObject toJson() throws Exception {
            JSONObject row = new JSONObject();
            row.put("candidateCount", candidateCount);
            row.put("purgedSegmentCount", purgedSegmentCount);
            row.put("deletedAudioCount", deletedAudioCount);
            row.put("deletedAudioBytes", deletedAudioBytes);
            row.put("deletedTranscriptCount", deletedTranscriptCount);
            row.put("deletedTranscriptBytes", deletedTranscriptBytes);
            row.put("retainedBoundaryCount", retainedBoundaryCount);
            row.put("failedCount", failedCount);
            return row;
        }
    }
}
