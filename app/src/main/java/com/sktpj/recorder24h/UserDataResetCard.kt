package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun UserDataResetCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!running) confirm = false },
            title = { Text("録音・文字起こしデータを一括削除しますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("この操作は元に戻せません。録音を停止してから実行してください。")
                    Text(
                        "削除: 音声ファイル、30秒暫定/確定文字起こし、手動編集、モデル比較結果、記録履歴、文字起こしキュー、AI要約キュー。",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "保持: Whisper/VAD/話者/Gemma等のモデル、OpenAI APIキー、AIノート、本人声プロファイル、各種設定、診断ログ。"
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !running,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        running = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                UserDataResetManager.clearGeneratedData(context)
                            }
                            running = false
                            when (result.status) {
                                UserDataResetManager.Result.SUCCESS -> {
                                    confirm = false
                                    lastMessage = "削除完了: ${result.filesDeleted}件 / ${formatResetBytes(result.bytesDeleted)}"
                                    Toast.makeText(
                                        context,
                                        "記録データを削除しました。モデルとAPIキーは保持しています。",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                UserDataResetManager.Result.RECORDING_ACTIVE -> {
                                    lastMessage = "録音中は削除できません。先にホームで録音を停止してください。"
                                    Toast.makeText(
                                        context,
                                        "先に録音を停止してください",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                else -> {
                                    lastMessage = "削除に失敗しました${result.error?.let { " ($it)" } ?: ""}"
                                    Toast.makeText(context, "記録データの削除に失敗しました", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text(if (running) "削除中…" else "一括削除する")
                }
            },
            dismissButton = {
                TextButton(enabled = !running, onClick = { confirm = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("録音・文字起こしデータの一括削除", style = MaterialTheme.typography.titleLarge)
            Text(
                "テストや再スタート用に、音声・文字起こし・履歴・処理キューをまとめて空にします。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "モデル、OpenAI APIキー、AIノート、本人声プロファイル、設定、診断ログは消しません。",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Button(
                enabled = !running,
                onClick = { confirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("録音・文字起こしデータを一括削除")
            }
            lastMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatResetBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    if (bytes < 1024L * 1024L) return String.format(Locale.JAPAN, "%.1f KB", bytes / 1024.0)
    if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.JAPAN, "%.1f MB", bytes / 1024.0 / 1024.0)
    return String.format(Locale.JAPAN, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
