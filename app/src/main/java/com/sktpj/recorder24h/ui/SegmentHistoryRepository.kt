package com.sktpj.recorder24h.ui

import android.content.Context
import com.sktpj.recorder24h.storage.StoragePolicy
import com.sktpj.recorder24h.transcription.TranscriptionRepository
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale

data class TranscriptChunk(
    val startMs: Long,
    val endMs: Long,
    val text: String
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
    val audioPath: String?,
    val audioAvailable: Boolean,
    val transcriptText: String?,
    val transcriptChunks: List<TranscriptChunk>,
    val transcriptModel: String?,
    val transcribedAtMs: Long
) {
    val hasTranscript: Boolean
        get() = transcriptText != null

    val durationMs: Long
        get() = if (startedAtMs > 0L && endedAtMs >= startedAtMs) endedAtMs - startedAtMs else 0L

    val needsAttention: Boolean
        get() = status == "FAILED" || status == "CORRUPT" || status == "RETRY_WAIT"
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
        var status: String = "READY",
        var reason: String? = null,
        var transcriptText: String? = null,
        var transcriptChunks: List<TranscriptChunk> = emptyList(),
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
                audioPath = audio?.absolutePath,
                audioAvailable = audio != null,
                transcriptText = builder.transcriptText,
                transcriptChunks = builder.transcriptChunks,
                transcriptModel = builder.transcriptModel,
                transcribedAtMs = builder.transcribedAtMs
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
                            // QUEUED is an internal scheduler state: a Worker has started but is
                            // waiting for the single local Whisper inference slot. User-facing UI
                            // should present that as ordinary waiting, not active transcription.
                            val status = if (rawStatus == "QUEUED") "READY" else rawStatus
                            val reason = if (rawStatus == "QUEUED") null else rawReason

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
                            builder.status = status
                            builder.reason = reason
                        } catch (_: Exception) {
                            // The recorder process can be appending the final line while the UI reads it.
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
                builder.transcriptText = row.optString("text", "")
                val chunks = mutableListOf<TranscriptChunk>()
                row.optJSONArray("segments")?.let { segments ->
                    for (index in 0 until segments.length()) {
                        val segment = segments.optJSONObject(index) ?: continue
                        val chunkText = segment.optString("text", "").trim()
                        val startMs = segment.optLong("startMs", -1L)
                        val endMs = segment.optLong("endMs", -1L)
                        if (chunkText.isNotBlank() && startMs >= 0L && endMs >= startMs) {
                            chunks += TranscriptChunk(startMs, endMs, chunkText)
                        }
                    }
                }
                builder.transcriptChunks = chunks
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
