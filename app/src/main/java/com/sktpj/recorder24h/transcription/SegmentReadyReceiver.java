package com.sktpj.recorder24h.transcription;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

public final class SegmentReadyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TranscriptionScheduler.ACTION_SEGMENT_READY.equals(intent.getAction())) {
            return;
        }
        String segmentId = intent.getStringExtra(TranscriptionScheduler.EXTRA_SEGMENT_ID);
        String filePath = intent.getStringExtra(TranscriptionScheduler.EXTRA_FILE_PATH);
        if (segmentId == null || filePath == null) {
            return;
        }
        // 0.7.39: the durable five-minute file remains the storage/recovery unit, but automatic
        // canonical Whisper no longer starts on every five-minute boundary. Stage it for the
        // previous-day night batch; explicit user retranscription still uses the immediate path.
        NightlyHourlyTranscriptionScheduler.onSegmentReady(
                context, segmentId, new File(filePath));
    }
}
