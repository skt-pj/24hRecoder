package com.sktpj.recorder24h

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.FullStreamingStateStore
import com.sktpj.recorder24h.transcription.LiveSpeakerEnrollmentWorker
import com.sktpj.recorder24h.ui.SegmentHistoryRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Long-press actions for one finalized realtime utterance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditableLiveFinalCard(item: FullStreamingStateStore.RecentFinal) {
    val context = LocalContext.current
    var menuExpanded by remember(item.id) { mutableStateOf(false) }
    var editingText by remember(item.id) { mutableStateOf(false) }
    var editingSpeaker by remember(item.id) { mutableStateOf(false) }
    var confirmingDelete by remember(item.id) { mutableStateOf(false) }

    if (editingText) {
        var value by remember(item.id, item.text) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { editingText = false },
            title = { Text("文字起こしを編集") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("内容") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val record = findMatchingRecord(context, item)
                    val updated = FullStreamingStateStore.editRecentFinalText(
                        context,
                        item.id,
                        value,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    editingText = false
                    Toast.makeText(
                        context,
                        if (updated) "文字起こしを更新しました" else "更新対象が見つかりませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingText = false }) { Text("キャンセル") }
            }
        )
    }

    if (editingSpeaker) {
        var speaker by remember(item.id, item.speaker) {
            mutableStateOf(item.speaker?.takeIf { it.isNotBlank() } ?: "判定不能")
        }
        AlertDialog(
            onDismissRequest = { editingSpeaker = false },
            title = { Text("話者を選択") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        "任意の名前も入力できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val normalized = speaker.trim().ifBlank { "判定不能" }
                    val record = findMatchingRecord(context, item)
                    val updated = FullStreamingStateStore.setRecentFinalSpeaker(
                        context,
                        item.id,
                        normalized,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    if (updated) {
                        LiveSpeakerEnrollmentWorker.reconcile(
                            context,
                            item.id,
                            item.startAtMs,
                            item.endAtMs,
                            normalized == "自分"
                        )
                    }
                    editingSpeaker = false
                    Toast.makeText(
                        context,
                        if (updated) "話者を更新しました" else "更新対象が見つかりませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingSpeaker = false }) { Text("キャンセル") }
            }
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("この発話を削除しますか？") },
            text = { Text("リアルタイム表示から削除し、対応する5分履歴の文字起こしにも反映します。音声ファイル自体は削除しません。") },
            confirmButton = {
                Button(onClick = {
                    val record = findMatchingRecord(context, item)
                    val deleted = FullStreamingStateStore.deleteRecentFinal(
                        context,
                        item.id,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    if (deleted) {
                        LiveSpeakerEnrollmentWorker.reconcile(
                            context,
                            item.id,
                            item.startAtMs,
                            item.endAtMs,
                            false
                        )
                    }
                    confirmingDelete = false
                    Toast.makeText(
                        context,
                        if (deleted) "発話を削除しました" else "削除対象が見つかりませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("キャンセル") }
            }
        )
    }

    Box {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = { menuExpanded = true }
                )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatLiveFinalTime(item.startAtMs), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.speaker?.takeIf { it.isNotBlank() } ?: "確定",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("編集") },
                onClick = {
                    menuExpanded = false
                    editingText = true
                }
            )
            DropdownMenuItem(
                text = { Text("話者を選択") },
                onClick = {
                    menuExpanded = false
                    editingSpeaker = true
                }
            )
            DropdownMenuItem(
                text = { Text("削除") },
                onClick = {
                    menuExpanded = false
                    confirmingDelete = true
                }
            )
        }
    }
}

private fun findMatchingRecord(
    context: Context,
    item: FullStreamingStateStore.RecentFinal
): SegmentRecord? {
    val toleranceMs = 2_500L
    val candidates = SegmentHistoryRepository.load(context).filter { record ->
        record.startedAtMs > 0L && record.endedAtMs >= record.startedAtMs &&
            item.endAtMs >= record.startedAtMs - toleranceMs &&
            item.startAtMs <= record.endedAtMs + toleranceMs
    }
    if (candidates.isEmpty()) return null
    val center = (item.startAtMs + item.endAtMs) / 2L
    return candidates.minByOrNull { record ->
        val recordCenter = (record.startedAtMs + record.endedAtMs) / 2L
        abs(center - recordCenter)
    }
}

private fun formatLiveFinalTime(value: Long): String {
    if (value <= 0L) return "--:--:--"
    return SimpleDateFormat("M/d HH:mm:ss", Locale.JAPAN).format(Date(value))
}
