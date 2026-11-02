package com.sktpj.recorder24h

import com.sktpj.recorder24h.ui.SegmentRecord

internal fun activeTranscriptionQueueSegments(records: List<SegmentRecord>): List<SegmentRecord> =
    records
        .filter { it.status == "QUEUED" || it.status == "RETRY_WAIT" || it.status == "TRANSCRIBING" }
        .distinctBy { it.segmentId }
        .sortedWith(
            compareBy<SegmentRecord> {
                if (it.status == "TRANSCRIBING") 0 else 1
            }.thenBy {
                if (it.queueEnqueuedAtMs > 0L) it.queueEnqueuedAtMs else it.stateChangedAtMs
            }
        )

internal fun transcriptionQueueSourceLabel(record: SegmentRecord): String = when {
    record.reason.orEmpty().startsWith("MANUAL_") -> "ユーザー追加"
    record.reason.orEmpty().startsWith("NIGHTLY_HOURLY_") -> "夜間確定"
    else -> "自動追加"
}
