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
import androidx.compose.material3.Checkbox
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
    val scope = rememberCoroutineScope()
    var exportFile by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/ogg")
    ) { uri ->
        val file = exportFile
        if (uri != null && file != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } != null
                    } catch (_: Exception) { false }
                }
                Toast.makeText(context, if (saved) "音声を書き出しました" else "書き出しに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
        exportFile = null
    }
    var source by remember { mutableStateOf(MemoketSettings.source(context)) }
    var address by remember { mutableStateOf(MemoketSettings.address(context)) }
    var automatic by remember { mutableStateOf(MemoketSettings.enabled(context)) }
    var result by remember { mutableStateOf(MemoketSettings.result(context)) }
    var fileCount by remember { mutableStateOf(MemoketRecordingStore(context).count()) }
    var remoteState by remember { mutableStateOf(MemoketSettings.remoteRecordingState(context)) }
    var physicalStopConfirmed by remember { mutableStateOf(false) }
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
            result = MemoketSettings.result(context)
            fileCount = MemoketRecordingStore(context).count()
            remoteState = MemoketSettings.remoteRecordingState(context)
            delay(2_000)
        }
    }

    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("録音データの接続先", style = MaterialTheme.typography.titleLarge)
            Text("GemはBluetoothマイクではなく、本体に保存された録音を後から転送する機器です。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == "LOCAL",
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
                    onClick = {
                        MemoketSettings.setSource(context, "MEMOKET")
                        source = "MEMOKET"
                    },
                    label = { Text("Memoket Gem") }
                )
            }
            if (source == "MEMOKET") {
                Text("選択中のGem: ${if (address.isEmpty()) "未選択" else address}")
                OutlinedButton(
                    enabled = !scanning,
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
                        onClick = {
                            MemoketSettings.selectDevice(context, deviceAddress)
                            address = deviceAddress
                            scanState = "$name を選択しました"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("$name ($deviceAddress)") }
                }
                Text("定期取得は、Gemの録音中・停止中を安全に判別できるようになるまで無効です。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.weight(1f)
                    ) { Text("遠隔開始（検証待ち）") }
                    OutlinedButton(
                        enabled = remoteState == "録音中",
                        onClick = {
                            context.startService(
                                android.content.Intent(context, MemoketRemoteRecordingService::class.java)
                                    .setAction(MemoketRemoteRecordingService.ACTION_STOP_RECORDING)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("録音操作を終了") }
                }
                Text("Gem録音状態: $remoteState")
                Text("遠隔停止は利用できません。本体で停止後に録音を取得してください。")
                if (remoteState == "Gem本体停止待ち" ||
                    (remoteState == "エラー" && MemoketSettings.recordingStopRequestedAt(context) > 0L)) {
                    Button(
                        enabled = address.isNotEmpty(),
                        onClick = {
                            try {
                                val startedAt = MemoketSettings.recordingStartedAt(context)
                                val requestedAt = MemoketSettings.recordingStopRequestedAt(context)
                                val confirmedAt = System.currentTimeMillis()
                                require(requestedAt > 0L && startedAt > 0L && confirmedAt >= requestedAt) {
                                    "停止要求の記録がありません"
                                }
                                MemoketSyncScheduler.syncAfterStop(
                                    context, startedAt, confirmedAt,
                                    MemoketSettings.recordingStartedZone(context)
                                )
                                MemoketSettings.setRemoteRecordingState(context, "停止操作済・取得待ち")
                                MemoketSettings.saveResult(context, "Gem本体の停止をユーザーが確認。対象1ファイルの取得を実行します")
                                remoteState = "停止操作済・取得待ち"
                            } catch (exception: Exception) {
                                MemoketSettings.saveResult(context, "取得開始失敗: ${exception.message ?: "原因不明"}")
                                result = MemoketSettings.result(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("本体停止を確認して今回の録音1件を取得") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = physicalStopConfirmed,
                        onCheckedChange = { physicalStopConfirmed = it }
                    )
                    Text("Gem本体の録音を停止しました（短い振動2回・赤LED消灯を確認）")
                }
                Button(
                    enabled = physicalStopConfirmed && address.isNotEmpty() && remoteState != "録音中" && remoteState != "接続中" && remoteState != "停止処理中" && remoteState != "ファイル取得中" && remoteState != "停止操作済・取得待ち" && remoteState != "Gem本体停止待ち",
                    onClick = {
                        physicalStopConfirmed = false
                        MemoketSyncScheduler.syncNowAfterPhysicalStopConfirmation(context)
                        scanState = "停止を確認したため、保存済み録音の取得を要求しました"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("停止確認後に保存済み録音を取得（過去分含む）") }
                Text("端末内保存: ${fileCount}件 / 最新結果: $result")
                Button(
                    enabled = address.isNotEmpty() && remoteState != "録音中" && remoteState != "接続中" && remoteState != "停止処理中" && remoteState != "ファイル取得中" && remoteState != "停止操作済・取得待ち" && remoteState != "Gem本体停止待ち",
                    onClick = {
                        context.startActivity(android.content.Intent(context, MemoketTestActivity::class.java))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Memoketテストを開く") }
                Text(
                    "開始・停止・振動・ファイル取得の因果関係を通常録音とは分離して検証します。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    enabled = fileCount > 0,
                    onClick = {
                        val last = MemoketRecordingStore(context).directory()
                            .listFiles { _, fileName -> fileName.endsWith(".opus") }
                            ?.maxByOrNull { it.lastModified() }
                        if (last != null) {
                            exportFile = last
                            exportLauncher.launch(last.name)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("最新の録音をファイルに保存") }
                Text(
                    "同期はAndroidの実行制約によって遅れる場合があります。Memoketテストは診断専用で、通常の録音操作とは別画面で実行します。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
