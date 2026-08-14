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

/**
 * Realtime display/editor at Whisper decoder-segment granularity.
 *
 * A 30-second window is only the inference scheduling unit. When Whisper returns multiple
 * timestamped segments, each segment remains independently visible and editable here.
 */
@Composable
internal fun EditableLiveSegmentedFinalCard(item: FullStreamingStateStore.RecentFinal) {
    if (item.segments.isEmpty()) {
        EditableLiveFinalCard(item)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.segments.forEach { segment ->
            EditableLiveSegmentCard(item, segment)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableLiveSegmentCard(
    item: FullStreamingStateStore.RecentFinal,
    segment: FullStreamingStateStore.RecentFinalSegment
) {
    val context = LocalContext.current
    val absoluteStartMs = if (item.startAtMs > 0L) item.startAtMs + segment.startMs else 0L
    val absoluteEndMs = if (item.startAtMs > 0L) item.startAtMs + segment.endMs else 0L
    val enrollmentKey = "${item.id}:seg:${segment.index}"

    var menuExpanded by remember(item.id, segment.index) { mutableStateOf(false) }
    var editingText by remember(item.id, segment.index) { mutableStateOf(false) }
    var editingSpeaker by remember(item.id, segment.index) { mutableStateOf(false) }
    var confirmingDelete by remember(item.id, segment.index) { mutableStateOf(false) }

    if (editingText) {
        var value by remember(item.id, segment.index, segment.text) { mutableStateOf(segment.text) }
        AlertDialog(
            onDismissRequest = { editingText = false },
            title = { Text("文字起こしを編集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        liveSegmentTimeRange(absoluteStartMs, absoluteEndMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("内容") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val record = findMatchingSegmentRecord(context, item)
                    val updated = FullStreamingStateStore.editRecentFinalSegmentText(
                        context,
                        item.id,
                        segment.index,
                        value,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    editingText = false
                    Toast.makeText(
                        context,
                        if (updated) "この発話の文字起こしを更新しました" else "更新対象が見つかりませんでした",
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
        var speaker by remember(item.id, segment.index, segment.speaker) {
            mutableStateOf(segment.speaker.ifBlank { "判定不能" })
        }
        AlertDialog(
            onDismissRequest = { editingSpeaker = false },
            title = { Text("この発話の話者を選択") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        liveSegmentTimeRange(absoluteStartMs, absoluteEndMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        "任意の名前も入力できます。変更はこの時刻区間だけに適用します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val normalized = speaker.trim().ifBlank { "判定不能" }
                    val record = findMatchingSegmentRecord(context, item)
                    val updated = FullStreamingStateStore.setRecentFinalSegmentSpeaker(
                        context,
                        item.id,
                        segment.index,
                        normalized,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    if (updated) {
                        LiveSpeakerEnrollmentWorker.reconcile(
                            context,
                            enrollmentKey,
                            absoluteStartMs,
                            absoluteEndMs,
                            normalized == "自分"
                        )
                    }
                    editingSpeaker = false
                    Toast.makeText(
                        context,
                        if (updated) "この発話の話者を更新しました" else "更新対象が見つかりませんでした",
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
            title = { Text("この発話だけ削除しますか？") },
            text = {
                Text(
                    "${liveSegmentTimeRange(absoluteStartMs, absoluteEndMs)} の発話だけをリアルタイム表示から削除します。録音音声は削除しません。夜間確定後は対応する時刻区間にも反映します。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    val record = findMatchingSegmentRecord(context, item)
                    val deleted = FullStreamingStateStore.deleteRecentFinalSegment(
                        context,
                        item.id,
                        segment.index,
                        record?.segmentId,
                        record?.startedAtMs ?: 0L,
                        record?.endedAtMs ?: 0L
                    )
                    if (deleted) {
                        LiveSpeakerEnrollmentWorker.reconcile(
                            context,
                            enrollmentKey,
                            absoluteStartMs,
                            absoluteEndMs,
                            false
                        )
                    }
                    confirmingDelete = false
                    Toast.makeText(
                        context,
                        if (deleted) "この発話を削除しました" else "削除対象が見つかりませんでした",
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
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        liveSegmentTimeRange(absoluteStartMs, absoluteEndMs),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        segment.speaker.ifBlank { "判定不能" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("この発話を編集") },
                onClick = {
                    menuExpanded = false
                    editingText = true
                }
            )
            DropdownMenuItem(
                text = { Text("この発話の話者を選択") },
                onClick = {
                    menuExpanded = false
                    editingSpeaker = true
                }
            )
            DropdownMenuItem(
                text = { Text("この発話を削除") },
                onClick = {
                    menuExpanded = false
                    confirmingDelete = true
                }
            )
        }
    }
}

private fun findMatchingSegmentRecord(
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

private fun liveSegmentTimeRange(startMs: Long, endMs: Long): String {
    if (startMs <= 0L) return "--:--:--"
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
    val start = formatter.format(Date(startMs))
    val end = formatter.format(Date(if (endMs >= startMs) endMs else startMs))
    return "$start – $end"
}
