package com.sktpj.recorder24h.ui

import android.content.Context
import com.sktpj.recorder24h.storage.StoragePolicy
import com.sktpj.recorder24h.transcription.FullStreamingStateStore
import com.sktpj.recorder24h.transcription.LiveSegmentPolicyStore
import com.sktpj.recorder24h.transcription.SpeakerIdentifier
import com.sktpj.recorder24h.transcription.TranscriptEditRepository
import com.sktpj.recorder24h.transcription.TranscriptionRepository
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale

data class TranscriptChunk(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val sourceText: String,
    val speaker: String,
    val autoSpeaker: String,
    val autoSpeakerScore: Double?,
    val editKey: String,
    val manuallyEdited: Boolean
)

data class SegmentRecord(
    val segmentId: String,
    val fileName: String?,
    val fileSizeBytes: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val sortTimeMs: Long,
    val status: String,
    val reason: String?,
    val stateChangedAtMs: Long,
    val queueEnqueuedAtMs: Long,
    val audioPath: String?,
    val audioAvailable: Boolean,
    val transcriptText: String?,
    val transcriptChunks: List<TranscriptChunk>,
    val transcriptSpeaker: String?,
    val transcriptEditKey: String?,
    val transcriptManuallyEdited: Boolean,
    val transcriptModel: String?,
    val transcribedAtMs: Long,
    val liveOwned: Boolean,
    val fiveMinuteFinalEnabled: Boolean,
    val liveModelId: String?
) {
    val hasTranscript: Boolean
        get() = transcriptText != null

    val durationMs: Long
        get() = if (startedAtMs > 0L && endedAtMs >= startedAtMs) endedAtMs - startedAtMs else 0L

    val queueState: String
        get() = when (status) {
            "QUEUED", "RETRY_WAIT", "TRANSCRIBING" -> status
            else -> "NONE"
        }

    val dataState: String
        get() = when {
            status == "CORRUPT" -> "CORRUPT"
            hasTranscript && audioAvailable -> "AUDIO_AND_TRANSCRIPT"
            hasTranscript -> "TRANSCRIPT_ONLY"
            audioAvailable && queueState != "NONE" -> "AUDIO_PROCESSING"
            audioAvailable && liveOwned && !fiveMinuteFinalEnabled -> "LIVE_AUDIO_NO_TRANSCRIPT"
            audioAvailable -> "AUDIO_ONLY"
            else -> "METADATA_ONLY"
        }

    val needsAttention: Boolean
        get() = status == "FAILED" || status == "CORRUPT" || status == "RETRY_WAIT" ||
            (audioAvailable && !hasTranscript && queueState == "NONE")
}

object SegmentHistoryRepository {
    private data class Builder(
        val segmentId: String,
        var fileName: String? = null,
        var fileSizeBytes: Long = 0L,
        var recordedStartMs: Long = 0L,
        var recordedEndMs: Long = 0L,
        var fallbackStartMs: Long = 0L,
        var latestEventMs: Long = 0L,
        var queueEnqueuedAtMs: Long = 0L,
        var status: String = "READY",
        var reason: String? = null,
        var transcriptText: String? = null,
        var transcriptChunks: List<TranscriptChunk> = emptyList(),
        var transcriptSpeaker: String? = null,
        var transcriptEditKey: String? = null,
        var transcriptManuallyEdited: Boolean = false,
        var transcriptModel: String? = null,
        var transcribedAtMs: Long = 0L,
        var audioFile: File? = null
    )

