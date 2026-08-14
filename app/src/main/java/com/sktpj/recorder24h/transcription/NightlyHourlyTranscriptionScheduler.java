package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Automatic canonical transcription policy introduced in 0.7.39.
 *
 * Recording still uses durable five-minute M4A files. Automatic Whisper work is no longer started
 * when each file closes. Retained files are staged during the day and previous-day files are
 * enqueued together during the local night window. The UI groups their canonical text by hour.
 * Manual retranscription remains immediate through TranscriptionScheduler.
 */
public final class NightlyHourlyTranscriptionScheduler {
    public static final String PENDING_REASON = "NIGHTLY_HOURLY_PENDING";
    public static final String ENQUEUED_REASON = "NIGHTLY_HOURLY_WORK_ENQUEUED";
    public static final int NIGHT_START_HOUR_LOCAL = 2;

    private static final String PERIODIC_WORK_NAME = "nightly-hourly-transcription-scan-v1";
    private static final String CATCHUP_WORK_NAME = "nightly-hourly-transcription-catchup-v1";
    private static final String PREFS = "nightly_hourly_transcription";
    private static final String KEY_MIGRATED_039 = "migrated_0_7_39";

    private NightlyHourlyTranscriptionScheduler() {}

    public static void onSegmentReady(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty() || file == null || !file.isFile()) return;
        Context app = context.getApplicationContext();
        SegmentRepository.appendWithoutNotify(
                app,
                segmentId,
                file,
                file.lastModified(),
                System.currentTimeMillis(),
                "READY",
                PENDING_REASON);
        try {
            AppLogger.event(app, "NIGHTLY_HOURLY_SEGMENT_STAGED", new JSONObject()
                    .put("segmentId", segmentId)
                    .put("audioFile", file.getName())
                    .put("automaticFiveMinuteWhisper", false)
                    .put("canonicalDisplayUnitMinutes", 60)
                    .put("nightStartHourLocal", NIGHT_START_HOUR_LOCAL));
        } catch (Exception ignored) {}
        ensureScheduled(app);
    }

    /** One-time upgrade migration: remove old automatic five-minute backlog from the live queue. */
    public static int migrateLegacyAutomaticBacklog(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_MIGRATED_039, false)) return 0;

        List<SegmentRecord> records = SegmentHistoryRepository.load(app);
        boolean cancelRunning = false;
        int staged = 0;
        for (SegmentRecord record : records) {
            if (!record.getAudioAvailable() || record.getHasTranscript()) continue;
            if (record.getStatus().equals("CORRUPT")) continue;
            String reason = record.getReason();
            if (reason != null && reason.startsWith("MANUAL_")) continue;
            String audioPath = record.getAudioPath();
            File audio = audioPath == null ? null : new File(audioPath);
            if (audio == null || !audio.isFile()) continue;
            if ("TRANSCRIBING".equals(record.getStatus())) cancelRunning = true;
            SegmentRepository.appendWithoutNotify(
                    app,
                    record.getSegmentId(),
                    audio,
                    record.getStartedAtMs(),
                    System.currentTimeMillis(),
                    "READY",
                    PENDING_REASON);
            staged++;
        }
        if (cancelRunning) TranscriptionCancellation.cancelCurrent();
        prefs.edit().putBoolean(KEY_MIGRATED_039, true).commit();
        try {
            AppLogger.event(app, "NIGHTLY_HOURLY_MIGRATION_COMPLETED", new JSONObject()
                    .put("stagedCount", staged)
                    .put("runningAutomaticCancelled", cancelRunning)
                    .put("manualQueuePreserved", true));
        } catch (Exception ignored) {}
        return staged;
    }

    /** Re-stage retained automatic audio after a transcript reset without starting daytime ASR. */
    public static int stageRetainedUntranscribed(Context context) {
        Context app = context.getApplicationContext();
        int staged = 0;
        for (SegmentRecord record : SegmentHistoryRepository.load(app)) {
            if (!record.getAudioAvailable() || record.getHasTranscript()) continue;
            if (record.getStatus().equals("CORRUPT")) continue;
            if ("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus())
                    || "TRANSCRIBING".equals(record.getStatus())) continue;
            String reason = record.getReason();
            if (reason != null && reason.startsWith("MANUAL_")) continue;
            if (PENDING_REASON.equals(reason)) continue;
            String audioPath = record.getAudioPath();
            File audio = audioPath == null ? null : new File(audioPath);
            if (audio == null || !audio.isFile()) continue;
            SegmentRepository.appendWithoutNotify(app, record.getSegmentId(), audio,
                    record.getStartedAtMs(), System.currentTimeMillis(), "READY", PENDING_REASON);
            staged++;
        }
        ensureScheduled(app);
        return staged;
    }

    public static boolean isNightlyPending(SegmentRecord record) {
        return record != null && PENDING_REASON.equals(record.getReason());
    }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        long delayMs = delayUntilNextNight(System.currentTimeMillis());
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                NightlyHourlyTranscriptionWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag("nightly-hourly-transcription")
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic);

        // If the app first starts during the chosen night window, do not wait until tomorrow.
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour >= NIGHT_START_HOUR_LOCAL && hour < NIGHT_START_HOUR_LOCAL + 4
                && hasPreviousDayPending(app)) {
            OneTimeWorkRequest catchup = new OneTimeWorkRequest.Builder(NightlyHourlyTranscriptionWorker.class)
                    .setConstraints(constraints)
                    .addTag("nightly-hourly-transcription")
                    .addTag("nightly-hourly-catchup")
                    .build();
            WorkManager.getInstance(app).enqueueUniqueWork(
                    CATCHUP_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.KEEP,
                    catchup);
        }
    }

    static int enqueuePreviousDayBatches(Context context) {
        Context app = context.getApplicationContext();
        long todayStartMs = startOfLocalDay(System.currentTimeMillis());
        List<SegmentRecord> eligible = new ArrayList<>();
        for (SegmentRecord record : SegmentHistoryRepository.load(app)) {
            if (!record.getAudioAvailable() || record.getHasTranscript()) continue;
            if (record.getSortTimeMs() <= 0L || record.getSortTimeMs() >= todayStartMs) continue;
            if (record.getStatus().equals("CORRUPT")) continue;
            if ("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus())
                    || "TRANSCRIBING".equals(record.getStatus())) continue;
            String reason = record.getReason();
            if (reason != null && reason.startsWith("MANUAL_")) continue;
            eligible.add(record);
        }
        eligible.sort(Comparator.comparingLong(SegmentRecord::getSortTimeMs));

        Map<String, Integer> hourlyCounts = new LinkedHashMap<>();
        int enqueued = 0;
        for (SegmentRecord record : eligible) {
            String audioPath = record.getAudioPath();
            File audio = audioPath == null ? null : new File(audioPath);
            if (audio == null || !audio.isFile()) continue;
            if (TranscriptionScheduler.enqueue(context, record.getSegmentId(), audio)) {
                enqueued++;
                String hourKey = localHourKey(record.getSortTimeMs());
                hourlyCounts.put(hourKey, hourlyCounts.getOrDefault(hourKey, 0) + 1);
            }
        }
        try {
            JSONArray hours = new JSONArray();
            for (Map.Entry<String, Integer> row : hourlyCounts.entrySet()) {
                hours.put(new JSONObject().put("hour", row.getKey()).put("segmentCount", row.getValue()));
            }
            AppLogger.event(app, "NIGHTLY_HOURLY_BATCH_ENQUEUED", new JSONObject()
                    .put("eligibleCount", eligible.size())
                    .put("enqueuedCount", enqueued)
                    .put("hourCount", hourlyCounts.size())
                    .put("hours", hours)
                    .put("physicalSegmentMinutes", 5)
                    .put("canonicalDisplayUnitMinutes", 60)
                    .put("automaticCpuFallback", false));
        } catch (Exception ignored) {}
        return enqueued;
    }

    private static boolean hasPreviousDayPending(Context context) {
        long todayStart = startOfLocalDay(System.currentTimeMillis());
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (record.getAudioAvailable() && !record.getHasTranscript()
                    && record.getSortTimeMs() > 0L && record.getSortTimeMs() < todayStart
                    && !"CORRUPT".equals(record.getStatus())) return true;
        }
        return false;
    }

    private static long delayUntilNextNight(long nowMs) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(nowMs);
        next.set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR_LOCAL);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= nowMs) next.add(Calendar.DAY_OF_YEAR, 1);
        return Math.max(1_000L, next.getTimeInMillis() - nowMs);
    }

    static long startOfLocalDay(long timeMs) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(timeMs);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    public static String localHourKey(long timeMs) {
        Calendar hour = Calendar.getInstance();
        hour.setTimeInMillis(timeMs);
        return String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d",
                hour.get(Calendar.YEAR), hour.get(Calendar.MONTH) + 1,
                hour.get(Calendar.DAY_OF_MONTH), hour.get(Calendar.HOUR_OF_DAY));
    }
}
