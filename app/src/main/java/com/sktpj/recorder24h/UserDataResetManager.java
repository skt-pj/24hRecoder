package com.sktpj.recorder24h;

import android.content.Context;
import android.content.Intent;

import androidx.work.Operation;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.ai.AiAnalysisScheduler;
import com.sktpj.recorder24h.service.RecorderService;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.transcription.NightlyHourlyTranscriptionScheduler;
import com.sktpj.recorder24h.transcription.RealtimeSpeechGateStore;
import com.sktpj.recorder24h.transcription.StreamingTranscriptionService;
import com.sktpj.recorder24h.transcription.TranscriptionCancellation;
import com.sktpj.recorder24h.transcription.TranscriptionQueueService;
import com.sktpj.recorder24h.transcription.TranscriptionResetManager;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Explicit user reset for generated recording/transcription data.
 *
 * This intentionally uses an allow-list. It must never clear SharedPreferences, noBackupFilesDir,
 * Android Keystore entries, or model directories, because model downloads, API credentials,
 * speaker profile, and user settings are setup state rather than disposable recording history.
 */
public final class UserDataResetManager {
    private static final long CANCEL_WAIT_SECONDS = 8L;

    private UserDataResetManager() {}

    public static synchronized Result clearGeneratedData(Context context) {
        Context app = context.getApplicationContext();
        if (isRecordingActive(app)) {
            return Result.recordingActive();
        }

        long startedAtMs = System.currentTimeMillis();
        long bytesDeleted = 0L;
        int filesDeleted = 0;
        int generation;
        try {
            WorkManager workManager = WorkManager.getInstance(app);
            List<Operation> cancellations = new ArrayList<>();
            cancellations.add(workManager.cancelAllWorkByTag("transcription"));
            cancellations.add(workManager.cancelAllWorkByTag("model-comparison"));
            cancellations.add(workManager.cancelAllWorkByTag("nightly-hourly-transcription"));
            cancellations.add(workManager.cancelAllWorkByTag("speaker-enrollment"));
            cancellations.add(workManager.cancelAllWorkByTag("live-speaker-enrollment"));
            // Clear semantic AI queue work but keep saved AI notes and provider/API configuration.
            cancellations.add(workManager.cancelAllWorkByTag("ai-analysis"));

            generation = TranscriptionResetManager.invalidateGeneration(app);
            TranscriptionCancellation.cancelCurrent();

            try {
                app.stopService(new Intent(app, TranscriptionQueueService.class));
            } catch (RuntimeException ignored) {
            }
            try {
                app.stopService(new Intent(app, StreamingTranscriptionService.class));
            } catch (RuntimeException ignored) {
            }

            // A cancelled worker must not be allowed to recreate queue/history files after deletion.
            // The persistent transcription generation protects stale ASR writers; waiting for
            // WorkManager cancellation also covers AI/speaker queue workers before their files go.
            for (Operation operation : cancellations) {
                awaitCancellation(operation);
            }

            DeleteStats total = new DeleteStats();
            total.add(deleteTree(new File(app.getFilesDir(), "audio")));
            total.add(deleteTree(new File(app.getFilesDir(), "transcripts")));
            total.add(deleteTree(new File(app.getFilesDir(), "transcript-edits")));
            total.add(deleteTree(new File(app.getFilesDir(), "model_comparisons")));
            total.add(deleteTree(new File(app.getFilesDir(), "metadata")));

            // AI notes are intentionally retained. Only the queue/checkpoint state tied to the
            // deleted source data is cleared, then periodic scheduling is recreated below.
            total.add(deleteTree(new File(app.getFilesDir(), "analysis/queue.json")));
            total.add(deleteTree(new File(app.getFilesDir(), "analysis/gemma-hourly-progress")));

            // This is status/history, not a preference. Removing it prevents the Home screen from
            // continuing to name a segment whose recording/history was intentionally deleted.
            total.add(deleteTree(new File(app.getFilesDir(), "recorder_state.json")));

            filesDeleted = total.files;
            bytesDeleted = total.bytes;
            RealtimeSpeechGateStore.resetStream();

            // Preserve configured providers/settings while recreating only their periodic wakeups.
            AiAnalysisScheduler.ensureScheduled(app);
            NightlyHourlyTranscriptionScheduler.ensureScheduled(app);

            JSONObject details = new JSONObject();
            details.put("generation", generation);
            details.put("filesDeleted", filesDeleted);
            details.put("bytesDeleted", bytesDeleted);
            details.put("durationMs", System.currentTimeMillis() - startedAtMs);
            details.put("audioDeleted", true);
            details.put("transcriptsDeleted", true);
            details.put("transcriptEditsDeleted", true);
            details.put("modelComparisonResultsDeleted", true);
            details.put("recordMetadataDeleted", true);
            details.put("transcriptionQueueDeleted", true);
            details.put("aiQueueDeleted", true);
            details.put("aiNotesPreserved", true);
            details.put("modelsPreserved", true);
            details.put("apiKeyPreserved", true);
            details.put("settingsPreserved", true);
            details.put("speakerProfilePreserved", true);
            details.put("diagnosticLogsPreserved", true);
            AppLogger.event(app, "USER_GENERATED_DATA_RESET_COMPLETED", details);
            return Result.success(filesDeleted, bytesDeleted);
        } catch (Exception error) {
            try {
                JSONObject details = new JSONObject();
                details.put("error", error.getClass().getSimpleName());
                details.put("message", error.getMessage() == null ? "" : error.getMessage());
                details.put("filesDeletedBeforeFailure", filesDeleted);
                details.put("bytesDeletedBeforeFailure", bytesDeleted);
                AppLogger.event(app, "USER_GENERATED_DATA_RESET_FAILED", details);
            } catch (Exception ignored) {
            }
            return Result.failed(error.getClass().getSimpleName());
        }
    }

