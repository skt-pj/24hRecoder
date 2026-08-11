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
import com.sktpj.recorder24h.ai.AiDailySourceCleanup
import com.sktpj.recorder24h.ai.AiQueueStore
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
                        onSubmit = { startMs, endMs ->
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
    onSubmit: (Long, Long) -> Boolean
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
                "対象日を選ぶと、その日の24時間を1時間単位ですべて表示します。データが揃っている時間だけ実行でき、生成済みの時間は再実行できます。",
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
                "時間別",
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
                        if (onSubmit(row.periodStartMs, row.periodEndMs)) {
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
    val nowMs = System.currentTimeMillis()

    return (0..23).map { hour ->
        val start = date.atTime(hour, 0).atZone(zone)
        val end = start.plusHours(1)
        val startMs = start.toInstant().toEpochMilli()
        val endMs = end.toInstant().toEpochMilli()
        val source = AiAnalysisRepository.buildSource(context, startMs, endMs)
        val pending = AiDailySourceCleanup.hasPendingTranscription(context, startMs, endMs)
        val hasResult = AiAnalysisRepository.hourlyFile(context, startMs).isFile
        val queueEntry = queueEntries[startMs]

        HourlyActionRow(
            hour = hour,
            periodStartMs = startMs,
            periodEndMs = endMs,
            hasSource = !source.isEmpty,
            hasResult = hasResult,
            pendingTranscription = pending,
            queueState = queueEntry?.state,
            periodFinished = endMs <= nowMs,
            periodStarted = startMs <= nowMs
        )
    }
}

private fun queueStateLabel(state: String?): String = when (state) {
    AiQueueStore.STATE_RUNNING -> "処理中"
    AiQueueStore.STATE_WAITING_DATA -> "データ待ち"
    AiQueueStore.STATE_RETRY_WAIT -> "再試行待ち"
    AiQueueStore.STATE_QUEUED -> "実行待ち"
    AiQueueStore.STATE_FAILED -> "失敗"
    else -> "待機中"
}
