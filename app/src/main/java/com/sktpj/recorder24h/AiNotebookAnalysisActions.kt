package com.sktpj.recorder24h

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisRepository
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiProviderStore
import com.sktpj.recorder24h.ai.AiQueueStore
import com.sktpj.recorder24h.ui.SegmentHistoryRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private data class NotebookHourlyActionRow(
    val hour: Int,
    val periodStartMs: Long,
    val periodEndMs: Long,
    val hasSource: Boolean,
    val hasResult: Boolean,
    val pendingTranscription: Boolean,
    val queueState: String?,
    val periodFinished: Boolean,
    val periodStarted: Boolean
)

private data class NotebookDayActionSnapshot(
    val configured: Boolean,
    val dailyHasSource: Boolean,
    val dailyPendingTranscription: Boolean,
    val dailyQueueState: String?,
    val rows: List<NotebookHourlyActionRow>
)

@Composable
internal fun AiNotebookDayAnalysisActions(
    date: LocalDate,
    hourlySummaries: Map<Long, String>,
    dailyHasResult: Boolean
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var refreshToken by remember { mutableIntStateOf(0) }
    var snapshot by remember(date) {
        mutableStateOf(
            NotebookDayActionSnapshot(
                configured = AiProviderStore.isConfigured(context),
                dailyHasSource = false,
                dailyPendingTranscription = false,
                dailyQueueState = null,
                rows = emptyList()
            )
        )
    }

    LaunchedEffect(date, refreshToken) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) {
                loadNotebookDayActionSnapshot(context.applicationContext, date, zone)
            }
            delay(1_000L)
        }
    }

    val dailyStart = date.atStartOfDay(zone)
    val dailyEnd = dailyStart.plusDays(1)
    val dailyStartMs = dailyStart.toInstant().toEpochMilli()
    val dailyEndMs = dailyEnd.toInstant().toEpochMilli()
    val dailyActiveQueue = snapshot.dailyQueueState != null &&
        snapshot.dailyQueueState != AiQueueStore.STATE_FAILED
    val dailyActionLabel = when {
        dailyActiveQueue -> notebookQueueStateLabel(snapshot.dailyQueueState)
        snapshot.dailyPendingTranscription -> "待機"
        snapshot.dailyQueueState == AiQueueStore.STATE_FAILED && snapshot.dailyHasSource -> "再試行"
        dailyHasResult && snapshot.dailyHasSource -> "再分析"
        snapshot.dailyHasSource -> "分析"
        else -> "実行不可"
    }
    val dailyCanRun = snapshot.configured &&
        date <= LocalDate.now(zone) &&
        !dailyActiveQueue &&
        !snapshot.dailyPendingTranscription &&
        snapshot.dailyHasSource

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "1日単位",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${date.year}年${date.monthValue}月${date.dayOfMonth}日 00:00–24:00",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        dailyActiveQueue -> notebookQueueStateLabel(snapshot.dailyQueueState)
                        snapshot.dailyPendingTranscription && dailyHasResult ->
                            "生成済み・文字起こし待ち"
                        snapshot.dailyPendingTranscription -> "文字起こし待ち"
                        snapshot.dailyQueueState == AiQueueStore.STATE_FAILED && snapshot.dailyHasSource ->
                            "前回失敗・再試行可能"
                        dailyHasResult && snapshot.dailyHasSource -> "生成済み"
                        dailyHasResult -> "生成済み・元データなし"
                        snapshot.dailyHasSource -> "日次ノート未生成・分析可能"
                        else -> "データなし"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val queued = AiAnalysisScheduler.enqueuePeriod(
                            context,
                            AiAnalysisScheduler.KIND_DAILY,
                            dailyStartMs,
                            dailyEndMs
                        )
                        Toast.makeText(
                            context,
                            if (queued) "この1日をAI要約キューに追加しました" else "AI要約を登録できませんでした",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (queued) refreshToken++
                    },
                    enabled = dailyCanRun,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(dailyActionLabel)
                }
            }
        }

        Text(
            "1時間単位",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        if (snapshot.rows.isEmpty()) {
            Text(
                "24時間分のデータ状態を確認中…",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            snapshot.rows.asReversed().forEach { row ->
                NotebookHourlyActionCard(
                    row = row,
                    summary = hourlySummaries[row.periodStartMs].orEmpty(),
                    configured = snapshot.configured,
                    onRun = {
                        val queued = AiAnalysisScheduler.enqueuePeriod(
                            context,
                            AiAnalysisScheduler.KIND_HOURLY,
                            row.periodStartMs,
                            row.periodEndMs
                        )
                        Toast.makeText(
                            context,
                            if (queued) "この時間帯をAI要約キューに追加しました" else "AI要約を登録できませんでした",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (queued) refreshToken++
                    }
                )
            }
        }
    }
}

@Composable
private fun NotebookHourlyActionCard(
    row: NotebookHourlyActionRow,
    summary: String,
    configured: Boolean,
    onRun: () -> Unit
) {
    val activeQueue = row.queueState != null && row.queueState != AiQueueStore.STATE_FAILED
    val canRun = configured &&
        row.periodFinished &&
        !activeQueue &&
        row.hasSource

    val statusText = when {
        activeQueue -> notebookQueueStateLabel(row.queueState)
        !row.periodFinished && row.periodStarted -> "進行中"
        !row.periodStarted -> "未到達"
        row.queueState == AiQueueStore.STATE_FAILED && row.hasSource -> "前回失敗・再試行可能"
        row.queueState == AiQueueStore.STATE_FAILED -> "前回失敗・元データなし"
        row.hasResult && row.hasSource -> "生成済み"
        row.hasResult -> "生成済み・元データなし"
        row.hasSource && row.pendingTranscription -> "一部文字起こし待ち・手動実行可能"
        row.hasSource -> "未生成・実行可能"
        row.pendingTranscription -> "文字起こし待ち"
        else -> "データなし"
    }

    val actionLabel = when {
        activeQueue -> notebookQueueStateLabel(row.queueState)
        !row.periodFinished && row.periodStarted -> "進行中"
        !row.periodStarted -> "未到達"
        row.queueState == AiQueueStore.STATE_FAILED && row.hasSource -> "再試行"
        row.hasResult && row.hasSource -> "再実行"
        row.hasResult -> "再実行不可"
        row.hasSource -> "実行"
        row.pendingTranscription -> "待機"
        else -> "実行不可"
    }

    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    String.format(
                        Locale.JAPAN,
                        "%02d:00–%02d:00",
                        row.hour,
                        row.hour + 1
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (summary.isNotBlank()) {
                    Text(summary.trim())
                }
            }
            Button(onClick = onRun, enabled = canRun) {
                Text(actionLabel)
            }
        }
    }
}

