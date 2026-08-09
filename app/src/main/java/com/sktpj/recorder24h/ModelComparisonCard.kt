package com.sktpj.recorder24h

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.transcription.ModelComparisonRepository
import com.sktpj.recorder24h.transcription.ModelComparisonScheduler
import com.sktpj.recorder24h.transcription.WhisperModelManager
import com.sktpj.recorder24h.ui.SegmentRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

@Composable
fun ModelComparisonCard(record: SegmentRecord) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val specs = remember { WhisperModelManager.comparisonModels().toList() }
    var pollTick by remember(record.segmentId) { mutableIntStateOf(0) }
    var comparison by remember(record.segmentId) {
        mutableStateOf(ModelComparisonRepository.read(context, record.segmentId))
    }
    var selectedIds by remember(record.segmentId) {
        mutableStateOf(
            specs.filter { WhisperModelManager.isComparisonReady(context, it.id) }
                .map { it.id }
                .toSet()
        )
    }

    LaunchedEffect(record.segmentId) {
        while (true) {
            comparison = withContext(Dispatchers.IO) {
                ModelComparisonRepository.read(context, record.segmentId)
            }
            pollTick++
            delay(1_000L)
        }
    }

    // Reading pollTick makes model download progress/readiness refresh even while no comparison
    // result exists yet.
    val refreshKey = pollTick
    val audioFile = record.audioPath?.let(::File)
    val selectedSpecs = specs.filter { selectedIds.contains(it.id) }
    val missingSelected = selectedSpecs.filterNot {
        refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, it.id)
    }
    val running = comparison?.optString("status") == "RUNNING"

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("モデル比較", style = MaterialTheme.typography.titleLarge)
            Text(
                "この記録の元音声だけを同じ前処理・同じSilero VAD条件で比較します。内容と発話の取りこぼしを最優先で確認し、速度は補助指標として表示します。比較結果は通常の文字起こしを上書きしません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            specs.forEach { spec ->
                val ready = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, spec.id)
                val bytes = WhisperModelManager.downloadedBytesForModel(context, spec.id)
                val selected = selectedIds.contains(spec.id)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(spec.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${spec.description} • ${formatModelBytes(spec.expectedBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedIds = if (selected) selectedIds - spec.id else selectedIds + spec.id
                                },
                                label = { Text(if (selected) "選択中" else "選択") }
                            )
                        }
                        if (ready) {
                            Text("準備済み", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            if (spec.id != WhisperModelManager.MODEL_BASE) {
                                TextButton(onClick = {
                                    val ok = WhisperModelManager.deleteComparisonModel(context, spec.id)
                                    if (ok) {
                                        selectedIds = selectedIds - spec.id
                                        Toast.makeText(context, "${spec.label}を削除しました", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("モデルを削除") }
                            }
                        } else {
                            if (bytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { (bytes.toFloat() / spec.expectedBytes.toFloat()).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "取得中 ${formatModelBytes(bytes)} / ${formatModelBytes(spec.expectedBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            OutlinedButton(onClick = {
                                WhisperModelManager.enqueueModelDownload(context, spec.id)
                                selectedIds = selectedIds + spec.id
                                Toast.makeText(context, "${spec.label}の取得を開始します", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${spec.label}をダウンロード")
                            }
                        }
                    }
                }
            }

            val canRun = audioFile?.isFile == true && selectedSpecs.isNotEmpty() &&
                missingSelected.isEmpty() && !running
            Button(
                onClick = {
                    val file = audioFile ?: return@Button
                    val ids = selectedSpecs.map { it.id }.toTypedArray()
                    val queued = ModelComparisonScheduler.enqueue(context, record.segmentId, file, ids)
                    Toast.makeText(
                        context,
                        if (queued) {
                            if (ids.size == 1) "この音源の再実行を登録しました" else "モデル比較を登録しました"
                        } else "比較を登録できませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                enabled = canRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        running -> "比較実行中…"
                        selectedSpecs.size == 1 -> "この音源を選択モデルで再実行"
                        else -> "選択した${selectedSpecs.size}モデルで比較"
                    }
                )
            }

            if (audioFile?.isFile != true) {
                Text("元M4Aが削除済みのため、この記録はモデル比較できません。", color = MaterialTheme.colorScheme.error)
            } else if (selectedSpecs.isEmpty()) {
                Text("比較するモデルを1つ以上選択してください。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (missingSelected.isNotEmpty()) {
                Text(
                    "選択中の未取得モデル: ${missingSelected.joinToString { it.label }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            comparison?.let { result ->
                HorizontalDivider()
                ComparisonResultBlock(result)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val pretty = result.toString(2)
                            copyText(context, "24hRecoder モデル比較", pretty)
                            Toast.makeText(context, "比較結果をコピーしました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("結果をコピー") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    ModelComparisonRepository.buildCopyReport(context, record.segmentId)
                                }
                                copyText(context, "24hRecoder 比較診断", report)
                                Toast.makeText(context, "比較結果と関連ログをコピーしました", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("結果＋ログ") }
                }
                TextButton(onClick = {
                    ModelComparisonRepository.delete(context, record.segmentId)
                    comparison = null
                }) { Text("比較結果を消去") }
            }
        }
    }
}

@Composable
private fun ComparisonResultBlock(root: JSONObject) {
    val status = root.optString("status", "-")
    Text("比較結果  ${comparisonStatusLabel(status)}", fontWeight = FontWeight.Bold)
    if (root.has("audioDurationMs")) {
        Text(
            "音源 ${formatMs(root.optLong("audioDurationMs"))} • 復号 ${formatMs(root.optLong("decodeMs"))} • 前処理 ${formatMs(root.optLong("preprocessMs"))}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            String.format(
                Locale.JAPAN,
                "前処理: gain %+.1f dB • SNR目安 %.1f dB • active %.1f%%",
                root.optDouble("appliedGainDb"),
                root.optDouble("snrProxyDb"),
                root.optDouble("activeFrameFraction") * 100.0
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val vad = root.optJSONObject("vadDiagnostics")
    if (vad != null) {
        Text("Silero VAD独立解析", fontWeight = FontWeight.SemiBold)
        Text(
            "発話 ${vad.optInt("segmentCount")}区間 • 発話合計 ${formatMs(vad.optLong("totalSpeechMs"))} • 最終発話終了 ${formatOffset(vad.optLong("lastEndMs"))} • VAD ${formatMs(vad.optLong("vadDetectMs"))}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            String.format(
                Locale.JAPAN,
                "speech確率 mean %.3f / max %.3f • threshold以上 %.1f%%",
                vad.optDouble("meanSpeechProbability"),
                vad.optDouble("maxSpeechProbability"),
                vad.optDouble("aboveThresholdFraction") * 100.0
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val vadSegments = vad.optJSONArray("segments") ?: JSONArray()
        val visible = minOf(vadSegments.length(), 12)
        for (i in 0 until visible) {
            val segment = vadSegments.optJSONObject(i) ?: continue
            Text(
                "VAD #${i + 1}  ${formatOffset(segment.optLong("startMs"))}–${formatOffset(segment.optLong("endMs"))}  (${formatMs(segment.optLong("durationMs"))})",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (vadSegments.length() > visible) {
            Text(
                "ほか ${vadSegments.length() - visible}区間。全区間は「結果＋ログ」でコピーできます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    val results = root.optJSONArray("results") ?: JSONArray()
    for (i in 0 until results.length()) {
        val row = results.optJSONObject(i) ?: continue
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.optString("modelLabel", row.optString("modelId")), fontWeight = FontWeight.Bold)
                    Text(comparisonStatusLabel(row.optString("status")), style = MaterialTheme.typography.labelMedium)
                }
                if (row.optString("status") == "COMPLETED") {
                    if (row.has("reachesLastVad") && !row.optBoolean("reachesLastVad")) {
                        Text(
                            "警告: Whisper出力がVADの最終発話まで到達していません。後半欠落の可能性があります（差 ${formatMs(row.optLong("lastVadGapMs"))}）。",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text("文字起こし内容", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        val text = row.optString("text")
                        Text(if (text.isBlank()) "（空の文字起こし）" else text)
                    }

                    val segments = row.optJSONArray("segments") ?: JSONArray()
                    if (segments.length() > 0) {
                        Text("Whisper出力区間とデコーダ診断", style = MaterialTheme.typography.labelLarge)
                        for (j in 0 until segments.length()) {
                            val segment = segments.optJSONObject(j) ?: continue
                            Text(
                                "${formatOffset(segment.optLong("startMs"))}–${formatOffset(segment.optLong("endMs"))}  ${segment.optString("text").trim()}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (segment.has("noSpeechProbability")) {
                                Text(
                                    String.format(
                                        Locale.JAPAN,
                                        "  tokens %d • tokenP avg %.3f / min %.3f • noSpeech %.3f",
                                        segment.optInt("tokenCount"),
                                        segment.optDouble("avgTokenProbability"),
                                        segment.optDouble("minTokenProbability"),
                                        segment.optDouble("noSpeechProbability")
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        "Whisper出力 ${row.optInt("segmentCount")}区間 • 出力区間スパン合計 ${formatMs(row.optLong("outputSegmentDurationMs", row.optLong("recognizedSpeechMs")))} • 最終出力 ${formatOffset(row.optLong("lastOutputEndMs"))} • ${row.optInt("textChars")}文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        String.format(
                            Locale.JAPAN,
                            "処理: JNI全体 %.2f秒 • モデル読込 %.2f秒 • whisper_full %.2f秒 • RTF %.3f",
                            row.optLong("inferenceMs") / 1000.0,
                            row.optLong("modelLoadMs") / 1000.0,
                            row.optLong("whisperFullMs") / 1000.0,
                            row.optDouble("realTimeFactor")
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "参考総時間 ${formatMs(row.optLong("referenceEndToEndMs"))} • モデル ${formatModelBytes(row.optLong("modelBytes"))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (row.has("message") || row.has("error")) {
                    Text(
                        listOf(row.optString("error"), row.optString("message")).filter { it.isNotBlank() }.joinToString(": "),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun comparisonStatusLabel(status: String) = when (status) {
    "RUNNING" -> "実行中"
    "COMPLETED" -> "完了"
    "PARTIAL" -> "一部完了"
    "FAILED" -> "失敗"
    "MODEL_MISSING" -> "モデル未準備"
    else -> status
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun formatModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "-"
    val mib = bytes / 1024.0 / 1024.0
    return if (mib >= 1024.0) String.format(Locale.JAPAN, "%.2f GiB", mib / 1024.0)
    else String.format(Locale.JAPAN, "%.1f MiB", mib)
}

private fun formatMs(ms: Long): String = when {
    ms >= 60_000L -> String.format(Locale.JAPAN, "%.1f分", ms / 60_000.0)
    ms >= 1_000L -> String.format(Locale.JAPAN, "%.2f秒", ms / 1000.0)
    ms >= 0L -> "${ms}ms"
    else -> "-"
}

private fun formatOffset(ms: Long): String {
    if (ms < 0L) return "-"
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (ms % 1000L) / 100L
    return String.format(Locale.JAPAN, "%02d:%02d.%01d", minutes, seconds, tenths)
}
