package com.sktpj.recorder24h

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.FullStreamingStateStore
import com.sktpj.recorder24h.transcription.StreamingVadSettings
import com.sktpj.recorder24h.transcription.StreamingVadStore
import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

@Composable
fun DiagnosticsScreen(refreshToken: Int = 0) {
    val context = LocalContext.current
    var vad by remember { mutableStateOf(StreamingVadStore.readCurrentDiagnostics(context)) }
    var live by remember { mutableStateOf(FullStreamingStateStore.readLiveState(context)) }

    LaunchedEffect(refreshToken) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                StreamingVadStore.readCurrentDiagnostics(context) to FullStreamingStateStore.readLiveState(context)
            }
            vad = snapshot.first
            live = snapshot.second
            delay(1_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VadDiagnosticsCard(vad, live.state, live.backend, live.queueDepth, live.error) }
        item { RuntimeDiagnosticsCard() }
        item { VulkanDiagnosticCard() }
        item { Model30sBenchmarkCard() }
    }
}

@Composable
fun StreamingVadPresetSelector() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(StreamingVadSettings.snapshot(context)) }
    val presets = listOf(
        StreamingVadSettings.PRESET_OFFICIAL,
        StreamingVadSettings.PRESET_CONVERSATION,
        StreamingVadSettings.PRESET_SENSITIVE
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VAD感度", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets.size) { index ->
                val id = presets[index]
                FilterChip(
                    selected = current.presetId == id,
                    onClick = {
                        if (StreamingVadSettings.setPreset(context, id)) {
                            current = StreamingVadSettings.snapshot(context)
                            try {
                                AppLogger.event(
                                    context,
                                    "UI_STREAMING_VAD_PRESET_CHANGED",
                                    JSONObject().put("vad", current.toJson())
                                )
                            } catch (_: Exception) {}
                        }
                    },
                    label = { Text(StreamingVadSettings.label(id)) }
                )
            }
        }
        Text(
            String.format(
                Locale.JAPAN,
                "開始 %.2f / 終了 %.2f / 最短発話 %dms / 無音 %dms / pad %dms",
                current.startThreshold,
                current.endThreshold,
                current.minSpeechMs,
                current.minSilenceMs,
                current.speechPadMs
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VadDiagnosticsCard(
    vad: JSONObject,
    liveState: String,
    liveBackend: String?,
    queueDepth: Int,
    liveError: String?
) {
    val context = LocalContext.current
    val settings = StreamingVadSettings.snapshot(context)
    val reason = vad.optString("vadHealthReason", "NO_DATA")
    val healthText = when (reason) {
        "SPEECH_DETECTED" -> "発話検出あり"
        "PROBABILITY_TOO_LOW" -> "Silero確率が開始閾値未満"
        "NO_PROBABILITY_FRAMES" -> "Silero確率フレームなし"
        "SPEECH_NOT_CONFIRMED" -> "閾値超えあり・発話未確定"
        "OK" -> "正常"
        else -> "データ待ち"
    }
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VAD / リアルタイム", style = MaterialTheme.typography.titleLarge)
            Text("${settings.label()}  •  $healthText", fontWeight = FontWeight.SemiBold)
            DiagnosticRow("更新", formatAge(vad.optLong("updatedAtMs", 0L)))
            DiagnosticRow("入力", "${vad.optInt("sampleRate", 16_000)}Hz / mono / ${vad.optInt("windowSamples", 512)} samples")
            DiagnosticRow("PCM", "RMS ${formatDouble(vad, "pcmRms")} / peak ${formatDouble(vad, "pcmPeak")}")
            DiagnosticRow("Silero確率", "avg ${formatDouble(vad, "probabilityAvg")} / p95 ${formatDouble(vad, "probabilityP95")} / max ${formatDouble(vad, "probabilityMax")}")
            DiagnosticRow("frames", "${vad.optLong("frameCount", 0L)} / start超え ${vad.optLong("aboveStartFrames", 0L)} / end超え ${vad.optLong("aboveEndFrames", 0L)}")
            DiagnosticRow("発話", "${vad.optLong("speechMs", 0L)}ms / ${vad.optInt("rangeCount", 0)}区間")
            DiagnosticRow("ASR", "$liveState / ${liveBackend ?: "-"} / 待ち $queueDepth")
            if (!liveError.isNullOrBlank()) {
                Text(liveError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RuntimeDiagnosticsCard() {
    val context = LocalContext.current
    val pipeline = TranscriptionPipelineSettings.snapshot(context)
    val vulkan = remember {
        TranscriptionPipelineSettings.isAsrRuntimeAvailable(context, TranscriptionPipelineSettings.ASR_WHISPER_VULKAN)
    }
    val androidAsr = remember {
        TranscriptionPipelineSettings.isAsrRuntimeAvailable(context, TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE)
    }
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("実行環境", style = MaterialTheme.typography.titleLarge)
            DiagnosticRow("端末", "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
            DiagnosticRow("方式", TranscriptionPipelineSettings.modeLabel(pipeline.executionMode))
            DiagnosticRow("ASR", TranscriptionPipelineSettings.asrLabel(pipeline.asrBackend))
            DiagnosticRow("VAD", TranscriptionPipelineSettings.vadLabel(pipeline.vadBackend))
            DiagnosticRow("Vulkan", if (vulkan) "利用可能" else "利用不可")
            DiagnosticRow("Android ASR", if (androidAsr) "利用可能" else "利用不可")
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.30f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.70f))
    }
}

private fun formatDouble(json: JSONObject, key: String): String {
    if (!json.has(key) || json.isNull(key)) return "-"
    return String.format(Locale.JAPAN, "%.3f", json.optDouble(key, 0.0))
}

private fun formatAge(timestampMs: Long): String {
    if (timestampMs <= 0L) return "-"
    val seconds = ((System.currentTimeMillis() - timestampMs).coerceAtLeast(0L) / 1000L)
    return if (seconds < 60L) "${seconds}秒前" else "${seconds / 60L}分前"
}
