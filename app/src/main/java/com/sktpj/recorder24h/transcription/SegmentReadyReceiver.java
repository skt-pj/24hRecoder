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
        TranscriptionScheduler.enqueue(context, segmentId, new File(filePath));
    }
}
