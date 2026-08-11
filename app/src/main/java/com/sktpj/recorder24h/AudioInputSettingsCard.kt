package com.sktpj.recorder24h

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.sktpj.recorder24h.audio.AudioInputRouteStateStore
import com.sktpj.recorder24h.audio.AudioInputRouter
import com.sktpj.recorder24h.audio.AudioInputSettingsStore
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun AudioInputSettingsCard() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(AudioInputSettingsStore.read(context)) }
    var bluetoothInputs by remember { mutableStateOf(AudioInputRouter.availableBluetoothMics(context)) }
    var routeState by remember { mutableStateOf(AudioInputRouteStateStore.read(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            settings = AudioInputSettingsStore.read(context)
            bluetoothInputs = AudioInputRouter.availableBluetoothMics(context)
            routeState = AudioInputRouteStateStore.read(context)
            delay(1_000L)
        }
    }

    val selectedBluetoothConnected = bluetoothInputs.any { it.key == settings.manualDeviceKey }
    val actualLabel = routeState.optString("actualDeviceLabel", "")
        .takeUnless { it.isBlank() || it == "null" }
    val preferredLabel = routeState.optString("preferredDeviceLabel", "")
        .takeUnless { it.isBlank() || it == "null" }
    val fallbackReason = routeState.optString("fallbackReason", "")
        .takeUnless { it.isBlank() || it == "null" }

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("録音入力", style = MaterialTheme.typography.titleLarge)
            Text(
                "Bluetoothマイクと端末マイクの使い方を選択します。録音中の接続・切断にも追従します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.mode == AudioInputSettingsStore.MODE_AUTO,
                    onClick = {
                        val before = settings
                        AudioInputSettingsStore.setAuto(context)
                        settings = AudioInputSettingsStore.read(context)
                        logAudioInputSetting(context, before.mode, settings, "AUTO")
                    },
                    label = { Text("自動") }
                )
                FilterChip(
                    selected = settings.mode == AudioInputSettingsStore.MODE_MANUAL,
                    onClick = {
                        val before = settings
                        AudioInputSettingsStore.setManual(
                            context,
                            settings.manualDeviceKey,
                            settings.manualDeviceLabel,
                            settings.manualDeviceType
                        )
                        settings = AudioInputSettingsStore.read(context)
                        logAudioInputSetting(context, before.mode, settings, "MANUAL")
                    },
                    label = { Text("手動") }
                )
            }

            if (settings.mode == AudioInputSettingsStore.MODE_AUTO) {
                Text(
                    if (bluetoothInputs.isEmpty())
                        "現在はBluetoothマイクが見つからないため、端末マイクを使用します。"
                    else
                        "Bluetoothマイクを検出中: ${bluetoothInputs.joinToString { it.label }}。自動ではBluetoothを優先します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("手動で使う入力", fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = settings.manualDeviceKey == AudioInputSettingsStore.BUILTIN_KEY,
                            onClick = {
                                val before = settings
                                AudioInputSettingsStore.setManual(
                                    context,
                                    AudioInputSettingsStore.BUILTIN_KEY,
                                    "端末マイク",
                                    AudioDeviceInfo.TYPE_BUILTIN_MIC
                                )
                                settings = AudioInputSettingsStore.read(context)
                                logAudioInputSetting(context, before.manualDeviceKey, settings, "BUILTIN")
                            },
                            label = { Text("端末マイク") }
                        )
                    }
                    items(bluetoothInputs, key = { it.key }) { device ->
                        FilterChip(
                            selected = settings.manualDeviceKey == device.key,
                            onClick = {
                                val before = settings
                                AudioInputSettingsStore.setManual(
                                    context,
                                    device.key,
                                    device.label,
                                    device.type
                                )
                                settings = AudioInputSettingsStore.read(context)
                                logAudioInputSetting(context, before.manualDeviceKey, settings, device.key)
                            },
                            label = { Text(device.label) }
                        )
                    }
                }

                if (settings.manualDeviceKey != AudioInputSettingsStore.BUILTIN_KEY && !selectedBluetoothConnected) {
                    Text(
                        "選択中: ${settings.manualDeviceLabel}（未接続）。接続されるまでは端末マイクへ戻します。再接続すると選択したBluetoothマイクへ戻ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Text(
                        "手動でも選択したBluetoothマイクが使えない場合は端末マイクへフォールバックします。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "現在の入力: ${actualLabel ?: preferredLabel ?: "録音開始後に確認できます"}",
                fontWeight = FontWeight.SemiBold
            )
            fallbackReasonLabel(fallbackReason)?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "設定変更は録音中でも反映します。設定変更は最大約5秒、Bluetooth機器の接続・切断はデバイス変更通知を受けて再ルーティングします。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun fallbackReasonLabel(reason: String?): String? {
    if (reason == null) return null
    return when {
        reason.contains("MANUAL_BT_NOT_AVAILABLE") -> "選択したBluetoothマイクが未接続のため端末マイクを使用中です。"
        reason.contains("AUTO_BT_NOT_AVAILABLE") -> "Bluetoothマイクがないため端末マイクを使用中です。"
        reason.contains("PREFERRED_BT_REJECTED") -> "Bluetooth入力をAndroidが受理しなかったため端末マイクへ戻しました。"
        reason.contains("SYSTEM_DEFAULT_FALLBACK") -> "指定入力を使用できず、Androidの既定入力へフォールバックしました。"
        else -> null
    }
}

private fun logAudioInputSetting(
    context: android.content.Context,
    before: String,
    settings: AudioInputSettingsStore.Settings,
    selected: String
) {
    try {
        AppLogger.event(
            context,
            "AUDIO_INPUT_SETTINGS_CHANGED",
            JSONObject()
                .put("before", before)
                .put("mode", settings.mode)
                .put("selected", selected)
                .put("manualDeviceKey", settings.manualDeviceKey)
                .put("manualDeviceLabel", settings.manualDeviceLabel)
        )
    } catch (_: Exception) {
    }
}
