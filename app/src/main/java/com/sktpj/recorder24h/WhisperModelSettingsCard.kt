package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.LiveTranscriptionSettings
import com.sktpj.recorder24h.transcription.TranscriptionScheduler
import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings
import com.sktpj.recorder24h.transcription.WhisperModelManager
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

@Composable
fun WhisperModelSettingsCard() {
    val context = LocalContext.current
    val specs = remember { WhisperModelManager.comparisonModels().toList() }
    var selectedId by remember { mutableStateOf(WhisperModelManager.selectedModelId(context)) }
    var liveSelectedId by remember { mutableStateOf(LiveTranscriptionSettings.selectedLiveModelId(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            selectedId = WhisperModelManager.selectedModelId(context)
            liveSelectedId = LiveTranscriptionSettings.selectedLiveModelId(context)
            refreshTick++
            delay(1_000L)
        }
    }

    val refreshKey = refreshTick
    val selected = specs.firstOrNull { it.id == selectedId }
        ?: WhisperModelManager.selectedModelSpec(context)
    val liveSelected = specs.firstOrNull { it.id == liveSelectedId }
        ?: WhisperModelManager.modelSpec(liveSelectedId)
    val liveReady = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, liveSelectedId)
    val liveAsrBytes = WhisperModelManager.downloadedBytesForModel(context, liveSelectedId)
    val ready = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, selectedId)
    val asrBytes = WhisperModelManager.downloadedBytesForModel(context, selectedId)
    val vadBytes = WhisperModelManager.vadModelFile(context).let { if (it.isFile) it.length() else 0L }
    val expectedBytes = (selected?.expectedBytes ?: 0L) + WhisperModelManager.VAD_EXPECTED_BYTES
    val downloadedBytes = asrBytes + vadBytes

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TranscriptionBackendSettingsCard()

        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ライブ文字起こしモデル", style = MaterialTheme.typography.titleLarge)
                Text(
                    "リアルタイムの発話ごとの認識に使うWhisperモデルです。5分後の確定・通常文字起こしモデルとは独立して選択します。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(specs, key = { "live-${it.id}" }) { spec ->
                        FilterChip(
                            selected = liveSelectedId == spec.id,
                            onClick = {
                                val before = LiveTranscriptionSettings.selectedLiveModelId(context)
                                LiveTranscriptionSettings.setLiveModelId(context, spec.id)
                                liveSelectedId = spec.id
                                refreshTick++
                                logLiveSelection(context, before, spec.id)
                                Toast.makeText(context, "ライブを${spec.label}に変更しました", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(spec.label) }
                        )
                    }
                }
                liveSelected?.let { spec ->
                    Text(spec.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${spec.description} • ${formatModelBytes(spec.expectedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (liveReady) "ライブモデル: 準備済み" else if (liveAsrBytes > 0L) "ライブモデル: 取得中 / 未完了" else "ライブモデル: 未取得",
                    color = if (liveReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                if (!liveReady) {
                    Button(
                        onClick = {
                            WhisperModelManager.enqueueModelDownload(context, liveSelectedId)
                            Toast.makeText(
                                context,
                                "${liveSelected?.label ?: "ライブモデル"}のダウンロードを開始します",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ライブモデルをダウンロード") }
                }
                Text(
                    "録音中のモデル変更は次の5分セグメント境界からライブ処理へ反映します。モデルを分けても自動フォールバックは行いません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("5分後の確定・通常文字起こしモデル", style = MaterialTheme.typography.titleLarge)
                Text(
                    "5分後の確定文字起こし、自動文字起こしと「この音声を再文字起こし」で使うモデルです。ライブモデル・モデル比較とは別です。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(specs, key = { it.id }) { spec ->
                        FilterChip(
                            selected = selectedId == spec.id,
                            onClick = {
                                val before = WhisperModelManager.selectedModelId(context)
                                if (WhisperModelManager.setSelectedModelId(context, spec.id)) {
                                    selectedId = spec.id
                                    refreshTick++
                                    logSelection(context, before, spec.id)
                                    Toast.makeText(
                                        context,
                                        "通常文字起こしを${spec.label}に変更しました",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            label = { Text(spec.label) }
                        )
                    }
                }

                selected?.let { spec ->
                    Text(spec.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${spec.description} • ${formatModelBytes(spec.expectedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    if (ready) "選択モデル: 準備済み" else if (downloadedBytes > 0L) "選択モデル: 取得中 / 未完了" else "選択モデル: 未取得",
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )

                if (!ready && expectedBytes > 0L && downloadedBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (downloadedBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${formatModelBytes(downloadedBytes)} / ${formatModelBytes(expectedBytes)}（Silero VAD含む）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!ready) {
                    Button(
                        onClick = {
                            WhisperModelManager.enqueueDownload(context)
                            val spec = WhisperModelManager.selectedModelSpec(context)
                            val details = JSONObject()
                            details.put("modelId", WhisperModelManager.selectedModelId(context))
                            details.put("modelLabel", spec?.label ?: JSONObject.NULL)
                            AppLogger.event(context, "UI_WHISPER_SELECTED_MODEL_DOWNLOAD_REQUESTED", details)
                            Toast.makeText(
                                context,
                                "${spec?.label ?: "選択モデル"}のダウンロードを開始します",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("選択モデルをダウンロード")
                    }
                }

                OutlinedButton(
                    onClick = {
                        if (!TranscriptionPipelineSettings.isSelectedPipelineReady(context, selectedId)) {
                            Toast.makeText(context, "先に選択した文字起こし経路を準備してください", Toast.LENGTH_SHORT).show()
                        } else {
                            val count = TranscriptionScheduler.enqueueExisting(context)
                            Toast.makeText(
                                context,
                                "自動処理対象・別モデル音声を${count}件再登録しました",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("自動処理対象・別モデル音声を再登録")
                }

                if (asrBytes > 0L) {
                    OutlinedButton(
                        onClick = {
                            val spec = WhisperModelManager.selectedModelSpec(context)
                            val ok = WhisperModelManager.deleteSelectedModel(context)
                            AppLogger.event(
                                context,
                                if (ok) "UI_WHISPER_SELECTED_MODEL_DELETED"
                                else "UI_WHISPER_SELECTED_MODEL_DELETE_FAILED"
                            )
                            Toast.makeText(
                                context,
                                if (ok) "${spec?.label ?: "選択モデル"}を削除しました"
                                else "選択モデルを削除できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("選択モデルを削除")
                    }
                }

                Text(
                    "変更後に開始する文字起こしから選択モデルを使用します。Android端末内ASR選択時はWhisperモデルをASRには使いません。すでに実推論中の1件は、開始時に確定した経路で完了します。モデル変更だけでは保存済み文字起こしを自動上書きしません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AudioInputSettingsCard()
    }
}

private fun logLiveSelection(context: android.content.Context, before: String, after: String) {
    try {
        val details = JSONObject()
        details.put("previousLiveModelId", before)
        details.put("selectedLiveModelId", after)
        details.put("selectedLiveModelLabel", WhisperModelManager.modelSpec(after)?.label ?: JSONObject.NULL)
        details.put("selectedLiveModelReady", WhisperModelManager.isComparisonReady(context, after))
        details.put("fiveMinuteFinalEnabled", LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context))
        AppLogger.event(context, "UI_WHISPER_LIVE_MODEL_SELECTED", details)
    } catch (_: Exception) {
    }
}

private fun logSelection(context: android.content.Context, before: String, after: String) {
    // kept below; live selection has a separate event so diagnostics can distinguish it.
    try {
        val details = JSONObject()
        details.put("previousModelId", before)
        details.put("selectedModelId", after)
        details.put("selectedModelLabel", WhisperModelManager.modelSpec(after)?.label ?: JSONObject.NULL)
        details.put("selectedModelReady", WhisperModelManager.isComparisonReady(context, after))
        AppLogger.event(context, "UI_WHISPER_NORMAL_MODEL_SELECTED", details)
    } catch (_: Exception) {
    }
}

private fun formatModelBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L * 1024L) {
        String.format(Locale.JAPAN, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    } else {
        String.format(Locale.JAPAN, "%.0f MB", bytes / 1024.0 / 1024.0)
    }