    public static boolean isRecordingActive(Context context) {
        Context app = context.getApplicationContext();
        if (RecordingIntentStore.isRequested(app)) return true;
        String state = RecorderStateStore.read(app).optString("state", "STOPPED");
        return "RECORDING".equals(state)
                || "STARTING".equals(state)
                || "ROTATING".equals(state)
                || "RECOVERING".equals(state)
                || "STOPPING".equals(state);
    }

    private static void awaitCancellation(Operation operation) throws Exception {
        if (operation == null) return;
        operation.getResult().get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static DeleteStats deleteTree(File target) {
        DeleteStats result = new DeleteStats();
        if (target == null || !target.exists()) return result;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) result.add(deleteTree(child));
            }
        } else {
            result.bytes += Math.max(0L, target.length());
        }
        if (target.delete()) result.files++;
        return result;
    }

    private static final class DeleteStats {
        int files;
        long bytes;

        void add(DeleteStats other) {
            if (other == null) return;
            files += other.files;
            bytes += other.bytes;
        }
    }

    public static final class Result {
        public static final String SUCCESS = "SUCCESS";
        public static final String RECORDING_ACTIVE = "RECORDING_ACTIVE";
        public static final String FAILED = "FAILED";

        public final String status;
        public final int filesDeleted;
        public final long bytesDeleted;
        public final String error;

        private Result(String status, int filesDeleted, long bytesDeleted, String error) {
            this.status = status;
            this.filesDeleted = filesDeleted;
            this.bytesDeleted = bytesDeleted;
            this.error = error;
        }

        static Result success(int filesDeleted, long bytesDeleted) {
            return new Result(SUCCESS, filesDeleted, bytesDeleted, null);
        }

        static Result recordingActive() {
            return new Result(RECORDING_ACTIVE, 0, 0L, null);
        }

        static Result failed(String error) {
            return new Result(FAILED, 0, 0L, error);
        }
    }
}
