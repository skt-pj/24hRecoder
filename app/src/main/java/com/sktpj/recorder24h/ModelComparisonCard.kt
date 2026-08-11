package com.sktpj.recorder24h

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.WhisperModelManager
import com.sktpj.recorder24h.ui.SegmentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Per-record transcription information.
 *
 * The old per-record model-comparison UI was intentionally removed. This card now shows only
 * facts about the transcript that was actually saved for the record: the model used and the
 * elapsed successful transcription processing time derived from the segment journal.
 */
@Composable
fun TranscriptionProcessingCard(record: SegmentRecord) {
    val context = LocalContext.current
    var processingMs by remember(record.segmentId, record.transcribedAtMs) {
        mutableStateOf<Long?>(null)
    }

    LaunchedEffect(record.segmentId, record.transcribedAtMs, record.status) {
        processingMs = withContext(Dispatchers.IO) {
            latestSuccessfulProcessingMs(context, record.segmentId)
        }
    }

    val modelLabel = savedModelLabel(record.transcriptModel)
    val processingText = when {
        record.hasTranscript && processingMs != null -> formatProcessingDuration(processingMs!!)
        record.hasTranscript -> "記録なし"
        record.status == "TRANSCRIBING" -> "処理中"
        else -> "-"
    }

    Card {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("文字起こし処理", style = MaterialTheme.typography.titleLarge)
            ProcessingInfoRow("使用モデル", if (record.hasTranscript) modelLabel else "-")
            ProcessingInfoRow("処理時間", processingText)
            Text(
                "処理時間は、実際に文字起こしを開始してから結果を保存するまでの最新の成功処理時間です。キュー待ち時間は含みません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProcessingInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(0.62f),
            fontWeight = FontWeight.Medium
        )
    }
}

private fun savedModelLabel(engine: String?): String {
    if (engine.isNullOrBlank()) return "-"

    val modelId = when {
        engine.contains("/${WhisperModelManager.MODEL_MEDIUM_Q5}+") ->
            WhisperModelManager.MODEL_MEDIUM_Q5
        engine.contains("/${WhisperModelManager.MODEL_LARGE_V3_Q5}+") ||
            engine.contains("/large-v3-q5_0+") ->
            WhisperModelManager.MODEL_LARGE_V3_Q5
        engine.contains("/${WhisperModelManager.MODEL_KOTOBA_V2_Q5}+") ->
            WhisperModelManager.MODEL_KOTOBA_V2_Q5
        engine.contains("/${WhisperModelManager.MODEL_SMALL}+") ->
            WhisperModelManager.MODEL_SMALL
        engine.contains("/${WhisperModelManager.MODEL_BASE}+") ->
            WhisperModelManager.MODEL_BASE
        else -> null
    }

    return modelId?.let { WhisperModelManager.modelSpec(it)?.label } ?: engine
}

private fun latestSuccessfulProcessingMs(context: Context, segmentId: String): Long? {
    val journal = File(context.filesDir, "metadata/segments.jsonl")
    if (!journal.isFile) return null

    var activeStartMs: Long? = null
    var latestDurationMs: Long? = null

    try {
        FileInputStream(journal).use { input ->
            InputStreamReader(input, Charsets.UTF_8).buffered().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val row = JSONObject(line)
                        if (row.optString("segmentId", "") != segmentId) return@forEach

                        val status = row.optString("status", "")
                        val eventAtMs = row.optLong("endedAtMs", 0L)
                        when (status) {
                            "TRANSCRIBING" -> {
                                if (eventAtMs > 0L) activeStartMs = eventAtMs
                            }
                            "TRANSCRIBED" -> {
                                val started = activeStartMs
                                if (started != null && eventAtMs >= started) {
                                    latestDurationMs = eventAtMs - started
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    } catch (_: Exception) {
        return null
    }

    return latestDurationMs
}

private fun formatProcessingDuration(ms: Long): String {
    if (ms < 1_000L) return "${ms} ms"
    if (ms < 60_000L) return String.format(Locale.JAPAN, "%.1f秒", ms / 1000.0)

    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000.0
    return String.format(Locale.JAPAN, "%d分 %.1f秒", minutes, seconds)
}