private fun loadNotebookDayActionSnapshot(
    context: Context,
    date: LocalDate,
    zone: ZoneId
): NotebookDayActionSnapshot {
    val queueEntries = AiQueueStore.load(context)
    val records = SegmentHistoryRepository.load(context)
    val nowMs = System.currentTimeMillis()
    val dailyStart = date.atStartOfDay(zone)
    val dailyEnd = dailyStart.plusDays(1)
    val dailyStartMs = dailyStart.toInstant().toEpochMilli()
    val dailyEndMs = dailyEnd.toInstant().toEpochMilli()
    val dailyQueueState = queueEntries.firstOrNull {
        it.kind == AiAnalysisScheduler.KIND_DAILY && it.periodStartMs == dailyStartMs
    }?.state
    val hourlyQueue = queueEntries
        .filter { it.kind == AiAnalysisScheduler.KIND_HOURLY }
        .associateBy { it.periodStartMs }

    val rows = (0..23).map { hour ->
        val start = date.atTime(hour, 0).atZone(zone)
        val end = start.plusHours(1)
        val startMs = start.toInstant().toEpochMilli()
        val endMs = end.toInstant().toEpochMilli()
        NotebookHourlyActionRow(
            hour = hour,
            periodStartMs = startMs,
            periodEndMs = endMs,
            hasSource = notebookHasTranscriptSource(records, startMs, endMs),
            hasResult = AiAnalysisRepository.hourlyFile(context, startMs).isFile,
            pendingTranscription = notebookHasPendingTranscription(records, startMs, endMs),
            queueState = hourlyQueue[startMs]?.state,
            periodFinished = endMs <= nowMs,
            periodStarted = startMs <= nowMs
        )
    }

    return NotebookDayActionSnapshot(
        configured = AiProviderStore.isConfigured(context),
        dailyHasSource = notebookHasTranscriptSource(records, dailyStartMs, dailyEndMs),
        dailyPendingTranscription = notebookHasPendingTranscription(records, dailyStartMs, dailyEndMs),
        dailyQueueState = dailyQueueState,
        rows = rows
    )
}

private fun notebookHasTranscriptSource(
    records: List<SegmentRecord>,
    periodStartMs: Long,
    periodEndMs: Long
): Boolean {
    for (record in records) {
        val transcript = record.transcriptText?.trim().orEmpty()
        if (transcript.isEmpty()) continue

        val chunks = record.transcriptChunks
        if (chunks.isNotEmpty() && record.startedAtMs > 0L) {
            for (chunk in chunks) {
                if (chunk.text.isBlank()) continue
                val absoluteStart = record.startedAtMs + chunk.startMs
                val absoluteEnd = record.startedAtMs + chunk.endMs
                if (absoluteEnd > periodStartMs && absoluteStart < periodEndMs) {
                    return true
                }
            }
        } else if (notebookRecordOverlaps(record, periodStartMs, periodEndMs)) {
            return true
        }
    }
    return false
}

private fun notebookHasPendingTranscription(
    records: List<SegmentRecord>,
    periodStartMs: Long,
    periodEndMs: Long
): Boolean {
    for (record in records) {
        if (!notebookRecordOverlaps(record, periodStartMs, periodEndMs)) continue
        if (record.status == "CORRUPT") continue
        if (!record.audioAvailable) continue
        if (!record.hasTranscript ||
            record.status == "READY" ||
            record.status == "QUEUED" ||
            record.status == "RETRY_WAIT" ||
            record.status == "TRANSCRIBING"
        ) {
            return true
        }
    }
    return false
}

private fun notebookRecordOverlaps(
    record: SegmentRecord,
    periodStartMs: Long,
    periodEndMs: Long
): Boolean {
    val start = if (record.startedAtMs > 0L) record.startedAtMs else record.sortTimeMs
    if (start <= 0L) return false
    val end = if (record.endedAtMs > start) record.endedAtMs else start + 1L
    return start < periodEndMs && end > periodStartMs
}

private fun notebookQueueStateLabel(state: String?): String = when (state) {
    AiQueueStore.STATE_RUNNING -> "処理中"
    AiQueueStore.STATE_WAITING_DATA -> "データ待ち"
    AiQueueStore.STATE_RETRY_WAIT -> "再試行待ち"
    AiQueueStore.STATE_QUEUED -> "実行待ち"
    AiQueueStore.STATE_FAILED -> "失敗"
    else -> "待機中"
}
