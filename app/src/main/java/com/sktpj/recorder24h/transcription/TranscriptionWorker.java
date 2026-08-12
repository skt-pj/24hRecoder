package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;

/**
 * Compatibility shim for WorkManager requests persisted by 0.6.13 and earlier.
 *
 * New builds never schedule this per-segment worker. If Android restores one from the old
 * WorkManager database after an APK update/process restart, it redirects the request into the
 * canonical recorded-start FIFO and exits without touching LocalWhisperEngine.
 */
public final class TranscriptionWorker extends Worker {
    public TranscriptionWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String segmentId = getInputData().getString(TranscriptionScheduler.EXTRA_SEGMENT_ID);
        String filePath = getInputData().getString(TranscriptionScheduler.EXTRA_FILE_PATH);
        boolean forceRetranscribe = getInputData()
                .getBoolean(TranscriptionScheduler.EXTRA_FORCE_RETRANSCRIBE, false);

        try {
            JSONObject details = new JSONObject();
            details.put("segmentId", segmentId == null ? JSONObject.NULL : segmentId);
            details.put("filePath", filePath == null ? JSONObject.NULL : filePath);
            details.put("manualRetranscription", forceRetranscribe);
            details.put("redirectedTo", "recorded-start-fifo");
            AppLogger.event(context, "LEGACY_TRANSCRIPTION_WORK_REDIRECTED", details);
        } catch (Exception ignored) {
        }

        if (forceRetranscribe && segmentId != null && filePath != null) {
            File file = new File(filePath);
            if (file.isFile()) {
                TranscriptionScheduler.enqueueForceRetranscription(context, segmentId, file);
                return Result.success();
            }
        }

        // This also recovers stale TRANSCRIBING rows left by the old architecture and schedules a
        // single dispatcher wake-up after re-establishing chronological queue state.
        TranscriptionScheduler.enqueueExisting(context);
        return Result.success();
    }
}
