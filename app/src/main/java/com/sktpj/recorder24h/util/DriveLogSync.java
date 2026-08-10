package com.sktpj.recorder24h.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.ai.OpenAiKeyStore;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.transcription.TranscriptionRepository;
import com.sktpj.recorder24h.transcription.TranscriptionScheduler;
import com.sktpj.recorder24h.transcription.WhisperModelManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DriveLogSync {
    private static final String PERIODIC_WORK = "drive-log-sync-periodic";
    private static final String NOW_WORK = "drive-log-sync-now";
    private static final long DIRECT_SYNC_MIN_INTERVAL_MS = 60_000L;
    private static final long SEGMENT_TAIL_BYTES = 2L * 1024L * 1024L;
    private static final ExecutorService DIRECT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DIRECT_RUNNING = new AtomicBoolean(false);
    private static volatile long lastDirectStartMs;

    private DriveLogSync() {
    }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DriveLogSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("drive-log-sync")
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);
    }

    public static void enqueueNow(Context context) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DriveLogSyncWorker.class)
                .setConstraints(constraints)
                .addTag("drive-log-sync")
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.REPLACE,
                request);
    }

    public static void syncDirectAsync(Context context) {
        Context app = context.getApplicationContext();
        if (!DriveLogTarget.isConfigured(app)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (DIRECT_RUNNING.get() || now - lastDirectStartMs < DIRECT_SYNC_MIN_INTERVAL_MS) {
            return;
        }
        if (!DIRECT_RUNNING.compareAndSet(false, true)) {
            return;
        }
        lastDirectStartMs = now;
        DIRECT_EXECUTOR.execute(() -> {
            try {
                sync(app);
            } catch (SecurityException permissionLost) {
                DriveLogTarget.recordError(app, shortError(permissionLost));
                DriveLogTarget.clearTreeUri(app);
                AppLogger.event(app, "DRIVE_LOG_SYNC_PERMISSION_LOST", errorDetails(permissionLost));
            } catch (Exception error) {
                DriveLogTarget.recordError(app, shortError(error));
                AppLogger.event(app, "DRIVE_LOG_SYNC_FAILED", errorDetails(error));
            } finally {
                DIRECT_RUNNING.set(false);
            }
        });
    }

    public static void sync(Context context) throws Exception {
        Context app = context.getApplicationContext();
        Uri treeUri = DriveLogTarget.getTreeUri(app);
        if (treeUri == null) {
            return;
        }

        ContentResolver resolver = app.getContentResolver();
        verifyTreeAccess(resolver, treeUri);

        File logsDir = new File(app.getFilesDir(), "logs");
        File[] logFiles = logsDir.listFiles((dir, name) ->
                name.endsWith(".jsonl") || name.endsWith(".jsonl.1"));
        if (logFiles != null) {
            Arrays.sort(logFiles, Comparator.comparing(File::getName));
            for (File file : logFiles) {
                if (file.isFile()) {
                    uploadFile(resolver, treeUri, "24hRecoder_" + file.getName(),
                            "application/json", file);
                }
            }
        }

        File recorderState = new File(app.getFilesDir(), "recorder_state.json");
        if (recorderState.isFile()) {
            uploadFile(resolver, treeUri, "24hRecoder_recorder_state.json",
                    "application/json", recorderState);
        }

        File segmentJournal = new File(new File(app.getFilesDir(), "metadata"), "segments.jsonl");
        if (segmentJournal.isFile()) {
            byte[] tail = readTail(segmentJournal, SEGMENT_TAIL_BYTES);
            uploadBytes(resolver, treeUri, "24hRecoder_segments_tail.jsonl",
                    "application/json", tail);
        }

        byte[] diagnostics = buildDiagnostics(app).toString(2).getBytes(StandardCharsets.UTF_8);
        uploadBytes(resolver, treeUri, "24hRecoder_diagnostics.json",
                "application/json", diagnostics);

        long now = System.currentTimeMillis();
        DriveLogTarget.recordSuccess(app, now);
        try {
            JSONObject details = new JSONObject();
            details.put("targetFolderId", DriveLogTarget.TARGET_FOLDER_ID);
            details.put("targetFolderName", DriveLogTarget.TARGET_FOLDER_NAME);
            details.put("syncedAtMs", now);
            AppLogger.event(app, "DRIVE_LOG_SYNC_SUCCEEDED", details);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject buildDiagnostics(Context context) throws Exception {
        JSONObject root = new JSONObject();
        long now = System.currentTimeMillis();
        root.put("schemaVersion", 1);
        root.put("generatedAtMs", now);
        root.put("generatedAtUtc", isoUtc(now));
        root.put("packageName", context.getPackageName());
        root.put("targetDriveFolderId", DriveLogTarget.TARGET_FOLDER_ID);
        root.put("targetDriveFolderName", DriveLogTarget.TARGET_FOLDER_NAME);
        root.put("driveLastSuccessAtMs", DriveLogTarget.getLastSuccessAtMs(context));
        root.put("driveLastError", nullable(DriveLogTarget.getLastError(context)));

        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        root.put("versionName", info.versionName == null ? JSONObject.NULL : info.versionName);
        root.put("versionCode", info.getLongVersionCode());

        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("model", Build.MODEL);
        device.put("device", Build.DEVICE);
        device.put("sdkInt", Build.VERSION.SDK_INT);
        device.put("androidRelease", Build.VERSION.RELEASE);
        root.put("device", device);

        root.put("recordingRequested", RecordingIntentStore.isRequested(context));
        root.put("recorderState", new JSONObject(RecorderStateStore.read(context).toString()));

        JSONObject transcription = new JSONObject();
        transcription.put("modelReady", WhisperModelManager.isReady(context));
        transcription.put("asrReady", WhisperModelManager.isAsrReady(context));
        transcription.put("vadReady", WhisperModelManager.isVadReady(context));
        transcription.put("pendingAudioCount", TranscriptionScheduler.pendingAudioCount(context));
        transcription.put("transcriptCount", TranscriptionRepository.count(context));
        File audioDir = StoragePolicy.getAudioDir(context);
        transcription.put("audioFileCount", countFiles(audioDir, ".m4a"));
        transcription.put("audioBytes", sumFiles(audioDir, ".m4a"));
        root.put("transcription", transcription);

        JSONObject ai = new JSONObject();
        ai.put("apiKeyConfigured", OpenAiKeyStore.hasKey(context));
        ai.put("analysisFiles", latestAnalysisFiles(context, 20));
        root.put("ai", ai);

        JSONObject work = new JSONObject();
        work.put("transcription", workInfos(context, "transcription"));
        work.put("aiAnalysis", workInfos(context, "ai-analysis"));
        work.put("driveLogSync", workInfos(context, "drive-log-sync"));
        root.put("workManager", work);

        File journal = new File(new File(context.getFilesDir(), "metadata"), "segments.jsonl");
        JSONObject files = new JSONObject();
        files.put("segmentJournalExists", journal.isFile());
        files.put("segmentJournalBytes", journal.isFile() ? journal.length() : 0L);
        files.put("logsBytes", directoryBytes(new File(context.getFilesDir(), "logs")));
        root.put("localFiles", files);
        return root;
    }

    private static JSONArray workInfos(Context context, String tag) {
        JSONArray out = new JSONArray();
        try {
            List<WorkInfo> infos = WorkManager.getInstance(context)
                    .getWorkInfosByTag(tag)
                    .get(5, TimeUnit.SECONDS);
            int start = Math.max(0, infos.size() - 50);
            for (int i = start; i < infos.size(); i++) {
                WorkInfo info = infos.get(i);
                JSONObject row = new JSONObject();
                row.put("id", info.getId().toString());
                row.put("state", info.getState().name());
                row.put("runAttemptCount", info.getRunAttemptCount());
                JSONArray tags = new JSONArray();
                List<String> sortedTags = new ArrayList<>(info.getTags());
                Collections.sort(sortedTags);
                for (String value : sortedTags) {
                    tags.put(value);
                }
                row.put("tags", tags);
                out.put(row);
            }
        } catch (Exception error) {
            JSONObject row = new JSONObject();
            try {
                row.put("error", shortError(error));
                out.put(row);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONArray latestAnalysisFiles(Context context, int limit) {
        List<File> files = new ArrayList<>();
        collectJsonFiles(new File(context.getFilesDir(), "analysis"), files);
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JSONArray out = new JSONArray();
        int count = Math.min(limit, files.size());
        for (int i = 0; i < count; i++) {
            File file = files.get(i);
            try {
                JSONObject row = new JSONObject();
                row.put("name", relativeTo(new File(context.getFilesDir(), "analysis"), file));
                row.put("lastModifiedMs", file.lastModified());
                row.put("bytes", file.length());
                out.put(row);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void collectJsonFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectJsonFiles(child, out);
            } else if (child.isFile() && child.getName().endsWith(".json")) {
                out.add(child);
            }
        }
    }

    private static String relativeTo(File root, File file) {
        try {
            return root.toPath().relativize(file.toPath()).toString();
        } catch (Exception ignored) {
            return file.getName();
        }
    }

    private static void verifyTreeAccess(ContentResolver resolver, Uri treeUri) {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
        try (Cursor cursor = resolver.query(
                children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null,
                null,
                null)) {
            if (cursor == null) {
                throw new SecurityException("Drive folder permission is unavailable");
            }
        }
    }

    private static void uploadFile(ContentResolver resolver, Uri treeUri, String name,
                                   String mimeType, File source) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            uploadStream(resolver, treeUri, name, mimeType, input);
        }
    }

    private static void uploadBytes(ContentResolver resolver, Uri treeUri, String name,
                                    String mimeType, byte[] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            uploadStream(resolver, treeUri, name, mimeType, input);
        }
    }

    private static void uploadStream(ContentResolver resolver, Uri treeUri, String name,
                                     String mimeType, InputStream input) throws IOException {
        Uri document = findOrCreateDocument(resolver, treeUri, name, mimeType);
        OutputStream output = null;
        try {
            output = resolver.openOutputStream(document, "rwt");
        } catch (Exception ignored) {
        }
        if (output == null) {
            output = resolver.openOutputStream(document, "w");
        }
        if (output == null) {
            throw new IOException("Unable to open Drive output for " + name);
        }
        try (OutputStream closeable = output) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    closeable.write(buffer, 0, read);
                }
            }
            closeable.flush();
        }
    }

    private static Uri findOrCreateDocument(ContentResolver resolver, Uri treeUri,
                                             String name, String mimeType) throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
        try (Cursor cursor = resolver.query(
                childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                },
                null,
                null,
                null)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    if (idColumn < 0 || nameColumn < 0) {
                        break;
                    }
                    String displayName = cursor.getString(nameColumn);
                    if (name.equals(displayName)) {
                        String documentId = cursor.getString(idColumn);
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    }
                }
            }
        }

        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri created = DocumentsContract.createDocument(resolver, parent, mimeType, name);
        if (created == null) {
            throw new IOException("Unable to create Drive document " + name);
        }
        return created;
    }

    private static byte[] readTail(File file, long maxBytes) throws IOException {
        long length = file.length();
        int size = (int) Math.min(length, maxBytes);
        byte[] buffer = new byte[size];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(Math.max(0L, length - size));
            input.readFully(buffer);
        }
        return buffer;
    }

    private static int countFiles(File dir, String suffix) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
        return files == null ? 0 : files.length;
    }

    private static long sumFiles(File dir, String suffix) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File file : files) {
            total += file.length();
        }
        return total;
    }

    private static long directoryBytes(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File file : files) {
            total += file.isDirectory() ? directoryBytes(file) : file.length();
        }
        return total;
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static JSONObject errorDetails(Throwable error) {
        JSONObject details = new JSONObject();
        try {
            details.put("type", error.getClass().getName());
            details.put("message", shortError(error));
        } catch (Exception ignored) {
        }
        return details;
    }

    static String shortError(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String isoUtc(long timeMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMs));
    }
}
