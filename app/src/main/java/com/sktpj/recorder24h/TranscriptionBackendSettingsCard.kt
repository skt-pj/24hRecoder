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
import com.sktpj.recorder24h.transcription.LiveTranscriptionSettings
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
    var fiveMinuteFinalEnabled by remember { mutableStateOf(LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context)) }
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
        fiveMinuteFinalEnabled = LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context)
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
                    text = "5分終了後に処理"
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
                    text = "完全ストリーミング"
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
                    "通常の文字起こしキューは自動停止しません。ライブ中に止める場合は、キュー画面の「一時停止」を使ってください。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }


            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING) {
                PipelineSection("5分後の確定文字起こし") {
                    BackendChip(
                        selected = fiveMinuteFinalEnabled,
                        enabled = true,
                        text = "ON"
                    ) {
                        val before = fiveMinuteFinalEnabled.toString()
                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, true)
                        refresh(); logChange("fiveMinuteFinalEnabled", before, fiveMinuteFinalEnabled.toString())
                    }
                    BackendChip(
                        selected = !fiveMinuteFinalEnabled,
                        enabled = true,
                        text = "OFF"
                    ) {
                        val before = fiveMinuteFinalEnabled.toString()
                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, false)
                        refresh(); logChange("fiveMinuteFinalEnabled", before, fiveMinuteFinalEnabled.toString())
                    }
                }
                Text(
                    if (fiveMinuteFinalEnabled)
                        "ON: ライブ表示とは別に、5分音声を通常/確定モデルで再処理して履歴の確定結果にします。"
                    else
                        "OFF: 5分後の通常モデル再処理は行わず、ライブで確定した発話を5分履歴へ保存します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING &&
                pipeline.vadBackend != TranscriptionPipelineSettings.VAD_STREAMING_SILERO
            ) {
                Text(
                    "完全ストリーミングには「Silero ストリーミング」を選択してください。自動変更はしません。",
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
                PipelineSection("ライブWhisper経路") {
                    BackendChip(
                        selected = pipeline.liveWhisperRoute == TranscriptionPipelineSettings.LIVE_WHISPER_PERSISTENT,
                        enabled = true,
                        text = "ライブ専用常駐"
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
                        text = "通常JNI（発話ごとロード）"
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
                        "通常JNIは5分処理と同じnativeTranscribeDetailedを発話ごとに使い、毎回モデルをロード・解放します。"
                    else
                        "ライブ専用常駐はモデルを保持する既存経路です。どちらも選択backendのままで、自動フォールバックしません。",
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
                    Text("ライブ文字起こし", fontWeight = FontWeight.SemiBold)
                    Text(
                        "状態: ${liveState.state} / backend: ${liveState.backend ?: "-"} / 推論待ち: ${liveState.queueDepth}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (liveState.partialText.isNotBlank()) {
                        Text("暫定: ${liveState.partialText}", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (liveState.latestFinalText.isNotBlank()) {
                        Text("最新確定: ${liveState.latestFinalText}", style = MaterialTheme.typography.bodyMedium)
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
                "完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡します。ライブモデルと5分確定モデルは独立し、5分後の通常モデル確定はON/OFFできます。5分音声保存は常に継続し、録音中の設定変更は次の5分セグメント境界から反映されます。",
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
