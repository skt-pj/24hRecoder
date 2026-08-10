package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.OpenAiKeyStore
import com.sktpj.recorder24h.util.AppLogger

@Composable
fun AiSettingsCard() {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }
    var stored by remember { mutableStateOf(OpenAiKeyStore.hasKey(context)) }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("AI分析", style = MaterialTheme.typography.titleLarge)
            Text("GPT-5.6 Luna / 1時間ごとの要約 / 1日ごとの統合分析")
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
                        AiAnalysisScheduler.cancel(context)
                        stored = false
                        apiKey = ""
                        AppLogger.event(context, "UI_OPENAI_API_KEY_CLEARED")
                        Toast.makeText(context, "APIキーを削除し、AI分析を停止しました", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("APIキーを削除")
                }
            }
        }
    }
}
