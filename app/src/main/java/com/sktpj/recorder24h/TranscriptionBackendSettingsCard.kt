package com.sktpj.recorder24h

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.sktpj.recorder24h.transcription.FullStreamingStateStore
import com.sktpj.recorder24h.transcription.NightlyHourlyTranscriptionScheduler
import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TranscriptionBackendSettingsCard() {
    val context = LocalContext.current
    var pipeline by remember { mutableStateOf(TranscriptionPipelineSettings.snapshot(context)) }
    var liveState by remember { mutableStateOf(FullStreamingStateStore.readLiveState(context)) }
    val vulkanAvailable = remember {
        TranscriptionPipelineSettings.isAsrRuntimeAvailable(
            context, TranscriptionPipelineSettings.ASR_WHISPER_VULKAN
        )
    }
    val androidAsrAvailable = remember {
        TranscriptionPipelineSettings.isAsrRuntimeAvailable(
            context, TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE
        )
    }

    LaunchedEffect(Unit) {
        AppLogger.event(
            context,
            "TRANSCRIPTION_BACKEND_CAPABILITIES",
            TranscriptionPipelineSettings.capabilities(context)
        )
        while (true) {
            liveState = withContext(Dispatchers.IO) { FullStreamingStateStore.readLiveState(context) }
            delay(750L)
        }
    }

    fun refresh() {
        pipeline = TranscriptionPipelineSettings.snapshot(context)
    }

    fun logChange(kind: String, before: String, after: String) {
        try {
            AppLogger.event(
                context,
                "UI_TRANSCRIPTION_PIPELINE_CHANGED",
                JSONObject()
                    .put("kind", kind)
                    .put("before", before)
                    .put("after", after)
                    .put("automaticFallback", false)
                    .put("pipeline", TranscriptionPipelineSettings.snapshot(context).toJson())
            )
        } catch (_: Exception) {
        }
    }

    Card {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("文字起こし処理経路", style = MaterialTheme.typography.titleLarge)
            Text(
                "各処理を明示的に選択します。選択した経路が利用できない・失敗した場合も、別のCPU/GPU/ASRへ自動切替はしません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PipelineSection("文字起こし方式") {
                BackendChip(
                    selected = pipeline.executionMode == TranscriptionPipelineSettings.MODE_SEGMENT_POSTPROCESS,
                    enabled = true,
                    text = "夜間確定のみ"
                ) {
                    val before = pipeline.executionMode
                    TranscriptionPipelineSettings.setExecutionMode(
                        context, TranscriptionPipelineSettings.MODE_SEGMENT_POSTPROCESS
                    )
                    refresh(); logChange("executionMode", before, pipeline.executionMode)
                }
                BackendChip(
                    selected = pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING,
                    enabled = true,
                    text = "30秒暫定 + 夜間確定"
                ) {
                    val before = pipeline.executionMode
                    TranscriptionPipelineSettings.setExecutionMode(
                        context, TranscriptionPipelineSettings.MODE_LIVE_STREAMING
                    )
                    refresh(); logChange("executionMode", before, pipeline.executionMode)
                }
            }

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING) {
                Text(
                    "録音中は30秒単位の暫定文字起こしだけを表示します。発話ごとのWhisper実行と5分ごとの確定Whisperは行いません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("確定文字起こし", fontWeight = FontWeight.SemiBold)
                Text(
                    "前日分を端末のローカル時刻 ${NightlyHourlyTranscriptionScheduler.NIGHT_START_HOUR_LOCAL}:00 以降にまとめて処理し、履歴では1時間単位にまとめます。録音ファイルは安全性のため内部では5分分割のまま保持します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING &&
                pipeline.vadBackend != TranscriptionPipelineSettings.VAD_STREAMING_SILERO
            ) {
                Text(
                    "30秒暫定表示には「Silero ストリーミング」を選択してください。自動変更はしません。",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }

            PipelineSection("ASR") {
                BackendChip(
                    selected = pipeline.asrBackend == TranscriptionPipelineSettings.ASR_WHISPER_CPU,
                    enabled = true,
                    text = "Whisper CPU"
                ) {
                    val before = pipeline.asrBackend
                    TranscriptionPipelineSettings.setAsr(context, TranscriptionPipelineSettings.ASR_WHISPER_CPU)
                    refresh(); logChange("asr", before, pipeline.asrBackend)
                }
                BackendChip(
                    selected = pipeline.asrBackend == TranscriptionPipelineSettings.ASR_WHISPER_VULKAN,
                    enabled = vulkanAvailable,
                    text = if (vulkanAvailable) "Whisper Vulkan GPU" else "Vulkan GPU（利用不可）"
                ) {
                    val before = pipeline.asrBackend
                    TranscriptionPipelineSettings.setAsr(context, TranscriptionPipelineSettings.ASR_WHISPER_VULKAN)
                    refresh(); logChange("asr", before, pipeline.asrBackend)
                }
                BackendChip(
                    selected = pipeline.asrBackend == TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE,
                    enabled = androidAsrAvailable,
                    text = if (androidAsrAvailable) "Android 端末内ASR" else "Android端末内ASR（利用不可）"
                ) {
                    val before = pipeline.asrBackend
                    TranscriptionPipelineSettings.setAsr(context, TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE)
                    refresh(); logChange("asr", before, pipeline.asrBackend)
                }
            }

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING &&
                pipeline.asrBackend != TranscriptionPipelineSettings.ASR_ANDROID_ON_DEVICE
            ) {
                PipelineSection("暫定Whisper経路") {
                    BackendChip(
                        selected = pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT,
                        enabled = true,
                        text = "モデル常駐"
                    ) {
                        val before = pipeline.liveWhisperRoute
                        TranscriptionPipelineSettings.setLiveWhisperRoute(
                            context, TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT
                        )
                        refresh(); logChange("liveWhisperRoute", before, pipeline.liveWhisperRoute)
                    }
                    BackendChip(
                        selected = pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE,
                        enabled = true,
                        text = "通常JNI（30秒ごとロード）"
                    ) {
                        val before = pipeline.liveWhisperRoute
                        TranscriptionPipelineSettings.setLiveWhisperRoute(
                            context, TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE
                        )
                        refresh(); logChange("liveWhisperRoute", before, pipeline.liveWhisperRoute)
                    }
                }
                Text(
                    if (pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_POSTPROCESS_NATIVE)
                        "通常JNIは30秒の暫定窓ごとにモデルをロード・解放します。"
                    else
                        "モデル常駐は同じモデルを保持して30秒窓を順次処理します。どちらも選択backendのままで、自動フォールバックしません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            PipelineSection("VAD") {
                BackendChip(
                    selected = pipeline.vadBackend == TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO,
                    enabled = true,
                    text = "Silero 候補区間"
                ) {
                    val before = pipeline.vadBackend
                    TranscriptionPipelineSettings.setVad(context, TranscriptionPipelineSettings.VAD_CANDIDATE_SILERO)
                    refresh(); logChange("vad", before, pipeline.vadBackend)
                }
                BackendChip(
                    selected = pipeline.vadBackend == TranscriptionPipelineSettings.VAD_STREAMING_SILERO,
                    enabled = true,
                    text = "Silero ストリーミング"
                ) {
                    val before = pipeline.vadBackend
                    TranscriptionPipelineSettings.setVad(context, TranscriptionPipelineSettings.VAD_STREAMING_SILERO)
                    refresh(); logChange("vad", before, pipeline.vadBackend)
                }
            }

            PipelineSection("ノイズ処理") {
                BackendChip(
                    selected = pipeline.denoiseBackend == TranscriptionPipelineSettings.DENOISE_OFF,
                    enabled = true,
                    text = "なし"
                ) {
                    val before = pipeline.denoiseBackend
                    TranscriptionPipelineSettings.setDenoise(context, TranscriptionPipelineSettings.DENOISE_OFF)
                    refresh(); logChange("denoise", before, pipeline.denoiseBackend)
                }
                BackendChip(
                    selected = pipeline.denoiseBackend == TranscriptionPipelineSettings.DENOISE_DEEPFILTER,
                    enabled = true,
                    text = "DeepFilterNet 適応"
                ) {
                    val before = pipeline.denoiseBackend
                    TranscriptionPipelineSettings.setDenoise(context, TranscriptionPipelineSettings.DENOISE_DEEPFILTER)
                    refresh(); logChange("denoise", before, pipeline.denoiseBackend)
                }
            }

            PipelineSection("話者判定") {
                BackendChip(
                    selected = pipeline.speakerBackend == TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU,
                    enabled = true,
                    text = "sherpa-onnx CPU"
                ) {
                    val before = pipeline.speakerBackend
                    TranscriptionPipelineSettings.setSpeaker(context, TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU)
                    refresh(); logChange("speaker", before, pipeline.speakerBackend)
                }
                BackendChip(
                    selected = pipeline.speakerBackend == TranscriptionPipelineSettings.SPEAKER_OFF,
                    enabled = true,
                    text = "話者判定なし"
                ) {
                    val before = pipeline.speakerBackend
                    TranscriptionPipelineSettings.setSpeaker(context, TranscriptionPipelineSettings.SPEAKER_OFF)
                    refresh(); logChange("speaker", before, pipeline.speakerBackend)
                }
            }

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING ||
                liveState.state != "OFF"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("30秒暫定文字起こし", fontWeight = FontWeight.SemiBold)
                    Text(
                        "状態: ${liveState.state} / backend: ${liveState.backend ?: "-"} / 推論待ち: ${liveState.queueDepth}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (liveState.partialText.isNotBlank()) {
                        Text("処理中: ${liveState.partialText}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (liveState.latestFinalText.isNotBlank()) {
                        Text("最新暫定: ${liveState.latestFinalText}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!liveState.error.isNullOrBlank()) {
                        Text("エラー: ${liveState.error}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Text(
                "端末: ${Build.MANUFACTURER} ${Build.MODEL} / Android API ${Build.VERSION.SDK_INT}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Vulkan: ${if (vulkanAvailable) "利用可能" else "利用不可"} / Android端末内ASR: ${if (androidAsrAvailable) "利用可能" else "利用不可"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "録音中PCMは専用ASRプロセスへ渡し、暫定表示は30秒窓で更新します。確定処理は前日分を夜間にまとめて実行します。内部5分M4Aは録音継続・障害復旧のための保存単位であり、5分ごとに確定文字起こしを出すための単位ではありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PipelineSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
        }
    }
}

@Composable
private fun BackendChip(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) }
    )
}
