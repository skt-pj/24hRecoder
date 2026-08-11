package com.sktpj.recorder24h

import android.content.Context
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
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

private data class AiQueueWorkItem(
    val id: String,
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    val tags: Set<String>
)

@Composable
internal fun UnifiedQueueScreen(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit,
    onRemove: (SegmentRecord) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(QueueTab.TRANSCRIPTION) }
    var aiQueue by remember { mutableStateOf<List<AiQueueWorkItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            aiQueue = withContext(Dispatchers.IO) { readAiQueue(context) }
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
            .filter { it.status == "QUEUED" || it.status == "RETRY_WAIT" }
            .sortedBy {
                if (it.queueEnqueuedAtMs > 0L) it.queueEnqueuedAtMs else it.stateChangedAtMs
            }
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
                    onLongClick = { menuExpanded = true }
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
                queueSourceLabel(record),
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
private fun AiSummaryQueueTab(items: List<AiQueueWorkItem>) {
    if (items.isEmpty()) {
        EmptyQueue("AI要約キューは空です")
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(aiQueueLabel(item), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        aiQueueSourceLabel(item),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    aiQueueStateLabel(item),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyQueue(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun readAiQueue(context: Context): List<AiQueueWorkItem> {
    return try {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTag("ai-analysis")
            .get()
            .asSequence()
            .filter {
                it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED ||
                    it.state == WorkInfo.State.ENQUEUED
            }
            .map {
                AiQueueWorkItem(
                    id = it.id.toString(),
                    state = it.state,
                    runAttemptCount = it.runAttemptCount,
                    tags = it.tags
                )
            }
            .sortedWith(
                compareBy<AiQueueWorkItem>(
                    { aiQueueStateOrder(it.state) },
                    { aiQueueLabel(it) },
                    { it.id }
                )
            )
            .toList()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun aiQueueLabel(item: AiQueueWorkItem): String {
    return when {
        "ai-analysis-hourly" in item.tags -> "1時間要約"
        "ai-analysis-daily" in item.tags -> "1日要約"
        "ai-analysis-rollup" in item.tags || "ai-rollup" in item.tags || "ai-rollup-now" in item.tags ->
            "週・月・年の集約"
        "ai-analysis-now" in item.tags -> "AI要約"
        else -> "AI要約"
    }
}

private fun aiQueueSourceLabel(item: AiQueueWorkItem): String {
    return if ("ai-analysis-now" in item.tags || "ai-rollup-now" in item.tags) {
        "今すぐ実行"
    } else {
        "定期実行"
    }
}

private fun aiQueueStateLabel(item: AiQueueWorkItem): String {
    return when (item.state) {
        WorkInfo.State.RUNNING -> "処理中"
        WorkInfo.State.BLOCKED -> "待機中"
        WorkInfo.State.ENQUEUED -> if (item.runAttemptCount > 0) "再試行待ち" else "待機中"
        else -> item.state.name
    }
}

private fun aiQueueStateOrder(state: WorkInfo.State): Int {
    return when (state) {
        WorkInfo.State.RUNNING -> 0
        WorkInfo.State.BLOCKED -> 1
        WorkInfo.State.ENQUEUED -> 2
        else -> 3
    }
}

private fun queueSourceLabel(record: SegmentRecord): String =
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
