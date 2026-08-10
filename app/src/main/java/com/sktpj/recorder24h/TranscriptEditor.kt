package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sktpj.recorder24h.transcription.SpeakerEnrollmentWorker
import com.sktpj.recorder24h.transcription.SpeakerIdentifier
import com.sktpj.recorder24h.transcription.TranscriptEditRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import com.sktpj.recorder24h.ui.TranscriptChunk

@Composable
internal fun EditableTranscriptChunk(record: SegmentRecord, chunk: TranscriptChunk, timeLabel: String) {
    val context = LocalContext.current
    var editing by remember(record.segmentId, chunk.editKey) { mutableStateOf(false) }

    if (editing) {
        TranscriptEditDialog(
            initialText = chunk.text,
            initialSpeaker = chunk.speaker,
            canReset = chunk.manuallyEdited,
            onDismiss = { editing = false },
            onReset = {
                TranscriptEditRepository.delete(context, record.segmentId, chunk.editKey)
                editing = false
                Toast.makeText(context, "手動修正を解除しました", Toast.LENGTH_SHORT).show()
            },
            onSave = { text, speaker ->
                TranscriptEditRepository.save(context, record.segmentId, chunk.editKey, text, speaker)
                if (speaker == "自分" && chunk.speaker != "自分" && record.audioPath != null) {
                    SpeakerEnrollmentWorker.enqueue(
                        context,
                        record.segmentId,
                        record.audioPath,
                        chunk.startMs,
                        chunk.endMs
                    )
                }
                editing = false
                Toast.makeText(context, "会話ログを更新しました", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                timeLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        transcriptSpeakerLabel(chunk.speaker),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        autoSpeakerDescription(chunk),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { editing = true }) { Text("編集") }
            }
            SelectionContainer {
                Text(chunk.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
            }
        }
    }
}

@Composable
internal fun EditableWholeTranscript(record: SegmentRecord) {
    val context = LocalContext.current
    val text = record.transcriptText.orEmpty()
    val editKey = record.transcriptEditKey ?: return
    val speaker = record.transcriptSpeaker ?: "判定不能"
    var editing by remember(record.segmentId, editKey) { mutableStateOf(false) }

    if (editing) {
        TranscriptEditDialog(
            initialText = text,
            initialSpeaker = speaker,
            canReset = record.transcriptManuallyEdited,
            onDismiss = { editing = false },
            onReset = {
                TranscriptEditRepository.delete(context, record.segmentId, editKey)
                editing = false
                Toast.makeText(context, "手動修正を解除しました", Toast.LENGTH_SHORT).show()
            },
            onSave = { editedText, editedSpeaker ->
                TranscriptEditRepository.save(context, record.segmentId, editKey, editedText, editedSpeaker)
                editing = false
                Toast.makeText(context, "会話ログを更新しました", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(transcriptSpeakerLabel(speaker), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { editing = true }) { Text("編集") }
            }
            SelectionContainer {
                Text(if (text.isBlank()) "（文字起こし結果は空です）" else text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
            }
        }
    }
}

@Composable
private fun TranscriptEditDialog(
    initialText: String,
    initialSpeaker: String,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var speaker by remember(initialSpeaker) { mutableStateOf(initialSpeaker) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会話ログを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("自分", "他人", "判定不能").forEach { label ->
                        FilterChip(
                            selected = speaker == label,
                            onClick = { speaker = label },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = speaker,
                    onValueChange = { speaker = it },
                    label = { Text("話者") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "話者名は任意の名前に変更できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalizedSpeaker = speaker.trim().ifBlank { "判定不能" }
                    onSave(text, normalizedSpeaker)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (canReset) {
                    TextButton(onClick = onReset) { Text("自動に戻す") }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        }
    )
}

internal fun transcriptSpeakerLabel(speaker: String): String = "話者: $speaker"

private fun autoSpeakerDescription(chunk: TranscriptChunk): String {
    val label = when (chunk.autoSpeaker) {
        SpeakerIdentifier.SELF -> "自分"
        SpeakerIdentifier.OTHER -> "他人"
        else -> "判定不能"
    }
    val score = chunk.autoSpeakerScore
    val automatic = if (score == null) "自動判定: $label" else "自動判定: $label  ${String.format("%.2f", score)}"
    return if (chunk.manuallyEdited) "$automatic / 手動修正済み" else automatic
}
