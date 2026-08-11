package com.sktpj.recorder24h

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

class AiRerunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AiRerunScreen(
                        onCancel = { finish() },
                        onSubmit = { kind, startMs, endMs ->
                            val queued = AiAnalysisScheduler.enqueuePeriod(
                                this,
                                kind,
                                startMs,
                                endMs
                            )
                            Toast.makeText(
                                this,
                                if (queued) "指定期間をAI要約キューに追加しました" else "AI要約を登録できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (queued) finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiRerunScreen(
    onCancel: () -> Unit,
    onSubmit: (String, Long, Long) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { ZonedDateTime.now(zone) }
    val previousHour = remember { now.truncatedTo(ChronoUnit.HOURS).minusHours(1) }
    var kind by remember { mutableStateOf(AiAnalysisScheduler.KIND_HOURLY) }
    var selectedDate by remember { mutableStateOf(previousHour.toLocalDate()) }
    var selectedHour by remember { mutableIntStateOf(previousHour.hour) }

    val start = if (kind == AiAnalysisScheduler.KIND_HOURLY) {
        selectedDate.atTime(selectedHour, 0).atZone(zone)
    } else {
        selectedDate.atStartOfDay(zone)
    }
    val end = if (kind == AiAnalysisScheduler.KIND_HOURLY) start.plusHours(1) else start.plusDays(1)
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN) }
    val rangeText = if (kind == AiAnalysisScheduler.KIND_HOURLY) {
        "${dateFormat.format(start)} ${String.format(Locale.JAPAN, "%02d:00", start.hour)}–${String.format(Locale.JAPAN, "%02d:00", end.hour)}"
    } else {
        "${dateFormat.format(start)} 00:00–24:00"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("AI再分析", style = MaterialTheme.typography.headlineMedium)
        Text(
            "対象期間を明示して再分析します。データがまだ揃っていない場合は処理を続行せず、AI要約キューで「データ待ち」になります。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("要約単位", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == AiAnalysisScheduler.KIND_HOURLY,
                        onClick = {
                            kind = AiAnalysisScheduler.KIND_HOURLY
                            selectedDate = previousHour.toLocalDate()
                            selectedHour = previousHour.hour
                        },
                        label = { Text("1時間") }
                    )
                    FilterChip(
                        selected = kind == AiAnalysisScheduler.KIND_DAILY,
                        onClick = {
                            kind = AiAnalysisScheduler.KIND_DAILY
                            selectedDate = LocalDate.now(zone).minusDays(1)
                        },
                        label = { Text("1日") }
                    )
                }

                Text("対象日", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            LocalContextHolder.current,
                            { _, year, month, day -> selectedDate = LocalDate.of(year, month + 1, day) },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(dateFormat.format(selectedDate))
                }

                if (kind == AiAnalysisScheduler.KIND_HOURLY) {
                    Text("開始時刻", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                LocalContextHolder.current,
                                { _, hour, _ -> selectedHour = hour },
                                selectedHour,
                                0,
                                true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(String.format(Locale.JAPAN, "%02d:00", selectedHour))
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("再分析対象", style = MaterialTheme.typography.titleMedium)
                Text(rangeText, style = MaterialTheme.typography.titleLarge)
            }
        }

        Button(
            onClick = {
                onSubmit(kind, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("この期間を再分析")
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("キャンセル")
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Keeps the Activity context available to platform date/time picker dialogs. */
private object LocalContextHolder {
    lateinit var current: android.content.Context
}
