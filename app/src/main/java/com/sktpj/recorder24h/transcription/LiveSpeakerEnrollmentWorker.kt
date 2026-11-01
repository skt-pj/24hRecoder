package com.sktpj.recorder24h.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sktpj.recorder24h.ui.SegmentHistoryRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reconciles a realtime manual speaker correction with the durable SELF speaker profile.
 *
 * A live utterance can be corrected before its five-minute recording/transcript is finalized.
 * Therefore this worker waits until the canonical transcript chunk(s) exist, then uses the same
 * segmentId + editKey enrollment keys as the normal transcript editor. That keeps later corrections
 * in either UI able to remove the exact enrollment instead of creating a second live-only profile.
 */
class LiveSpeakerEnrollmentWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    private data class CanonicalChunk(
        val startMs: Long,
        val endMs: Long,
        val editKey: String
    )

    override fun doWork(): Result {
        val entryId = inputData.getString(EXTRA_ENTRY_ID).orEmpty()
        val startAtMs = inputData.getLong(EXTRA_START_AT_MS, -1L)
        val endAtMs = inputData.getLong(EXTRA_END_AT_MS, -1L)
        val desiredSelf = inputData.getBoolean(EXTRA_DESIRED_SELF, false)
        if (entryId.isBlank() || startAtMs < 0L || endAtMs < startAtMs) return Result.failure()

        val currentSpeaker = currentRequestedSpeaker(entryId)
        if (desiredSelf && currentSpeaker != "自分") {
            removeMatchingEnrollments(startAtMs, endAtMs)
            return Result.success()
        }
        if (!desiredSelf && currentSpeaker == "自分") {
            // A newer SELF correction won the race; its unique work request replaces this one, but
            // guard again here in case this run was already executing when replacement happened.
            return Result.success()
        }

        val record = findMatchingRecord(startAtMs, endAtMs)
            ?: return retryCanonical("record-not-finalized", entryId)
        val chunks = matchingChunks(record, startAtMs, endAtMs)
        if (chunks.isEmpty()) return retryCanonical("canonical-chunks-not-ready", entryId)

        if (!desiredSelf) {
            removeEnrollments(record, chunks)
            log("LIVE_SELF_SPEAKER_ENROLLMENT_REMOVED", entryId, record, chunks.size)
            return Result.success()
        }

        // FullStreamingStateStore must have propagated the live manual label to the canonical
        // transcript sidecar before we learn from it. Never learn from an automatic guess alone.
        val edits = TranscriptEditRepository.load(applicationContext, record.segmentId)
        if (chunks.any { edits[it.editKey]?.speaker != "自分" }) {
            return retryCanonical("manual-self-not-bound-yet", entryId)
        }

        val audioPath = record.audioPath
        val audioFile = audioPath?.let(::File)
        if (audioFile == null || !audioFile.isFile) {
            log("LIVE_SELF_SPEAKER_ENROLLMENT_AUDIO_MISSING", entryId, record, chunks.size)
            return Result.success()
        }

        return try {
            var enrolled = 0
            for (chunk in chunks) {
                val key = SpeakerProfileStore.enrollmentKey(record.segmentId, chunk.editKey)
                if (SpeakerIdentifier.enroll(
                        applicationContext,
                        audioFile,
                        chunk.startMs,
                        chunk.endMs,
                        key
                    )
                ) {
                    enrolled++
                }
            }

            val stillSelf = currentRequestedSpeaker(entryId) == "自分"
            if (!stillSelf) {
                removeEnrollments(record, chunks)
                log("LIVE_SELF_SPEAKER_ENROLLMENT_REVERTED", entryId, record, chunks.size)
                return Result.success()
            }

            log(
                if (enrolled > 0) "LIVE_SELF_SPEAKER_ENROLLED" else "LIVE_SELF_SPEAKER_ENROLLMENT_SKIPPED",
                entryId,
                record,
                enrolled
            )
            if (enrolled > 0) Result.success() else Result.failure()
        } catch (error: Exception) {
            AppLogger.event(
                applicationContext,
                "LIVE_SELF_SPEAKER_ENROLLMENT_FAILED",
                JSONObject()
                    .put("entryId", entryId)
                    .put("segmentId", record.segmentId)
                    .put("error", error.javaClass.simpleName)
            )
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    /**
     * 0.7.41 can enqueue one enrollment request for a decoder subsegment using an ID suffix
     * `<recent-entry-id>:seg:<index>`. Older callers still pass the bare recent-entry ID.
     */
    private fun currentRequestedSpeaker(requestId: String): String? {
        val marker = ":seg:"
        val markerIndex = requestId.lastIndexOf(marker)
        val baseId = if (markerIndex > 0) requestId.substring(0, markerIndex) else requestId
        val segmentIndex = if (markerIndex > 0) {
            requestId.substring(markerIndex + marker.length).toIntOrNull()
        } else null
        val current = FullStreamingStateStore.readRecentFinals(applicationContext)
            .firstOrNull { it.id == baseId } ?: return null
        return if (segmentIndex != null) {
            current.segments.firstOrNull { it.index == segmentIndex }?.speaker
        } else {
            current.speaker
        }
    }

    private fun findMatchingRecord(startAtMs: Long, endAtMs: Long): SegmentRecord? {
        val candidates = SegmentHistoryRepository.load(applicationContext).filter { record ->
            record.startedAtMs > 0L &&
                record.endedAtMs >= record.startedAtMs &&
                TranscriptionRepository.exists(applicationContext, record.segmentId) &&
                endAtMs >= record.startedAtMs - MATCH_TOLERANCE_MS &&
                startAtMs <= record.endedAtMs + MATCH_TOLERANCE_MS
        }
        if (candidates.isEmpty()) return null
        val center = (startAtMs + endAtMs) / 2L
        return candidates.minByOrNull { record ->
            abs(center - (record.startedAtMs + record.endedAtMs) / 2L)
        }
    }

    private fun canonicalChunks(record: SegmentRecord): List<CanonicalChunk> {
        val file = TranscriptionRepository.fileFor(applicationContext, record.segmentId)
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val segments = root.optJSONArray("segments") ?: return emptyList()
            buildList {
                for (index in 0 until segments.length()) {
                    val row = segments.optJSONObject(index) ?: continue
                    val sourceText = row.optString("text", "").trim()
                    val startMs = row.optLong("startMs", -1L)
                    val endMs = row.optLong("endMs", -1L)
                    if (sourceText.isEmpty() || startMs < 0L || endMs < startMs) continue
                    add(
                        CanonicalChunk(
                            startMs = startMs,
                            endMs = endMs,
                            editKey = TranscriptEditRepository.chunkKey(startMs, endMs, sourceText)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun matchingChunks(
        record: SegmentRecord,
        startAtMs: Long,
        endAtMs: Long
    ): List<CanonicalChunk> {
        val canonical = canonicalChunks(record)
        if (canonical.isEmpty()) return emptyList()
        val localStart = max(0L, startAtMs - record.startedAtMs)
        val localEnd = max(localStart, endAtMs - record.startedAtMs)
        val overlapping = canonical.filter { chunk ->
            max(chunk.startMs, localStart) <= min(chunk.endMs, localEnd)
        }
        if (overlapping.isNotEmpty()) return overlapping
        val center = (localStart + localEnd) / 2L
        val nearest = canonical.minByOrNull { chunk ->
            abs(center - (chunk.startMs + chunk.endMs) / 2L)
        } ?: return emptyList()
        val distance = abs(center - (nearest.startMs + nearest.endMs) / 2L)
        return if (distance <= MATCH_TOLERANCE_MS) listOf(nearest) else emptyList()
    }

    private fun removeMatchingEnrollments(startAtMs: Long, endAtMs: Long) {
        val record = findMatchingRecord(startAtMs, endAtMs) ?: return
        val chunks = matchingChunks(record, startAtMs, endAtMs)
        removeEnrollments(record, chunks)
    }

    private fun removeEnrollments(record: SegmentRecord, chunks: List<CanonicalChunk>) {
        for (chunk in chunks) {
            SpeakerProfileStore.removeEnrollment(
                applicationContext,
                SpeakerProfileStore.enrollmentKey(record.segmentId, chunk.editKey)
            )
        }
    }

    private fun retryCanonical(reason: String, entryId: String): Result {
        if (runAttemptCount < MAX_RETRIES) return Result.retry()
        AppLogger.event(
            applicationContext,
            "LIVE_SELF_SPEAKER_ENROLLMENT_CANONICAL_TIMEOUT",
            JSONObject()
                .put("entryId", entryId)
                .put("reason", reason)
                .put("attempt", runAttemptCount)
        )
        return Result.failure()
    }

    private fun log(event: String, entryId: String, record: SegmentRecord, count: Int) {
        AppLogger.event(
            applicationContext,
            event,
            JSONObject()
                .put("entryId", entryId)
                .put("segmentId", record.segmentId)
                .put("chunkCount", count)
        )
    }

    companion object {
        private const val EXTRA_ENTRY_ID = "liveSpeakerEntryId"
        private const val EXTRA_START_AT_MS = "liveSpeakerStartAtMs"
        private const val EXTRA_END_AT_MS = "liveSpeakerEndAtMs"
        private const val EXTRA_DESIRED_SELF = "liveSpeakerDesiredSelf"
        private const val MATCH_TOLERANCE_MS = 2_500L
        private const val MAX_RETRIES = 7

        @JvmStatic
        fun reconcile(
            context: Context,
            entryId: String,
            startAtMs: Long,
            endAtMs: Long,
            desiredSelf: Boolean
        ) {
            if (entryId.isBlank() || startAtMs < 0L || endAtMs < startAtMs) return
            val data = Data.Builder()
                .putString(EXTRA_ENTRY_ID, entryId)
                .putLong(EXTRA_START_AT_MS, startAtMs)
                .putLong(EXTRA_END_AT_MS, endAtMs)
                .putBoolean(EXTRA_DESIRED_SELF, desiredSelf)
                .build()
            val request = OneTimeWorkRequest.Builder(LiveSpeakerEnrollmentWorker::class.java)
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("live-speaker-enrollment")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "live-speaker-enrollment:$entryId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
