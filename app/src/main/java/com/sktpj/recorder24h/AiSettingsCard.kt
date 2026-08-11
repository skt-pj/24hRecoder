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
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiProviderStore
import com.sktpj.recorder24h.ai.Gemma4ModelManager
import com.sktpj.recorder24h.ai.OpenAiKeyStore
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.delay

@Composable
fun AiSettingsCard() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf(OpenAiKeyStore.hasKey(context)) }
    var provider by remember { mutableStateOf(AiProviderStore.getProvider(context)) }
    var gemmaReady by remember { mutableStateOf(Gemma4ModelManager.isReady(context)) }
    var gemmaBytes by remember { mutableStateOf(Gemma4ModelManager.downloadedBytes(context)) }

    LaunchedEffect(provider) {
        while (true) {
            gemmaReady = Gemma4ModelManager.isReady(context)
            gemmaBytes = Gemma4ModelManager.downloadedBytes(context)
            delay(1_500L)
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
                Text(
                    when {
                        gemmaReady -> "Gemma 4 E2Bモデルは準備済みです。"
                        gemmaBytes > 0L -> "Gemma 4 E2Bモデルを取得中です。"
                        else -> "Gemma 4 E2Bモデルを端末へ取得してください。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "モデルサイズ: 2.59 GB / 保存後のAI分析はオフラインで実行できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!gemmaReady && gemmaBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${formatModelGb(gemmaBytes)} / ${formatModelGb(Gemma4ModelManager.EXPECTED_BYTES)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!gemmaReady) {
                    Button(
                        onClick = {
                            Gemma4ModelManager.enqueueDownload(context)
                            AppLogger.event(context, "UI_GEMMA4_MODEL_DOWNLOAD_REQUESTED")
                            Toast.makeText(context, "Gemma 4モデルの取得を開始します", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (gemmaBytes > 0L) "Gemma 4モデルの取得を再開" else "Gemma 4モデルを取得")
                    }
                }
                if (gemmaReady || gemmaBytes > 0L) {
                    OutlinedButton(
                        onClick = {
                            val deleted = Gemma4ModelManager.deleteModel(context)
                            gemmaReady = Gemma4ModelManager.isReady(context)
                            gemmaBytes = Gemma4ModelManager.downloadedBytes(context)
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