    @JvmStatic
    fun load(context: Context): List<SegmentRecord> {
        val builders = linkedMapOf<String, Builder>()
        readJournal(context, builders)
        readTranscripts(context, builders)
        readAudioFiles(context, builders)

        return builders.values.map { builder ->
            val audio = builder.audioFile?.takeIf { it.isFile }
            val started = builder.recordedStartMs.takeIf { it > 0L } ?: builder.fallbackStartMs
            val ended = builder.recordedEndMs
            val liveOwned = FullStreamingStateStore.isOwned(context, builder.segmentId)
            val livePolicy = if (liveOwned) LiveSegmentPolicyStore.read(context, builder.segmentId) else null
            val sortTime = if (started > 0L) {
                started
            } else {
                listOf(
                    builder.transcribedAtMs,
                    audio?.lastModified() ?: 0L,
                    builder.latestEventMs
                ).maxOrNull() ?: 0L
            }

            SegmentRecord(
                segmentId = builder.segmentId,
                fileName = audio?.name ?: builder.fileName,
                fileSizeBytes = audio?.length() ?: builder.fileSizeBytes,
                startedAtMs = started,
                endedAtMs = ended,
                sortTimeMs = sortTime,
                status = builder.status,
                reason = builder.reason,
                stateChangedAtMs = builder.latestEventMs,
                queueEnqueuedAtMs = builder.queueEnqueuedAtMs,
                audioPath = audio?.absolutePath,
                audioAvailable = audio != null,
                transcriptText = builder.transcriptText,
                transcriptChunks = builder.transcriptChunks,
                transcriptSpeaker = builder.transcriptSpeaker,
                transcriptEditKey = builder.transcriptEditKey,
                transcriptManuallyEdited = builder.transcriptManuallyEdited,
                transcriptModel = builder.transcriptModel,
                transcribedAtMs = builder.transcribedAtMs,
                liveOwned = liveOwned,
                fiveMinuteFinalEnabled = livePolicy?.fiveMinuteFinalEnabled ?: false,
                liveModelId = livePolicy?.liveModelId
            )
        }.sortedByDescending { it.sortTimeMs }
    }

