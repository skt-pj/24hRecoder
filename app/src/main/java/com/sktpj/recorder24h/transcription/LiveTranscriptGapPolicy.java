package com.sktpj.recorder24h.transcription;

import com.sktpj.recorder24h.ui.SegmentRecord;

/**
 * Shared classification for live-owned recording segments whose authoritative transcript can no
 * longer arrive automatically.
 *
 * A FAILED live-owned segment is terminal, not "pending transcription". The retained M4A remains
 * available for an explicit user retranscription, but realtime-only mode must not silently route
 * it through the normal postprocess Whisper lane.
 */
public final class LiveTranscriptGapPolicy {
    private LiveTranscriptGapPolicy() {
    }

    public static boolean isTerminalGap(SegmentRecord record) {
        return record != null
                && record.getLiveOwned()
                && record.getAudioAvailable()
                && !record.getHasTranscript()
                && "FAILED".equals(record.getStatus());
    }
}
