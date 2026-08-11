package com.sktpj.recorder24h

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiQueueStore
import com.sktpj.recorder24h.ui.SegmentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class QueueTab(val label: String) {
    TRANSCRIPTION("文字起こし"),
    AI_SUMMARY("AI要約")
}

@Composable
internal fun UnifiedQueueScreen(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit,
    onRemove: (SegmentRecord) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(QueueTab.TRANSCRIPTION) }
    var aiQueue by remember { mutableStateOf<List<AiQueueStore.Entry>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            aiQueue = withContext(Dispatchers.IO) { AiQueueStore.load(context) }
            delay(1_000L)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            QueueTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }

        when (selectedTab) {
            QueueTab.TRANSCRIPTION -> TranscriptionQueueTab(
                records = records,
                onSelect = onSelect,
                onRemove = onRemove
            )
            QueueTab.AI_SUMMARY -> AiSummaryQueueTab(aiQueue)
        }
    }
}

@Composable
private fun TranscriptionQueueTab(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit,
    onRemove: (SegmentRecord) -> Unit
) {
    val queued = remember(records) {
        records
            .filter {
                it.status == "QUEUED" ||
                    it.status == "RETRY_WAIT" ||
                    it.status == "TRANSCRIBING"
            }
            .sortedWith(
                compareBy<SegmentRecord> {
                    if (it.status == "TRANSCRIBING") 0 else 1
                }.thenBy {
                    if (it.queueEnqueuedAtMs > 0L) it.queueEnqueuedAtMs else it.stateChangedAtMs
                }
            )
    }

    if (queued.isEmpty()) {
        EmptyQueue("文字起こしキューは空です")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(queued, key = { _, item -> item.segmentId }) { _, record ->
            TranscriptionQueueRow(
                record = record,
                onOpen = { onSelect(record) },
                onRemove = { onRemove(record) }
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptionQueueRow(
    record: SegmentRecord,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember(record.segmentId) { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        if (record.status != "TRANSCRIBING") {
                            menuExpanded = true
                        }
                    }
                )
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatQueueDateTime(record),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                transcriptionQueueStatusLabel(record),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("キューから削除") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun AiSummaryQueueTab(items: List<AiQueueStore.Entry>) {
    val context = LocalContext.current
    if (items.isEmpty()) {
        EmptyQueue("AI要約キューは空です")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            AiSummaryQueueRow(
                item = item,
                onRemove = {
                    AiAnalysisScheduler.removeTarget(
                        context,
                        item.kind,
                        item.periodStartMs,
                        item.periodEndMs
                    )
                }
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiSummaryQueueRow(
    item: AiQueueStore.Entry,
    onRemove: () -> Unit
) {
    var menuExpanded by remember(item.id) { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${aiKindLabel(item.kind)}  ${aiPeriodLabel(item)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    buildString {
                        append(if (item.requestType == AiQueueStore.REQUEST_MANUAL) "ユーザー指定" else "定期実行")
                        if (item.message.isNotBlank()) append(" • ").append(item.message)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                aiQueueStateLabel(item.state),
                style = MaterialTheme.typography.labelLarge,
                color = if (item.state == AiQueueStore.STATE_FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("AI要約キューから削除") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun EmptyQueue(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun aiKindLabel(kind: String): String = when (kind) {
    "hourly" -> "1時間要約"
    "daily" -> "1日要約"
    "weekly" -> "週まとめ"
    "monthly" -> "月まとめ"
    "yearly" -> "年まとめ"
    else -> "AI要約"
}

private fun aiPeriodLabel(item: AiQueueStore.Entry): String {
    if (item.periodStartMs <= 0L || item.periodEndMs <= item.periodStartMs) return "対象期間不明"
    val day = SimpleDateFormat("M/d", Locale.JAPAN)
    val time = SimpleDateFormat("HH:mm", Locale.JAPAN)
    val startDay = day.format(Date(item.periodStartMs))
    val endDay = day.format(Date(item.periodEndMs))
    return when (item.kind) {
        "hourly" -> "$startDay ${time.format(Date(item.periodStartMs))}–${time.format(Date(item.periodEndMs))}"
        "daily" -> "$startDay 1日"
        "monthly" -> SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(Date(item.periodStartMs))
        "yearly" -> SimpleDateFormat("yyyy年", Locale.JAPAN).format(Date(item.periodStartMs))
        else -> if (startDay == endDay) startDay else "$startDay–$endDay"
    }
}

private fun aiQueueStateLabel(state: String): String = when (state) {
    AiQueueStore.STATE_RUNNING -> "処理中"
    AiQueueStore.STATE_WAITING_DATA -> "データ待ち"
    AiQueueStore.STATE_RETRY_WAIT -> "再試行待ち"
    AiQueueStore.STATE_QUEUED -> "実行待ち"
    AiQueueStore.STATE_FAILED -> "失敗"
    else -> state
}

private fun transcriptionQueueStatusLabel(record: SegmentRecord): String {
    val source = transcriptionQueueSourceLabel(record)
    val state = when (record.status) {
        "TRANSCRIBING" -> "文字起こし中"
        "RETRY_WAIT" -> "再試行待ち"
        else -> "実行待ち"
    }
    return "$state • $source"
}

private fun transcriptionQueueSourceLabel(record: SegmentRecord): String =
    if (record.reason.orEmpty().startsWith("MANUAL_")) "ユーザー追加" else "自動追加"

private fun formatQueueDateTime(record: SegmentRecord): String {
    if (record.startedAtMs <= 0L) return "日時不明"
    val day = SimpleDateFormat("M/d", Locale.JAPAN).format(Date(record.startedAtMs))
    val time = SimpleDateFormat("HH:mm", Locale.JAPAN)
    val start = time.format(Date(record.startedAtMs))
    return if (record.endedAtMs > record.startedAtMs) {
        "$day $start–${time.format(Date(record.endedAtMs))}"
    } else {
        "$day $start"
    }
}
