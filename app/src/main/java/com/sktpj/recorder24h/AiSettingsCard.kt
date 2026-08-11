package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiProviderStore
import com.sktpj.recorder24h.ai.Gemma4ModelManager
import com.sktpj.recorder24h.ai.OpenAiKeyStore
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsCard() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf(OpenAiKeyStore.hasKey(context)) }
    var provider by remember { mutableStateOf(AiProviderStore.getProvider(context)) }
    var gemmaReady by remember { mutableStateOf(Gemma4ModelManager.isReady(context)) }
    var gemmaBytes by remember { mutableStateOf(Gemma4ModelManager.downloadedBytes(context)) }
    var gemmaWorkState by remember { mutableStateOf<WorkInfo.State?>(null) }
    var gemmaPhase by remember { mutableStateOf<String?>(null) }
    var gemmaProgressMessage by remember { mutableStateOf<String?>(null) }
    var gemmaError by remember { mutableStateOf<String?>(null) }
    var downloadRequested by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        while (true) {
            val readyNow = Gemma4ModelManager.isReady(context)
            val fileBytes = Gemma4ModelManager.downloadedBytes(context)
            val workInfo = try {
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(Gemma4ModelManager.DOWNLOAD_WORK_NAME)
                        .get()
                        .lastOrNull()
                }
            } catch (_: Exception) {
                null
            }

            gemmaReady = readyNow
            gemmaWorkState = workInfo?.state
            gemmaPhase = workInfo?.progress?.getString(Gemma4ModelManager.PROGRESS_PHASE)
            gemmaProgressMessage = workInfo?.progress?.getString(Gemma4ModelManager.PROGRESS_MESSAGE)
            gemmaError = workInfo?.outputData?.getString(Gemma4ModelManager.OUTPUT_ERROR)
            val workerBytes = workInfo?.progress?.getLong(
                Gemma4ModelManager.PROGRESS_DOWNLOADED_BYTES,
                -1L
            ) ?: -1L
            gemmaBytes = maxOf(fileBytes, workerBytes.coerceAtLeast(0L))

            downloadRequested = when {
                readyNow -> false
                workInfo?.state == WorkInfo.State.ENQUEUED -> true
                workInfo?.state == WorkInfo.State.BLOCKED -> true
                workInfo?.state == WorkInfo.State.RUNNING -> true
                else -> downloadRequested && workInfo == null
            }
            delay(500L)
        }
    }

    fun selectProvider(selected: String) {
        if (provider == selected) return
        provider = selected
        AiProviderStore.setProvider(context, selected)
        AiAnalysisScheduler.ensureScheduled(context)
        if (AiProviderStore.isConfigured(context)) {
            AiAnalysisScheduler.enqueueNow(context)
        }
        AppLogger.event(
            context,
            if (selected == AiProviderStore.PROVIDER_GEMMA4_LOCAL)
                "UI_AI_PROVIDER_GEMMA4_SELECTED"
            else "UI_AI_PROVIDER_OPENAI_SELECTED"
        )
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("AI分析", style = MaterialTheme.typography.titleLarge)
            Text("1時間ごとの要約 / 1日ごとの統合分析")
            Text("使用するLLM", style = MaterialTheme.typography.titleMedium)

            ProviderRow(
                selected = provider == AiProviderStore.PROVIDER_OPENAI_LUNA,
                title = "OpenAI GPT-5.6 Luna",
                detail = "文字起こしテキストをOpenAI APIへ送信",
                onClick = { selectProvider(AiProviderStore.PROVIDER_OPENAI_LUNA) }
            )
            ProviderRow(
                selected = provider == AiProviderStore.PROVIDER_GEMMA4_LOCAL,
                title = "ローカル Gemma 4 E2B",
                detail = "AI分析も端末内で実行。APIキー不要",
                onClick = { selectProvider(AiProviderStore.PROVIDER_GEMMA4_LOCAL) }
            )

            if (provider == AiProviderStore.PROVIDER_OPENAI_LUNA) {
                Text(
                    if (stored) "OpenAI APIキーは端末内に暗号化して保存済みです。"
                    else "OpenAI APIキーを入力するとAI分析を開始します。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenAI APIキー") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        try {
                            OpenAiKeyStore.save(context, apiKey)
                            stored = true
                            apiKey = ""
                            AiAnalysisScheduler.ensureScheduled(context)
                            AiAnalysisScheduler.enqueueNow(context)
                            AppLogger.event(context, "UI_OPENAI_API_KEY_SAVED")
                            Toast.makeText(context, "APIキーを保存し、AI分析を有効にしました", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, "APIキーを保存できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (stored) "APIキーを更新" else "APIキーを保存")
                }
                if (stored) {
                    OutlinedButton(
                        onClick = {
                            OpenAiKeyStore.clear(context)
                            AiAnalysisScheduler.ensureScheduled(context)
                            stored = false
                            apiKey = ""
                            AppLogger.event(context, "UI_OPENAI_API_KEY_CLEARED")
                            Toast.makeText(context, "APIキーを削除しました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("APIキーを削除")
                    }
                }
            } else {
                val progress = (gemmaBytes.toFloat() /
                    Gemma4ModelManager.EXPECTED_BYTES.toFloat()).coerceIn(0f, 1f)
                val percent = (progress * 100f).toInt()
                val isDownloadActive = downloadRequested ||
                    gemmaWorkState == WorkInfo.State.ENQUEUED ||
                    gemmaWorkState == WorkInfo.State.BLOCKED ||
                    gemmaWorkState == WorkInfo.State.RUNNING

                Text(
                    when {
                        gemmaReady -> "Gemma 4 E2Bモデルは準備済みです。"
                        gemmaWorkState == WorkInfo.State.RUNNING &&
                            gemmaPhase == Gemma4ModelManager.PHASE_VERIFYING ->
                            "ダウンロード完了。モデルファイルを検証中です。"
                        gemmaWorkState == WorkInfo.State.RUNNING &&
                            gemmaPhase == Gemma4ModelManager.PHASE_STARTING ->
                            "モデル取得を開始しています。配布サーバーへ接続中です。"
                        gemmaWorkState == WorkInfo.State.RUNNING ->
                            "モデル取得中: ${percent}%"
                        gemmaWorkState == WorkInfo.State.ENQUEUED &&
                            gemmaPhase == Gemma4ModelManager.PHASE_RETRYING ->
                            "一時的な失敗が発生しました。自動再試行を待っています。"
                        gemmaWorkState == WorkInfo.State.ENQUEUED ->
                            "モデル取得の開始待ちです。ネットワーク接続を確認しています。"
                        gemmaWorkState == WorkInfo.State.BLOCKED ->
                            "モデル取得の開始条件を待っています。"
                        gemmaWorkState == WorkInfo.State.FAILED ->
                            "モデル取得に失敗しました。"
                        gemmaWorkState == WorkInfo.State.CANCELLED ->
                            "モデル取得はキャンセルされました。続きから再開できます。"
                        isDownloadActive ->
                            "モデル取得を開始しています。"
                        gemmaBytes > 0L ->
                            "取得途中のモデルがあります。続きから再開できます。"
                        else -> "Gemma 4 E2Bモデルを端末へ取得してください。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "モデルサイズ: 2.59 GB / 保存後のAI分析はオフラインで実行できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!gemmaReady && (isDownloadActive || gemmaBytes > 0L)) {
                    if (gemmaBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${percent}%  ${formatModelGb(gemmaBytes)} / ${formatModelGb(Gemma4ModelManager.EXPECTED_BYTES)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "接続準備中…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (gemmaWorkState == WorkInfo.State.FAILED && !gemmaError.isNullOrBlank()) {
                    Text(
                        gemmaError!!,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (gemmaPhase == Gemma4ModelManager.PHASE_RETRYING &&
                    !gemmaProgressMessage.isNullOrBlank()) {
                    Text(
                        gemmaProgressMessage!!,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (!gemmaReady) {
                    Button(
                        onClick = {
                            downloadRequested = true
                            gemmaWorkState = null
                            gemmaPhase = Gemma4ModelManager.PHASE_STARTING
                            gemmaProgressMessage = null
                            gemmaError = null
                            Gemma4ModelManager.enqueueDownload(context)
                            AppLogger.event(context, "UI_GEMMA4_MODEL_DOWNLOAD_REQUESTED")
                            Toast.makeText(context, "Gemma 4モデルの取得を開始します", Toast.LENGTH_SHORT).show()
                        },
                        enabled = !isDownloadActive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                isDownloadActive -> "Gemma 4モデルを取得中…"
                                gemmaBytes > 0L -> "Gemma 4モデルの取得を再開"
                                else -> "Gemma 4モデルを取得"
                            }
                        )
                    }
                }

                if (!gemmaReady && isDownloadActive) {
                    OutlinedButton(
                        onClick = {
                            Gemma4ModelManager.cancelDownload(context)
                            downloadRequested = false
                            gemmaWorkState = WorkInfo.State.CANCELLED
                            AppLogger.event(context, "UI_GEMMA4_MODEL_DOWNLOAD_CANCELLED")
                            Toast.makeText(context, "モデル取得をキャンセルしました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取得をキャンセル")
                    }
                }

                if ((gemmaReady || gemmaBytes > 0L) && !isDownloadActive) {
                    OutlinedButton(
                        onClick = {
                            val deleted = Gemma4ModelManager.deleteModel(context)
                            gemmaReady = Gemma4ModelManager.isReady(context)
                            gemmaBytes = Gemma4ModelManager.downloadedBytes(context)
                            gemmaWorkState = null
                            gemmaPhase = null
                            gemmaProgressMessage = null
                            gemmaError = null
                            AiAnalysisScheduler.ensureScheduled(context)
                            AppLogger.event(
                                context,
                                if (deleted) "UI_GEMMA4_MODEL_DELETED" else "UI_GEMMA4_MODEL_DELETE_FAILED"
                            )
                            Toast.makeText(
                                context,
                                if (deleted) "Gemma 4モデルを削除しました" else "Gemma 4モデルを削除できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gemma 4モデルを削除")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatModelGb(bytes: Long): String =
    String.format(java.util.Locale.JAPAN, "%.2f GB", bytes / 1_000_000_000.0)
