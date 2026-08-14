package com.sktpj.recorder24h

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.sktpj.recorder24h.transcription.VulkanProbeService
import com.sktpj.recorder24h.transcription.VulkanProbeStore
import com.sktpj.recorder24h.util.DriveLogSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun VulkanDiagnosticCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(VulkanProbeStore.read(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            status = withContext(Dispatchers.IO) { VulkanProbeStore.read(context) }
            delay(1_000L)
        }
    }

    val state = status.optString("state", "IDLE")
    val running = state == "RUNNING"
    val profile = status.optString("profile", "")
    val phase = status.optString("phase", "-")
    val results = status.optJSONArray("results")

    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Vulkan切り分け試験", style = MaterialTheme.typography.titleLarge)
            Text(
                "専用の :vulkan_probe プロセスで、同じWhisperモデルをCPU/Vulkan条件別に実行します。録音・UI・5分確定処理とは分離しています。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("状態: $state / ${VulkanProbeStore.profileLabel(profile)}")
            Text("直近段階: $phase / 完了 ${results?.length() ?: 0}/4")
            status.optString("error", "").takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (running) {
                Text(
                    "この表示が同じ段階のまま残る場合、その段階のnative処理で停止・クラッシュした可能性があります。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ProbeButton("CPU対照", VulkanProbeStore.PROFILE_CPU, running) { status = VulkanProbeStore.read(context) }
            ProbeButton("Vulkan標準（回避策なし）", VulkanProbeStore.PROFILE_VULKAN_DEFAULT, running) { status = VulkanProbeStore.read(context) }
            ProbeButton("Vulkan coopmatだけ無効", VulkanProbeStore.PROFILE_VULKAN_COOPMAT_OFF, running) { status = VulkanProbeStore.read(context) }
            ProbeButton("Vulkan graph optimizeだけ無効", VulkanProbeStore.PROFILE_VULKAN_GRAPH_OFF, running) { status = VulkanProbeStore.read(context) }
            ProbeButton("Vulkan 現行回避策（両方無効）", VulkanProbeStore.PROFILE_VULKAN_SAFE, running) { status = VulkanProbeStore.read(context) }

            OutlinedButton(
                onClick = {
                    VulkanProbeStore.fail(context, "USER_MARKED_INTERRUPTED")
                    status = VulkanProbeStore.read(context)
                },
                enabled = running,
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止扱い") }
            OutlinedButton(
                onClick = {
                    DriveLogSync.enqueueNow(context)
                    Toast.makeText(context, "診断ログのDrive同期を登録しました", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ログを同期") }

            Text(
                "各試験は モデル読込のみ → 2秒 → 10秒 → 30秒 の順です。native breadcrumbsとJava側の画面状態・省電力・thermal状態をログへ残します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProbeButton(
    label: String,
    profile: String,
    running: Boolean,
    onStarted: () -> Unit
) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = {
            try {
                context.startService(
                    Intent(context, VulkanProbeService::class.java)
                        .setAction(VulkanProbeService.ACTION_RUN)
                        .putExtra(VulkanProbeService.EXTRA_PROFILE, profile)
                )
                onStarted()
            } catch (error: Exception) {
                Toast.makeText(context, "試験を開始できませんでした: ${error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        },
        enabled = !running,
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}
