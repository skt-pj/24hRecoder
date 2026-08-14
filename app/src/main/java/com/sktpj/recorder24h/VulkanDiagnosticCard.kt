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
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.VulkanAutoProbeController
import com.sktpj.recorder24h.transcription.VulkanAutoProbeStore
import com.sktpj.recorder24h.transcription.VulkanProbeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val totalProfiles = status.optInt("totalProfiles", 5)
    val profile = status.optString("currentProfile", "")
    val phase = status.optString("phase", "-")
    val results = status.optJSONArray("results")

    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Vulkan自動診断", style = MaterialTheme.typography.titleLarge)
            Text(
                "下のボタンを1回押すだけです。CPU対照と4種類のVulkan条件を順番に試します。途中でVulkanが落ちても自動で次へ進み、最後にログもDriveへ送ります。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (running) {
                val displayIndex = (currentIndex + 1).coerceAtLeast(1)
                Text("診断中: $displayIndex/$totalProfiles  ${VulkanProbeStore.profileLabel(profile)}")
                Text("現在: ${simplePhase(phase)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "このまま待つだけで大丈夫です。ほかの診断ボタンを押す必要はありません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state == "COMPLETED") {
                Text("診断完了。ログ送信も自動で実行しました。これ以上の操作は不要です。")
            } else if (state == "FAILED") {
                Text("自動診断が途中で停止しました。もう一度ボタンを押すと最初からやり直します。")
                status.optString("error", "").takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            } else if (state == "RUNNING") {
                Text("前回の自動診断は途中で中断されています。もう一度押すと最初からやり直します。")
            }

            if (results != null && results.length() > 0) {
                for (i in 0 until results.length()) {
                    val row = results.optJSONObject(i) ?: continue
                    val label = row.optString("label", row.optString("profile", ""))
                    Text("${i + 1}. $label: ${simpleOutcome(row.optString("outcome", ""))}")
                }
            }

            FilledTonalButton(
                onClick = {
                    if (!VulkanAutoProbeController.start(context)) {
                        Toast.makeText(context, "すでに診断中です", Toast.LENGTH_SHORT).show()
                    }
                    status = VulkanAutoProbeStore.read(context)
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state == "COMPLETED") "もう一度まとめて診断する" else "全部まとめて自動診断する")
            }

            Text(
                "内部では各条件ごとに専用プロセスを作り直し、モデル読込 → 2秒 → 10秒 → 30秒を確認します。クラッシュ位置、終了理由、native直前の段階も自動記録します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun simpleOutcome(value: String): String = when (value) {
    "COMPLETED" -> "完了"
    "PROCESS_EXIT" -> "クラッシュ/強制終了"
    "FAILED" -> "エラー"
    "TIMEOUT" -> "タイムアウト"
    "START_TIMEOUT" -> "起動失敗"
    else -> if (value.isBlank()) "-" else value
}

private fun simplePhase(value: String): String = when (value) {
    "WAITING_FOR_PROCESS", "PROFILE_STARTING" -> "試験プロセス起動"
    "PREPARE_AUDIO" -> "音声準備"
    "MODEL_LOAD_ONLY" -> "モデル読込"
    "INFERENCE_2000MS" -> "2秒音声"
    "INFERENCE_10000MS" -> "10秒音声"
    "INFERENCE_30000MS" -> "30秒音声"
    "PROFILE_FINISHED" -> "次の条件へ移動"
    "DONE" -> "完了"
    else -> value
}
