package com.sktpj.recorder24h

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings
import com.sktpj.recorder24h.util.AppLogger
import org.json.JSONObject

@Composable
fun TranscriptionBackendSettingsCard() {
    val context = LocalContext.current
    var pipeline by remember { mutableStateOf(TranscriptionPipelineSettings.snapshot(context)) }
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
                "Vulkan/Android端末内ASRは端末機能で判定します。Pixel機種名で強制しないため、他端末ではCPU経路を選択したまま利用できます。設定変更は次に処理を開始する1件から反映されます。",
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
