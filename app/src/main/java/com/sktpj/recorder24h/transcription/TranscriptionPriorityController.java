package com.sktpj.recorder24h.transcription;

import android.content.Context;

import androidx.work.WorkManager;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts the visible transcription order into the persisted FIFO timestamps used by the direct
 * queue runner. Pending WorkManager copies are cancelled so they cannot jump ahead of that order.
 * An item already TRANSCRIBING is intentionally left alone and is never preempted.
 */
public final class TranscriptionPriorityController {
    private TranscriptionPriorityController() {
    }

    public static boolean applyOrder(Context context, List<String> orderedSegmentIds) {
        if (orderedSegmentIds == null || orderedSegmentIds.isEmpty()) return false;
        Context app = context.getApplicationContext();
        Map<String, SegmentRecord> records = new HashMap<>();
        for (SegmentRecord record : SegmentHistoryRepository.load(app)) {
            records.put(record.getSegmentId(), record);
        }

        long base = System.currentTimeMillis();
        int written = 0;
        WorkManager manager = WorkManager.getInstance(app);
        for (String segmentId : orderedSegmentIds) {
            SegmentRecord record = records.get(segmentId);
            if (record == null || "TRANSCRIBING".equals(record.getStatus())) continue;
            if (!"QUEUED".equals(record.getStatus()) && !"RETRY_WAIT".equals(record.getStatus())) {
                continue;
            }
            String audioPath = record.getAudioPath();
            File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
            if (audioFile == null || !audioFile.isFile()) continue;

            manager.cancelUniqueWork(TranscriptionScheduler.uniqueWorkName(segmentId));
            boolean manual = record.getReason() != null && record.getReason().startsWith("MANUAL_");
            String reason = manual
                    ? "MANUAL_PRIORITY_WORK_ENQUEUED"
                    : "LOCAL_PRIORITY_WORK_ENQUEUED";
            SegmentRepository.appendWithoutNotify(
                    app,
                    segmentId,
                    audioFile,
                    0L,
                    base + written,
                    "QUEUED",
                    reason);
            written++;
        }

        if (written > 0) {
            TranscriptionQueueService.kick(app);
            return true;
        }
        return false;
    }
}
