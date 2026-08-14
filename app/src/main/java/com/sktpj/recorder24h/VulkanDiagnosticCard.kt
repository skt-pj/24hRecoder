package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import com.sktpj.recorder24h.transcription.VulkanAutoProbeController
import com.sktpj.recorder24h.transcription.VulkanAutoProbeStore
import com.sktpj.recorder24h.transcription.VulkanProbeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
fun VulkanDiagnosticCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(VulkanAutoProbeStore.read(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { VulkanAutoProbeStore.read(context) }
            delay(1_000L)
        }
    }

    val state = status.optString("state", "IDLE")
    val running = VulkanAutoProbeController.isRunning()
    val currentIndex = status.optInt("currentIndex", -1)
    val totalProfiles = status.optInt("totalProfiles", 2)
    val profile = status.optString("currentProfile", "")
    val phase = status.optString("phase", "-")
    val results = status.optJSONArray("results")
    val audioFile = status.optString("audioFile", "")
    val modelId = status.optString("modelId", "")

    val cpu = benchmarkProfile(results, VulkanProbeStore.PROFILE_CPU)
    val gpu = benchmarkProfile(results, VulkanProbeStore.PROFILE_VULKAN_SAFE)

    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("CPU / Vulkan 速度比較", style = MaterialTheme.typography.titleLarge)
            Text(
                "保存済みの同じ音声・同じWhisperモデル・同じ区間で比較します。通常の文字起こし設定は変更しません。Vulkanを先に測り、その後CPUを測ります。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (modelId.isNotBlank() || audioFile.isNotBlank()) {
                Text(
                    buildString {
                        if (modelId.isNotBlank()) append("モデル: $modelId")
                        if (modelId.isNotBlank() && audioFile.isNotBlank()) append(" / ")
                        if (audioFile.isNotBlank()) append("音声: $audioFile")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (running) {
                val displayIndex = (currentIndex + 1).coerceAtLeast(1)
                Text("比較中: $displayIndex/$totalProfiles  ${VulkanProbeStore.profileLabel(profile)}")
                Text("現在: ${simplePhase(phase)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("操作は不要です。完了後にログも自動同期します。")
            } else if (state == "COMPLETED") {
                Text("比較完了", fontWeight = FontWeight.SemiBold)
            } else if (state == "FAILED") {
                Text("比較処理が停止しました。", color = MaterialTheme.colorScheme.error)
                status.optString("error", "").takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            } else if (state == "RUNNING") {
                Text("前回の比較は途中で中断されています。再実行できます。")
            }

            if (cpu != null || gpu != null) {
                BenchmarkResultBlock(cpu, gpu)
            }

            FilledTonalButton(
                onClick = {
                    if (!VulkanAutoProbeController.start(context)) {
                        Toast.makeText(context, "すでに比較中です", Toast.LENGTH_SHORT).show()
                    }
                    status = VulkanAutoProbeStore.read(context)
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state == "COMPLETED") "CPUとVulkanをもう一度比較" else "CPUとVulkanを自動比較する")
            }

            Text(
                "比較対象はモデル読込、2秒音声、10秒音声です。CPU試験が3分を超えた場合は診断側で終了し、『クラッシュ』ではなく『処理時間超過』として記録します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BenchmarkResultBlock(cpu: BenchmarkProfile?, gpu: BenchmarkProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cpu != null && cpu.outcome != "COMPLETED") {
            Text("CPU: ${outcomeText(cpu)}", color = MaterialTheme.colorScheme.error)
            timeoutPhaseText("CPU", cpu)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (gpu != null && gpu.outcome != "COMPLETED") {
            Text("Vulkan: ${outcomeText(gpu)}", color = MaterialTheme.colorScheme.error)
            timeoutPhaseText("Vulkan", gpu)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        comparisonRow("モデル読込", cpu?.modelLoadMs, gpu?.modelLoadMs, null)
        comparisonRow("2秒音声", cpu?.twoSecondMs, gpu?.twoSecondMs, cpu?.twoSecondAudioMs ?: gpu?.twoSecondAudioMs)
        comparisonRow("10秒音声", cpu?.tenSecondMs, gpu?.tenSecondMs, cpu?.tenSecondAudioMs ?: gpu?.tenSecondAudioMs)
    }
}

@Composable
private fun comparisonRow(label: String, cpuMs: Long?, gpuMs: Long?, audioMs: Long?) {
    if (cpuMs == null && gpuMs == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text("CPU ${formatMs(cpuMs)} / Vulkan ${formatMs(gpuMs)}")
        if (cpuMs != null && cpuMs > 0 && gpuMs != null && gpuMs > 0) {
            Text(
                speedText(cpuMs, gpuMs),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (audioMs != null && audioMs > 0 && (cpuMs != null || gpuMs != null)) {
            Text(
                "RTF: CPU ${formatRtf(cpuMs, audioMs)} / Vulkan ${formatRtf(gpuMs, audioMs)}  （1.00未満なら音声時間より速い）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class BenchmarkProfile(
    val outcome: String,
    val lastPhase: String,
    val timeoutMs: Long?,
    val phaseElapsedMs: Long?,
    val exitReasonName: String?,
    val modelLoadMs: Long?,
    val twoSecondMs: Long?,
    val twoSecondAudioMs: Long?,
    val tenSecondMs: Long?,
    val tenSecondAudioMs: Long?
)

private fun benchmarkProfile(results: JSONArray?, profile: String): BenchmarkProfile? {
    if (results == null) return null
    for (i in 0 until results.length()) {
        val row = results.optJSONObject(i) ?: continue
        if (row.optString("profile", "") != profile) continue
        val outcome = row.optString("outcome", "")
        val probe = row.optJSONObject("probeStatus")
        val probeResults = probe?.optJSONArray("results")
        val model = phaseResult(probeResults, "MODEL_LOAD_ONLY")
        val two = phaseResult(probeResults, "INFERENCE_2000MS")
        val ten = phaseResult(probeResults, "INFERENCE_10000MS")
        return BenchmarkProfile(
            outcome = outcome,
            lastPhase = row.optString("lastPhase", ""),
            timeoutMs = row.longOrNull("timeoutMs"),
            phaseElapsedMs = row.longOrNull("phaseElapsedMs"),
            exitReasonName = row.optJSONObject("exit")?.optString("reasonName", "")?.takeIf { it.isNotBlank() },
            modelLoadMs = model?.longOrNull("modelLoadMs"),
            twoSecondMs = two?.longOrNull("whisperFullMs"),
            twoSecondAudioMs = two?.audioDurationMs(),
            tenSecondMs = ten?.longOrNull("whisperFullMs"),
            tenSecondAudioMs = ten?.audioDurationMs()
        )
    }
    return null
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

private fun JSONObject.audioDurationMs(): Long? {
    val samples = longOrNull("sampleCount") ?: return null
    return if (samples > 0L) samples / 16L else null
}

private fun formatMs(value: Long?): String {
    if (value == null) return "-"
    return if (value < 1_000L) "${value}ms"
    else String.format(Locale.JAPAN, "%.2f秒", value / 1000.0)
}

private fun speedText(cpuMs: Long, gpuMs: Long): String {
    return when {
        cpuMs > gpuMs -> String.format(Locale.JAPAN, "Vulkanが %.2f倍速い", cpuMs / gpuMs.toDouble())
        gpuMs > cpuMs -> String.format(Locale.JAPAN, "CPUが %.2f倍速い", gpuMs / cpuMs.toDouble())
        else -> "CPUとVulkanは同じ速度"
    }
}

private fun formatRtf(processMs: Long?, audioMs: Long): String {
    if (processMs == null || audioMs <= 0L) return "-"
    return String.format(Locale.JAPAN, "%.2f", processMs / audioMs.toDouble())
}

private fun outcomeText(profile: BenchmarkProfile): String = when (profile.outcome) {
    "COMPLETED" -> "完了"
    "PROCESS_EXIT" -> when (profile.exitReasonName) {
        "CRASH_NATIVE" -> "nativeクラッシュ"
        "CRASH" -> "クラッシュ"
        "LOW_MEMORY" -> "メモリ不足で終了"
        "ANR" -> "ANRで終了"
        else -> "試験プロセス終了（クラッシュ未確認）"
    }
    "FAILED" -> "エラー"
    "TIMEOUT" -> {
        val limit = profile.timeoutMs?.let { formatLimit(it) } ?: "制限時間"
        "処理時間超過（$limit、クラッシュではない）"
    }
    "START_TIMEOUT" -> "試験プロセス起動待ちタイムアウト"
    else -> if (profile.outcome.isBlank()) "-" else profile.outcome
}

private fun timeoutPhaseText(label: String, profile: BenchmarkProfile): String? {
    if (profile.outcome != "TIMEOUT") return null
    val elapsed = profile.phaseElapsedMs ?: return null
    return when (profile.lastPhase) {
        "INFERENCE_2000MS" -> "$label: 2秒音声の文字起こしが${formatMs(elapsed)}以上かかっても未完了"
        "INFERENCE_10000MS" -> "$label: 10秒音声の文字起こしが${formatMs(elapsed)}以上かかっても未完了"
        "MODEL_LOAD_ONLY" -> "$label: モデル読込が${formatMs(elapsed)}以上かかっても未完了"
        "PREPARE_AUDIO" -> "$label: 音声準備が${formatMs(elapsed)}以上かかっても未完了"
        else -> null
    }
}

private fun formatLimit(ms: Long): String = when {
    ms % 60_000L == 0L -> "${ms / 60_000L}分"
    ms >= 1_000L -> String.format(Locale.JAPAN, "%.1f秒", ms / 1000.0)
    else -> "${ms}ms"
}

private fun simplePhase(value: String): String = when (value) {
    "WAITING_FOR_PROCESS", "PROFILE_STARTING" -> "試験プロセス起動"
    "PREPARE_AUDIO" -> "同一音声の準備"
    "MODEL_LOAD_ONLY" -> "モデル読込"
    "INFERENCE_2000MS" -> "2秒音声を文字起こし"
    "INFERENCE_10000MS" -> "10秒音声を文字起こし"
    "PROFILE_FINISHED" -> "次のCPU/Vulkan条件へ移動"
    "DONE" -> "完了"
    else -> value
}
