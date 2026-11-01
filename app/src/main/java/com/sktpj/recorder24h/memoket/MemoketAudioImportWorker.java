package com.sktpj.recorder24h.memoket;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.Date;

public final class MemoketAudioImportWorker extends Worker {
    private static final String INPUT_FILENAME = "filename";

    public MemoketAudioImportWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void enqueue(Context context, String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}\\.opus")) return;
        Data input = new Data.Builder().putString(INPUT_FILENAME, filename).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoketAudioImportWorker.class)
                .setInputData(input).build();
        WorkManager.getInstance(context).enqueueUniqueWork("memoket-import-" + filename,
                ExistingWorkPolicy.KEEP, request);
    }

    public static void enqueuePending(Context context) {
        File[] items = new MemoketRecordingStore(context).directory()
                .listFiles((dir, name) -> name.endsWith(".opus"));
        if (items != null) {
            for (File file : items) enqueue(context, file.getName());
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        String filename = getInputData().getString(INPUT_FILENAME);
        if (filename == null || !filename.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}\\.opus"))
            return Result.failure();
        Context context = getApplicationContext();
        File source = new File(new MemoketRecordingStore(context).directory(), filename);
        try {
            long started = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .parse(filename.substring(0, 15)).getTime();
            String id = UUID.nameUUIDFromBytes(("gem:" + filename).getBytes(StandardCharsets.UTF_8)).toString();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date(started));
            File target = new File(StoragePolicy.getAudioDir(context), timestamp + "_" + id + ".m4a");
            if (!source.isFile() && !target.isFile()) return Result.retry();
            long duration;
            if (!target.isFile() || target.length() == 0) {
                StoragePolicy.enforce(context);
                duration = MemoketAudioImporter.convert(source, target);
            } else {
                duration = readDuration(target);
            }
            if (duration <= 0 || !target.isFile() || target.length() == 0)
                throw new IllegalStateException("Gem M4A not ready");
            File journal = new File(new File(context.getFilesDir(), "metadata"), "segments.jsonl");
            if (!hasReadyRecord(journal, id, target.getName())) {
                SegmentRepository.append(context, id, target, started,
                        started + duration, "READY", null);
            }
            if (!hasReadyRecord(journal, id, target.getName())) return Result.retry();
            source.delete();
            new File(source.getParentFile(), filename + ".raw").delete();
            return Result.success();
        } catch (Exception error) {
            try {
                AppLogger.event(context, "MEMOKET_AUDIO_IMPORT_FAILED",
                        new JSONObject().put("filename", filename)
                                .put("error", error.getClass().getSimpleName()));
            } catch (Exception ignored) {}
            return Result.retry();
        }
    }

    private static long readDuration(File audio) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audio.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0L : Long.parseLong(value);
        } finally {
            retriever.release();
        }
    }

    private static boolean hasReadyRecord(File journal, String id, String fileName) throws Exception {
        if (!journal.isFile()) return false;
        try (BufferedReader reader = Files.newBufferedReader(journal.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JSONObject record = new JSONObject(line);
                    if (id.equals(record.optString("segmentId"))
                            && fileName.equals(record.optString("fileName"))
                            && "READY".equals(record.optString("status"))) return true;
                } catch (org.json.JSONException invalidLine) {
                    continue;
                }
            }
        }
        return false;
    }
}
