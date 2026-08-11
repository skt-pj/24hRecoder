package com.sktpj.recorder24h

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisRepository
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiProcessingDurationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class AiQueueTargetActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_START_MS = "periodStartMs"
        private const val EXTRA_END_MS = "periodEndMs"

        fun createIntent(
            context: Context,
            kind: String,
            periodStartMs: Long,
            periodEndMs: Long
        ): Intent = Intent(context, AiQueueTargetActivity::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_START_MS, periodStartMs)
            .putExtra(EXTRA_END_MS, periodEndMs)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val periodStartMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val periodEndMs = intent.getLongExtra(EXTRA_END_MS, 0L)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("AIノート") },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                                    }
                                }
                            )
                        }
                    ) { padding ->
                        AiQueueTargetContent(
                            kind = kind,
                            periodStartMs = periodStartMs,
                            periodEndMs = periodEndMs,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiQueueTargetContent(
    kind: String,
    periodStartMs: Long,
    periodEndMs: Long,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val date = remember(periodStartMs) {
        Instant.ofEpochMilli(periodStartMs).atZone(zone).toLocalDate()
    }
    var hourlySummaries by remember(date) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var dailyHasResult by remember(date) { mutableStateOf(false) }

    LaunchedEffect(date) {
        val loaded = withContext(Dispatchers.IO) {
            loadTargetDay(context.applicationContext, date, zone)
        }
        hourlySummaries = loaded.first
        dailyHasResult = loaded.second
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("キュー対象", style = MaterialTheme.typography.titleLarge)
                Text(
                    aiTargetPeriodLabel(kind, periodStartMs, periodEndMs),
                    style = MaterialTheme.typography.headlineMedium
                )
                val duration = AiProcessingDurationStore.get(
                    context,
                    kind,
                    periodStartMs,
                    periodEndMs
                )
                if (duration > 0L) {
                    Text(
                        "前回の処理時間: ${formatAiProcessingDuration(duration)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (kind == AiAnalysisScheduler.KIND_HOURLY || kind == AiAnalysisScheduler.KIND_DAILY) {
            item {
                AiNotebookDayAnalysisActions(
                    date = date,
                    hourlySummaries = hourlySummaries,
                    dailyHasResult = dailyHasResult,
                    highlightPeriodStartMs = if (kind == AiAnalysisScheduler.KIND_HOURLY) {
                        periodStartMs
                    } else {
                        null
                    }
                )
            }
        } else {
            item {
                Text(
                    "この集約期間のAI処理対象です。完了後はAIノートの週・月・年表示に保存されます。",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun loadTargetDay(
    context: Context,
    date: java.time.LocalDate,
    zone: ZoneId
): Pair<Map<Long, String>, Boolean> {
    val summaries = linkedMapOf<Long, String>()
    for (hour in 0..23) {
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val file = AiAnalysisRepository.hourlyFile(context, start)
        if (!file.isFile) continue
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val summary = root.optJSONObject("analysis")?.optString("summary", "")?.trim().orEmpty()
            if (summary.isNotEmpty()) summaries[start] = summary
        } catch (_: Exception) {
        }
    }
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    return summaries to AiAnalysisRepository.dailyFile(context, dayStart).isFile
}

private fun aiTargetPeriodLabel(kind: String, startMs: Long, endMs: Long): String {
    val day = SimpleDateFormat("yyyy年M月d日", Locale.JAPAN)
    val time = SimpleDateFormat("HH:mm", Locale.JAPAN)
    return when (kind) {
        AiAnalysisScheduler.KIND_HOURLY ->
            "${day.format(Date(startMs))} ${time.format(Date(startMs))}–${time.format(Date(endMs))}"
        AiAnalysisScheduler.KIND_DAILY -> "${day.format(Date(startMs))} 00:00–24:00"
        "monthly" -> SimpleDateFormat("yyyy年M月", Locale.JAPAN).format(Date(startMs))
        "yearly" -> SimpleDateFormat("yyyy年", Locale.JAPAN).format(Date(startMs))
        else -> "${day.format(Date(startMs))}–${day.format(Date(endMs))}"
    }
}
