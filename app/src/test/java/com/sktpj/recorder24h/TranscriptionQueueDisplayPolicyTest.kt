package com.sktpj.recorder24h

import com.sktpj.recorder24h.ui.SegmentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionQueueDisplayPolicyTest {
    private fun record(
        id: String,
        status: String,
        reason: String,
        enqueuedAt: Long = 100L
    ) = SegmentRecord(
        segmentId = id,
        fileName = "$id.m4a",
        fileSizeBytes = 100L,
        startedAtMs = 1793534400000L,
        endedAtMs = 1793534700000L,
        sortTimeMs = 1793534400000L,
        status = status,
        reason = reason,
        stateChangedAtMs = enqueuedAt,
        queueEnqueuedAtMs = enqueuedAt,
        audioPath = null,
        audioAvailable = true,
        transcriptText = null,
        transcriptChunks = emptyList(),
        transcriptSpeaker = null,
        transcriptEditKey = null,
        transcriptManuallyEdited = false,
        transcriptModel = null,
        transcribedAtMs = 0L,
        liveOwned = false,
        fiveMinuteFinalEnabled = false,
        liveModelId = null
    )

    @Test fun sixNightlySegmentsPlusOneManualAreSevenVisibleRows() {
        val nightly = (1..6).map {
            record(
                id = "night-$it",
                status = "QUEUED",
                reason = "NIGHTLY_HOURLY_BATCH_2026-11-01T21_WORK_ENQUEUED",
                enqueuedAt = it.toLong()
            )
        }
        val manual = record("manual", "QUEUED", "MANUAL_DIRECT_QUEUE_ENQUEUED", 20L)
        val result = visibleTranscriptionQueue(nightly + manual)
        assertEquals(7, result.size)
        assertEquals(7, result.map { it.segmentId }.distinct().size)
        assertEquals(6, result.count { transcriptionQueueSourceLabel(it) == "夜間確定" })
        assertEquals(1, result.count { transcriptionQueueSourceLabel(it) == "ユーザー追加" })
    }

    @Test fun includesAllQueueStatusesButNotReadyOrCompletedAudio() {
        val result = visibleTranscriptionQueue(listOf(
            record("ready", "READY", "NIGHTLY_HOURLY_PENDING"),
            record("waiting", "QUEUED", "LOCAL_TRANSCRIPTION_WORK_ENQUEUED"),
            record("retry", "RETRY_WAIT", "SOME_ERROR"),
            record("running", "TRANSCRIBING", "LOCAL_SINGLE_RUNNER_TRANSCRIBING"),
            record("done", "TRANSCRIBED", "NO_SPEECH_DETECTED")
        ))
        assertEquals(listOf("running", "waiting", "retry"), result.map { it.segmentId })
    }

    @Test fun repeatedJournalVersionsOfSameSegmentDoNotCreateTwoRows() {
        val result = visibleTranscriptionQueue(listOf(
            record("same", "QUEUED", "NIGHTLY_HOURLY_WORK_ENQUEUED", 10L),
            record("same", "QUEUED", "NIGHTLY_HOURLY_WORK_ENQUEUED", 20L)
        ))
        assertEquals(1, result.size)
        assertTrue(result.all { it.segmentId == "same" })
    }
}
