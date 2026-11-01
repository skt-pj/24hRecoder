package com.sktpj.recorder24h

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.sktpj.recorder24h.ai.AiQueueStore
import com.sktpj.recorder24h.ui.SegmentHistoryRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class AiRerunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AiRerunScreen(
                        onClose = { finish() },
                        onSubmitHourly = { startMs, endMs ->
                            val queued = AiAnalysisScheduler.enqueuePeriod(
                                this,
                                AiAnalysisScheduler.KIND_HOURLY,
                                startMs,
                                endMs
                            )
                            Toast.makeText(
                                this,
                                if (queued) "この時間帯をAI要約キューに追加しました" else "AI要約を登録できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                            queued
                        },
                        onSubmitDaily = { startMs, endMs ->
                            val queued = AiAnalysisScheduler.enqueuePeriod(
                                this,
                                AiAnalysisScheduler.KIND_DAILY,
                                startMs,
                                endMs
                            )
                            Toast.makeText(
                                this,
                                if (queued) "この1日をAI要約キューに追加しました" else "AI要約を登録できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                            queued
                        }
                    )
                }
            }
        }
    }
}

private data class HourlyActionRow(
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

@Composable
private fun AiRerunScreen(
    onClose: () -> Unit,
    onSubmitHourly: (Long, Long) -> Boolean,
    onSubmitDaily: (Long, Long) -> Boolean
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { ZonedDateTime.now(zone) }
    val previousHour = remember { now.truncatedTo(ChronoUnit.HOURS).minusHours(1) }
    var selectedDate by remember { mutableStateOf(previousHour.toLocalDate()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<HourlyActionRow>>(emptyList()) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN) }

    LaunchedEffect(selectedDate, refreshToken) {
        loading = true
        rows = withContext(Dispatchers.IO) {
            loadHourlyActionRows(context.applicationContext, selectedDate, zone)
        }
        loading = false
    }

    val dailyStart = selectedDate.atStartOfDay(zone)
    val dailyEnd = dailyStart.plusDays(1)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("AI再分析", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Text(
                "対象日を選び、1時間単位または1日単位で分析・再分析します。時間別は24時間をすべて表示し、データが揃っている時間だけ実行できます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("対象日", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        selectedDate = LocalDate.of(year, month + 1, day)
                                    },
                                    selectedDate.year,
                                    selectedDate.monthValue - 1,
                                    selectedDate.dayOfMonth
                                ).apply {
                                    datePicker.maxDate = System.currentTimeMillis()
                                }.show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(dateFormat.format(selectedDate))
                        }
                        OutlinedButton(onClick = { refreshToken++ }) {
                            Text("更新")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "1日単位",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "${dateFormat.format(selectedDate)} 00:00–24:00",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "日次ノートをこの1日分の元データから生成または再生成します。データ待ちや既存結果の扱いはAI要約キューの既存仕様を維持します。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (onSubmitDaily(
                                    dailyStart.toInstant().toEpochMilli(),
                                    dailyEnd.toInstant().toEpochMilli()
                                )
                            ) {
                                refreshToken++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("この1日を分析 / 再分析")
                    }
                }
            }
        }

        item {
            Text(
                "1時間単位",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (loading) {
            item {
                Text(
                    "24時間分のデータ状態を確認中…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(rows, key = { it.periodStartMs }) { row ->
                HourlyActionCard(
                    row = row,
                    onRun = {
                        if (onSubmitHourly(row.periodStartMs, row.periodEndMs)) {
                            refreshToken++
                        }
                    }
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("閉じる")
            }
        }
    }
}

@Composable
private fun HourlyActionCard(
    row: HourlyActionRow,
    onRun: () -> Unit
) {
    val activeQueue = row.queueState != null && row.queueState != AiQueueStore.STATE_FAILED
    val canRun = row.periodFinished &&
        !row.pendingTranscription &&
        !activeQueue &&
        row.hasSource

    val statusText = when {
        activeQueue -> queueStateLabel(row.queueState)
        !row.periodFinished && row.periodStarted -> "進行中"
        !row.periodStarted -> "未到達"
        row.pendingTranscription -> "文字起こし待ち"
        row.queueState == AiQueueStore.STATE_FAILED && row.hasSource -> "前回失敗・再試行可能"
        row.queueState == AiQueueStore.STATE_FAILED -> "前回失敗・元データなし"
        row.hasResult && row.hasSource -> "生成済み"
        row.hasResult -> "生成済み・元データなし"
        row.hasSource -> "未生成・実行可能"
        else -> "データなし"
    }

    val actionLabel = when {
        activeQueue -> queueStateLabel(row.queueState)
        !row.periodFinished && row.periodStarted -> "進行中"
        !row.periodStarted -> "未到達"
        row.pendingTranscription -> "待機"
        row.queueState == AiQueueStore.STATE_FAILED && row.hasSource -> "再試行"
        row.hasResult && row.hasSource -> "再実行"
        row.hasResult -> "再実行不可"
        row.hasSource -> "実行"
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
            }
            Button(
                onClick = onRun,
                enabled = canRun
            ) {
                Text(actionLabel)
            }
        }
    }
}

private fun loadHourlyActionRows(
    context: Context,
    date: LocalDate,
    zone: ZoneId
): List<HourlyActionRow> {
    val queueEntries = AiQueueStore.load(context)
        .filter { it.kind == AiAnalysisScheduler.KIND_HOURLY }
        .associateBy { it.periodStartMs }
    val records = SegmentHistoryRepository.load(context)
    val nowMs = System.currentTimeMillis()

    return (0..23).map { hour ->
        val start = date.atTime(hour, 0).atZone(zone)
        val end = start.plusHours(1)
        val startMs = start.toInstant().toEpochMilli()
        val endMs = end.toInstant().toEpochMilli()
        val hasSource = hasTranscriptSource(records, startMs, endMs)
        val pending = hasPendingTranscription(records, startMs, endMs)
        val hasResult = AiAnalysisRepository.hourlyFile(context, startMs).isFile
        val queueEntry = queueEntries[startMs]

        HourlyActionRow(
            hour = hour,
            periodStartMs = startMs,
            periodEndMs = endMs,
            hasSource = hasSource,
            hasResult = hasResult,
            pendingTranscription = pending,
            queueState = queueEntry?.state,
            periodFinished = endMs <= nowMs,
            periodStarted = startMs <= nowMs
        )
    }
}

private fun hasTranscriptSource(
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
        } else if (recordOverlaps(record, periodStartMs, periodEndMs)) {
            return true
        }
    }
    return false
}

private fun hasPendingTranscription(
    records: List<SegmentRecord>,
    periodStartMs: Long,
    periodEndMs: Long
): Boolean {
    for (record in records) {
        if (!recordOverlaps(record, periodStartMs, periodEndMs)) continue
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

private fun recordOverlaps(
    record: SegmentRecord,
    periodStartMs: Long,
    periodEndMs: Long
): Boolean {
    val start = if (record.startedAtMs > 0L) record.startedAtMs else record.sortTimeMs
    if (start <= 0L) return false
    val end = if (record.endedAtMs > start) record.endedAtMs else start + 1L
    return start < periodEndMs && end > periodStartMs
}

private fun queueStateLabel(state: String?): String = when (state) {
    AiQueueStore.STATE_RUNNING -> "処理中"
    AiQueueStore.STATE_WAITING_DATA -> "データ待ち"
    AiQueueStore.STATE_RETRY_WAIT -> "再試行待ち"
    AiQueueStore.STATE_QUEUED -> "実行待ち"
    AiQueueStore.STATE_FAILED -> "失敗"
    else -> "待機中"
}
