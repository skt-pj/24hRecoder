package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import com.sktpj.recorder24h.transcription.Model30sBenchmarkController
import com.sktpj.recorder24h.transcription.Model30sBenchmarkStore
import com.sktpj.recorder24h.transcription.VulkanAutoProbeController
import com.sktpj.recorder24h.transcription.WhisperModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
fun Model30sBenchmarkCard() {
    val context = LocalContext.current
    val models = remember { WhisperModelManager.comparisonModels().toList() }
    var status by remember { mutableStateOf(Model30sBenchmarkStore.read(context)) }
    var backendSupported by remember { mutableStateOf(Model30sBenchmarkController.isWhisperBackendSupported(context)) }
    var backendLabel by remember { mutableStateOf(Model30sBenchmarkController.currentBackendLabel(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                Triple(
                    Model30sBenchmarkStore.read(context),
                    Model30sBenchmarkController.isWhisperBackendSupported(context),
                    Model30sBenchmarkController.currentBackendLabel(context)
                )
            }
            status = snapshot.first
            backendSupported = snapshot.second
            backendLabel = snapshot.third
            delay(750L)
        }
    }

    val state = status.optString("state", "IDLE")
    val running = Model30sBenchmarkController.isRunning()
    val otherBenchmarkRunning = VulkanAutoProbeController.isRunning()
    val currentModelId = status.optString("currentModelId", "")
    val results = status.optJSONArray("results") ?: JSONArray()
    val audioFile = status.optString("audioFile", "")

    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("30秒・各モデル速度比較", style = MaterialTheme.typography.titleLarge)
            Text(
                "同じ保存済み音声の先頭30秒を、現在選択中のWhisper実行方式で各モデルへ順番に通します。VADは挟まず、モデルの処理速度だけを比較します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "実行方式: $backendLabel" + if (audioFile.isNotBlank()) " / 音声: $audioFile" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state == "FAILED") {
                Text(
                    benchmarkErrorText(status.optString("error", "")),
                    color = MaterialTheme.colorScheme.error
                )
            } else if (running) {
                Text("測定中", fontWeight = FontWeight.SemiBold)
            } else if (state == "COMPLETED") {
                Text("測定完了", fontWeight = FontWeight.SemiBold)
            }

            models.forEachIndexed { index, spec ->
                if (index > 0) HorizontalDivider()
                val result = findModelResult(results, spec.id)
                val ready = WhisperModelManager.isModelReady(context, spec.id)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        spec.label,
                        modifier = Modifier.weight(0.46f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        benchmarkResultText(
                            result = result,
                            ready = ready,
                            running = running && currentModelId == spec.id
                        ),
                        modifier = Modifier.weight(0.54f)
                    )
                }
            }

            FilledTonalButton(
                onClick = {
                    if (!Model30sBenchmarkController.start(context)) {
                        Toast.makeText(context, "別の速度比較が実行中です", Toast.LENGTH_SHORT).show()
                    }
                    status = Model30sBenchmarkStore.read(context)
                },
                enabled = !running && !otherBenchmarkRunning && backendSupported,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state == "COMPLETED") "30秒で各モデルをもう一度測定" else "30秒で各モデルを測定")
            }

            Text(
                if (backendSupported) {
                    "30秒以上の保存済み音声が必要です。未取得モデルは測定せず「未取得」と表示します。処理時間はモデル読込 + whisper_full、RTFはその合計を30秒で割った値です。"
                } else {
                    "この比較はWhisper CPUまたはWhisper Vulkan選択時に実行できます。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun findModelResult(results: JSONArray, modelId: String): JSONObject? {
    for (i in 0 until results.length()) {
        val row = results.optJSONObject(i) ?: continue
        if (row.optString("modelId", "") == modelId) return row
    }
    return null
}

private fun benchmarkResultText(result: JSONObject?, ready: Boolean, running: Boolean): String {
    if (running) return "測定中"
    if (result == null) return if (ready) "未測定" else "未取得"

    return when (result.optString("outcome", "")) {
        "MODEL_MISSING" -> "未取得"
        "COMPLETED" -> {
            val probe = result.optJSONObject("probeStatus")
            val native = phaseResult(probe?.optJSONArray("results"), "INFERENCE_30000MS")
            if (native == null) {
                "結果なし"
            } else {
                val loadMs = native.longOrNull("modelLoadMs")
                val whisperMs = native.longOrNull("whisperFullMs")
                val totalMs = if (loadMs != null && whisperMs != null) loadMs + whisperMs else null
                val audioMs = native.longOrNull("sampleCount")?.let { it / 16L }
                    ?: Model30sBenchmarkController.BENCHMARK_DURATION_MS
                buildString {
                    append(formatBenchmarkMs(totalMs))
                    if (loadMs != null || whisperMs != null) {
                        append("\n読込 ")
                        append(formatBenchmarkMs(loadMs))
                        append(" / 推論 ")
                        append(formatBenchmarkMs(whisperMs))
                    }
                    if (totalMs != null && audioMs > 0L) {
                        append(" / RTF ")
                        append(String.format(Locale.JAPAN, "%.2f", totalMs / audioMs.toDouble()))
                    }
                }
            }
        }
        "TIMEOUT" -> "処理時間超過"
        "START_TIMEOUT" -> "試験プロセス起動失敗"
        "PROCESS_EXIT" -> "試験プロセス終了"
        "FAILED" -> result.optString("error", "エラー").ifBlank { "エラー" }
        else -> result.optString("outcome", "結果なし").ifBlank { "結果なし" }
    }
}

private fun phaseResult(results: JSONArray?, phase: String): JSONObject? {
    if (results == null) return null
    for (i in 0 until results.length()) {
        val row = results.optJSONObject(i) ?: continue
        if (row.optString("phase", "") == phase) return row.optJSONObject("result")
    }
    return null
}

private fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

private fun formatBenchmarkMs(value: Long?): String {
    if (value == null) return "-"
    if (value < 1_000L) return "${value}ms"
    return String.format(Locale.JAPAN, "%.2f秒", value / 1000.0)
}

private fun benchmarkErrorText(raw: String): String = when {
    raw.contains("RETAINED_30S_AUDIO_MISSING") -> "30秒以上の保存済み音声がありません。"
    raw.contains("WHISPER_CPU_OR_VULKAN_BACKEND_REQUIRED") -> "Whisper CPUまたはWhisper Vulkanを選択してください。"
    else -> raw.ifBlank { "速度比較を実行できませんでした。" }
}