    private fun readJournal(context: Context, builders: MutableMap<String, Builder>) {
        val journal = File(context.filesDir, "metadata/segments.jsonl")
        if (!journal.isFile) return

        try {
            FileInputStream(journal).use { input ->
                InputStreamReader(input, Charsets.UTF_8).buffered().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        try {
                            val row = JSONObject(line)
                            val segmentId = row.optString("segmentId", "")
                            if (segmentId.isBlank() || segmentId == "unknown") return@forEach
                            val builder = builders.getOrPut(segmentId) { Builder(segmentId) }

                            if (!row.isNull("fileName")) {
                                val name = row.optString("fileName", "")
                                if (name.isNotBlank()) builder.fileName = name
                            }
                            val size = row.optLong("fileSize", 0L)
                            if (size > 0L) builder.fileSizeBytes = size

                            val start = row.optLong("startedAtMs", 0L)
                            val end = row.optLong("endedAtMs", 0L)
                            val rawStatus = row.optString("status", "READY")
                            val rawReason = if (row.isNull("reason")) null else row.optString("reason", null)
                            val status = rawStatus
                            val reason = rawReason

                            if (start > 0L && (builder.fallbackStartMs == 0L || start < builder.fallbackStartMs)) {
                                builder.fallbackStartMs = start
                            }
                            if (rawStatus == "READY" && rawReason == null && start > 0L && end >= start) {
                                if (builder.recordedStartMs == 0L || start < builder.recordedStartMs) {
                                    builder.recordedStartMs = start
                                    builder.recordedEndMs = end
                                }
                            } else if (rawStatus == "CORRUPT" && builder.recordedStartMs == 0L && start > 0L) {
                                builder.recordedStartMs = start
                                builder.recordedEndMs = end
                            }

                            builder.latestEventMs = maxOf(builder.latestEventMs, end)
                            if (rawReason?.endsWith("WORK_ENQUEUED") == true && end > 0L) {
                                builder.queueEnqueuedAtMs = end
                            }
                            builder.status = status
                            builder.reason = reason
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readTranscripts(context: Context, builders: MutableMap<String, Builder>) {
        val dir = TranscriptionRepository.getTranscriptDir(context)
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return
        files.forEach { file ->
            try {
                val row = JSONObject(readUtf8(file))
                val segmentId = row.optString("segmentId", "")
                    .ifBlank { file.name.removeSuffix(".json") }
                if (segmentId.isBlank()) return@forEach
                val builder = builders.getOrPut(segmentId) { Builder(segmentId) }
                val sourceText = row.optString("text", "")
                val edits = TranscriptEditRepository.load(context, segmentId)
                val chunks = mutableListOf<TranscriptChunk>()
                var hadTimedSegments = false
                row.optJSONArray("segments")?.let { segments ->
                    for (index in 0 until segments.length()) {
                        val segment = segments.optJSONObject(index) ?: continue
                        val chunkSourceText = segment.optString("text", "").trim()
                        val startMs = segment.optLong("startMs", -1L)
                        val endMs = segment.optLong("endMs", -1L)
                        if (chunkSourceText.isNotBlank() && startMs >= 0L && endMs >= startMs) {
                            hadTimedSegments = true
                            val autoSpeaker = segment.optString("autoSpeaker", SpeakerIdentifier.UNKNOWN)
                            val autoScore = if (segment.isNull("autoSpeakerScore")) {
                                null
                            } else {
                                segment.optDouble("autoSpeakerScore").takeIf { it.isFinite() }
                            }
                            val editKey = TranscriptEditRepository.chunkKey(startMs, endMs, chunkSourceText)
                            val edit = edits[editKey]
                            val effectiveText = edit?.text ?: chunkSourceText
                            if (effectiveText.isBlank()) continue
                            chunks += TranscriptChunk(
                                startMs = startMs,
                                endMs = endMs,
                                text = effectiveText,
                                sourceText = chunkSourceText,
                                speaker = edit?.speaker?.takeIf { it.isNotBlank() } ?: speakerLabel(autoSpeaker),
                                autoSpeaker = autoSpeaker,
                                autoSpeakerScore = autoScore,
                                editKey = editKey,
                                manuallyEdited = edit != null
                            )
                        }
                    }
                }
                builder.transcriptChunks = chunks
                if (hadTimedSegments) {
                    builder.transcriptText = chunks.joinToString(" ") { it.text }.trim()
                    builder.transcriptSpeaker = null
                    builder.transcriptEditKey = null
                    builder.transcriptManuallyEdited = edits.isNotEmpty()
                } else {
                    val editKey = TranscriptEditRepository.wholeKey(sourceText)
                    val edit = edits[editKey]
                    builder.transcriptText = edit?.text ?: sourceText
                    builder.transcriptSpeaker = edit?.speaker?.takeIf { it.isNotBlank() } ?: "判定不能"
                    builder.transcriptEditKey = editKey
                    builder.transcriptManuallyEdited = edit != null
                }
                builder.transcriptModel = row.optString("model", "")
                builder.transcribedAtMs = row.optLong("transcribedAtMs", file.lastModified())
                if (!row.isNull("audioFile")) {
                    val name = row.optString("audioFile", "")
                    if (name.isNotBlank()) builder.fileName = name
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun speakerLabel(value: String): String = when (value) {
        SpeakerIdentifier.SELF -> "自分"
        SpeakerIdentifier.OTHER -> "他人"
        else -> "判定不能"
    }

    private fun readAudioFiles(context: Context, builders: MutableMap<String, Builder>) {
        val dir = StoragePolicy.getAudioDir(context)
        val files = dir.listFiles { _, name ->
            name.endsWith(".m4a") || name.endsWith(".m4a.corrupt")
        } ?: return

        files.forEach { file ->
            val segmentId = extractSegmentId(file.name)
            if (segmentId == "unknown") return@forEach
            val builder = builders.getOrPut(segmentId) { Builder(segmentId) }
            builder.audioFile = file
            builder.fileName = file.name
            builder.fileSizeBytes = file.length()
            if (builder.latestEventMs == 0L) builder.latestEventMs = file.lastModified()
            if (file.name.endsWith(".corrupt")) {
                builder.status = "CORRUPT"
                if (builder.reason == null) builder.reason = "CORRUPT_AUDIO_FILE"
            }
        }
    }

    private fun readUtf8(file: File): String {
        FileInputStream(file).use { input ->
            return InputStreamReader(input, Charsets.UTF_8).readText()
        }
    }

    private fun extractSegmentId(fileName: String): String {
        val lastUnderscore = fileName.lastIndexOf('_')
        val suffix = fileName.indexOf(".m4a", maxOf(0, lastUnderscore))
        return if (lastUnderscore >= 0 && suffix > lastUnderscore) {
            fileName.substring(lastUnderscore + 1, suffix)
        } else {
            "unknown"
        }
    }

    @JvmStatic
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 10.0) {
            String.format(Locale.JAPAN, "%.1f MB", mb)
        } else {
            String.format(Locale.JAPAN, "%.0f MB", mb)
        }
    }
}
