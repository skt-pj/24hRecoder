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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiPriorityGate
import com.sktpj.recorder24h.ai.AiQueueStore
import com.sktpj.recorder24h.transcription.TranscriptionPriorityController
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
    val context = LocalContext.current
    var selectedPriorityId by remember { mutableStateOf<String?>(null) }
    var priorityRevision by remember { mutableIntStateOf(0) }
    val queued = remember(records, priorityRevision) {
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

    LaunchedEffect(queued) {
        if (selectedPriorityId != null && queued.none { it.segmentId == selectedPriorityId }) {
            selectedPriorityId = null
        }
    }

    if (queued.isEmpty()) {
        EmptyQueue("文字起こしキューは空です")
        return
    }

    val selectedIndex = queued.indexOfFirst { it.segmentId == selectedPriorityId }
    val selectedRecord = queued.getOrNull(selectedIndex)
    val canMoveUp = selectedRecord != null &&
        selectedRecord.status != "TRANSCRIBING" &&
        selectedIndex > 0 && queued[selectedIndex - 1].status != "TRANSCRIBING"
    val canMoveDown = selectedRecord != null &&
        selectedRecord.status != "TRANSCRIBING" &&
        selectedIndex >= 0 && selectedIndex + 1 < queued.size

    Column(Modifier.fillMaxSize()) {
        PriorityControls(
            selected = selectedRecord != null,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = {
                val reordered = swapQueueItem(queued, selectedIndex, selectedIndex - 1)
                if (TranscriptionPriorityController.applyOrder(
                        context,
                        reordered.map { it.segmentId }
                    )
                ) {
                    priorityRevision++
                }
            },
            onMoveDown = {
                val reordered = swapQueueItem(queued, selectedIndex, selectedIndex + 1)
                if (TranscriptionPriorityController.applyOrder(
                        context,
                        reordered.map { it.segmentId }
                    )
                ) {
                    priorityRevision++
                }
            }
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(queued, key = { _, item -> item.segmentId }) { index, record ->
                TranscriptionQueueRow(
                    record = record,
                    priority = index + 1,
                    selectedForPriority = record.segmentId == selectedPriorityId,
                    onPrioritySelect = {
                        if (record.status != "TRANSCRIBING") {
                            selectedPriorityId = if (selectedPriorityId == record.segmentId) {
                                null
                            } else {
                                record.segmentId
                            }
                        }
                    },
                    onOpen = { onSelect(record) },
                    onRemove = { onRemove(record) }
                )
                HorizontalDivider()
            }
        }
    }
}

private fun swapQueueItem(
    source: List<SegmentRecord>,
    first: Int,
    second: Int
): List<SegmentRecord> {
    if (first !in source.indices || second !in source.indices) return source
    val mutable = source.toMutableList()
    val value = mutable[first]
    mutable[first] = mutable[second]
    mutable[second] = value
    return mutable
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptionQueueRow(
    record: SegmentRecord,
    priority: Int,
    selectedForPriority: Boolean,
    onPrioritySelect: () -> Unit,
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
                .padding(horizontal = 10.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectedForPriority,
                onClick = onPrioritySelect,
                enabled = record.status != "TRANSCRIBING"
            )
            Column(Modifier.weight(1f)) {
                Text(
                    formatQueueDateTime(record),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Text(
                    "優先度 $priority",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
    var selectedPriorityId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(items) {
        if (selectedPriorityId != null && items.none { it.id == selectedPriorityId }) {
            selectedPriorityId = null
        }
    }

    if (items.isEmpty()) {
        EmptyQueue("AI要約キューは空です")
        return
    }

    val selectedIndex = items.indexOfFirst { it.id == selectedPriorityId }
    val selectedItem = items.getOrNull(selectedIndex)
    val canMoveSelected = selectedItem != null && !AiPriorityGate.isActive(selectedItem.id)
    val canMoveUp = canMoveSelected && selectedIndex > 0 &&
        !AiPriorityGate.isActive(items[selectedIndex - 1].id)
    val canMoveDown = canMoveSelected && selectedIndex >= 0 && selectedIndex + 1 < items.size &&
        !AiPriorityGate.isActive(items[selectedIndex + 1].id)

    Column(Modifier.fillMaxSize()) {
        PriorityControls(
            selected = selectedItem != null,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = { selectedPriorityId?.let { AiQueueStore.moveUp(context, it) } },
            onMoveDown = { selectedPriorityId?.let { AiQueueStore.moveDown(context, it) } }
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                AiSummaryQueueRow(
                    item = item,
                    priority = index + 1,
                    selectedForPriority = item.id == selectedPriorityId,
                    onPrioritySelect = {
                        if (!AiPriorityGate.isActive(item.id)) {
                            selectedPriorityId = if (selectedPriorityId == item.id) null else item.id
                        }
                    },
                    onOpen = {
                        context.startActivity(
                            AiQueueTargetActivity.createIntent(
                                context,
                                item.kind,
                                item.periodStartMs,
                                item.periodEndMs
                            )
                        )
                    },
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
}

@Composable
private fun PriorityControls(
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "上にある項目ほど優先" else "左の丸で項目を選択",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) {
            Text("↑ 上へ")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) {
            Text("↓ 下へ")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiSummaryQueueRow(
    item: AiQueueStore.Entry,
    priority: Int,
    selectedForPriority: Boolean,
    onPrioritySelect: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember(item.id) { mutableStateOf(false) }
    val activelyProcessing = AiPriorityGate.isActive(item.id)

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        if (item.state != AiQueueStore.STATE_RUNNING) {
                            menuExpanded = true
                        }
                    }
                )
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selectedForPriority,
                onClick = onPrioritySelect,
                enabled = !activelyProcessing
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${aiKindLabel(item.kind)}  ${aiPeriodLabel(item)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    buildString {
                        append("優先度 ").append(priority).append(" • ")
                        append(if (item.requestType == AiQueueStore.REQUEST_MANUAL) "ユーザー指定" else "定期実行")
                        if (item.message.isNotBlank()) append(" • ").append(item.message)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                aiQueueStateLabel(item),
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

private fun aiQueueStateLabel(item: AiQueueStore.Entry): String {
    if (AiPriorityGate.isActive(item.id)) {
        val elapsed = AiPriorityGate.activeElapsedMs(item.id)
        return "処理中 ${formatQueueElapsed(elapsed)}"
    }
    return when (item.state) {
        AiQueueStore.STATE_RUNNING -> "優先度待ち"
        AiQueueStore.STATE_WAITING_DATA -> "データ待ち"
        AiQueueStore.STATE_RETRY_WAIT -> "再試行待ち"
        AiQueueStore.STATE_QUEUED -> "実行待ち"
        AiQueueStore.STATE_FAILED -> "失敗"
        else -> item.state
    }
}

private fun formatQueueElapsed(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.JAPAN, "%02d:%02d", minutes, seconds)
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
