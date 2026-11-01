package com.sktpj.recorder24h

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.memoket.MemoketRecordingStore
import com.sktpj.recorder24h.memoket.MemoketRemoteRecordingService
import com.sktpj.recorder24h.storage.RecordingIntentStore
import com.sktpj.recorder24h.memoket.MemoketSettings
import com.sktpj.recorder24h.memoket.MemoketSyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MemoketGemSettingsCard() {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var source by remember { mutableStateOf(MemoketSettings.source(context)) }
    var address by remember { mutableStateOf(MemoketSettings.address(context)) }
    var automatic by remember { mutableStateOf(MemoketSettings.enabled(context)) }
    var recordingActive by remember { mutableStateOf(RecordingIntentStore.isRequested(context)) }
    var result by remember { mutableStateOf(MemoketSettings.result(context)) }
    var remoteState by remember { mutableStateOf(MemoketSettings.remoteRecordingState(context)) }
    var scanState by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var scanning by remember { mutableStateOf(false) }
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                val name = try {
                    scanResult.scanRecord?.deviceName ?: scanResult.device.name.orEmpty()
                } catch (_: SecurityException) { "" }
                if (!name.contains("Memoket", ignoreCase = true)) return
                val found = scanResult.device.address
                if (devices.none { it.first == found }) {
                    devices = devices + (found to name)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                scanState = "検索に失敗しました ($errorCode)"
            }
        }
    }

    val doScan = {
        try {
            adapter?.bluetoothLeScanner?.startScan(callback)
                ?: throw IllegalStateException("Bluetoothが無効です")
            devices = emptyList()
            scanning = true
            scanState = "Memoket Gemを検索中"
            handler.postDelayed({
                try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
                scanning = false
                if (devices.isEmpty()) scanState = "Memoket Gemが見つかりません"
                else scanState = "${devices.size}台見つかりました"
            }, 10_000)
        } catch (exception: Exception) {
            scanning = false
            scanState = "検索を開始できませんでした"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) doScan()
        else scanState = "Bluetoothの検索・接続権限が必要です"
    }

    DisposableEffect(Unit) {
        onDispose {
            try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) { }
        }
    }

    LaunchedEffect(Unit) {
        if (MemoketSettings.enabled(context)) {
            MemoketSyncScheduler.setPeriodic(context, false)
            automatic = false
        }
        while (true) {
            recordingActive = RecordingIntentStore.isRequested(context)
            result = MemoketSettings.result(context)
            remoteState = MemoketSettings.remoteRecordingState(context)
            delay(2_000)
        }
    }

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("録音データの接続先", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == "LOCAL",
                    enabled = !recordingActive,
                    onClick = {
                        MemoketSettings.setSource(context, "LOCAL")
                        MemoketSyncScheduler.setPeriodic(context, false)
                        source = "LOCAL"
                        automatic = false
                    },
                    label = { Text("端末マイク／BTマイク") }
                )
                FilterChip(
                    selected = source == "MEMOKET",
                    enabled = !recordingActive,
                    onClick = {
                        MemoketSettings.setSource(context, "MEMOKET")
                        source = "MEMOKET"
                    },
                    label = { Text("Memoket Gem") }
                )
            }
            if (recordingActive) Text("入力先の変更は録音停止後に行えます")
            if (source == "MEMOKET") {
                Text("選択中のGem: ${if (address.isEmpty()) "未選択" else address}")
                OutlinedButton(
                    enabled = !scanning && !recordingActive,
                    onClick = {
                        val required = if (Build.VERSION.SDK_INT >= 31) {
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                            doScan()
                        } else {
                            permissionLauncher.launch(required)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (scanning) "検索中" else "Gemを検索") }
                if (scanState.isNotEmpty()) Text(scanState)
                devices.forEach { (deviceAddress, name) ->
                    OutlinedButton(
                        enabled = !recordingActive,
                        onClick = {
                            MemoketSettings.selectDevice(context, deviceAddress)
                            address = deviceAddress
                            scanState = "$name を選択しました"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("$name ($deviceAddress)") }
                }
                Text("Gemの録音はホームの「録音開始・停止」から操作します。")
                Text("状態: $remoteState")
                if (remoteState == "エラー" && result.isNotBlank()) Text(result)
                Text("録音済み音声と文字起こしは「記録」から確認できます。")
            }
        }
    }
}
