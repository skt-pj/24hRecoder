package com.sktpj.recorder24h

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sktpj.recorder24h.service.RecorderService
import com.sktpj.recorder24h.storage.RecorderStateStore
import com.sktpj.recorder24h.storage.RecordingIntentStore
import com.sktpj.recorder24h.storage.StoragePolicy
import com.sktpj.recorder24h.transcription.LocalWhisperEngine
import com.sktpj.recorder24h.transcription.TranscriptionRepository
import com.sktpj.recorder24h.transcription.TranscriptionScheduler
import com.sktpj.recorder24h.transcription.WhisperModelManager
import com.sktpj.recorder24h.ui.SegmentHistoryRepository
import com.sktpj.recorder24h.ui.SegmentRecord
import com.sktpj.recorder24h.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private companion object {
        const val REQUEST_PERMISSIONS = 100
    }

    private var startAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogger.event(this, "MAIN_ACTIVITY_CREATED")

        if (WhisperModelManager.isReady(this)) {
            TranscriptionScheduler.enqueueExisting(this)
        }

        setContent {
            RecorderTheme {
                RecorderApp(
                    onRequestStart = { requestStart() },
                    onStop = { stopRecording() },
                    onOpenSystemSettings = { openSystemSettings() }
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (startAfterPermission &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startAfterPermission = false
            startRecording()
        } else {
            startAfterPermission = false
        }
    }

    private fun requestStart() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        RecordingIntentStore.setRequested(this, true)
        RecorderStateStore.write(this, "STARTING", null, null)
        val intent = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        try {
            startForegroundService(intent)
            AppLogger.event(this, "UI_START_RECORDING")
        } catch (error: RuntimeException) {
            RecordingIntentStore.setRequested(this, false)
            RecorderStateStore.write(this, "ERROR", null, error.message)
            AppLogger.event(this, "UI_START_RECORDING_FAILED")
            Toast.makeText(this, "録音を開始できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        RecordingIntentStore.setRequested(this, false)
        val intent = Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
        try {
            startService(intent)
            AppLogger.event(this, "UI_STOP_RECORDING")
        } catch (error: RuntimeException) {
            RecorderStateStore.write(this, "ERROR", null, error.message)
            Toast.makeText(this, "録音を停止できませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}

private enum class AppSection(val label: String) {
    HOME("ホーム"),
    HISTORY("記録"),
    SETTINGS("設定")
}

private enum class HistoryFilter(val label: String) {
    ALL("すべて"),
    TRANSCRIBED("文字起こし済み"),
    AUDIO("音声あり"),
    ATTENTION("要確認")
}

private data class DashboardSnapshot(
    val state: String,
    val heartbeatMs: Long,
    val segmentId: String,
    val error: String,
    val recordingRequested: Boolean,
    val audioBytes: Long,
    val appBytes: Long,
    val deviceFreeBytes: Long,
    val modelReady: Boolean,
    val modelBytes: Long,
    val pendingAudio: Int,
    val transcriptCount: Int
)

@Composable
private fun RecorderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    val typography = Typography(
        headlineLarge = TextStyle(
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold
        ),
        headlineMedium = TextStyle(
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold
        ),
        titleLarge = TextStyle(
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
    val shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp)
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = shapes,
        content = content
    )
}

@Composable
private fun RecorderApp(
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(AppSection.HOME) }
    var selectedSegmentId by remember { mutableStateOf<String?>(null) }
    var historyRecords by remember { mutableStateOf<List<SegmentRecord>>(emptyList()) }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var dashboard by remember { mutableStateOf(readDashboard(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            dashboard = withContext(Dispatchers.IO) { readDashboard(context) }
            delay(2_000L)
        }
    }

    LaunchedEffect(section, historyRefresh) {
        if (section == AppSection.HISTORY) {
            do {
                historyRecords = withContext(Dispatchers.IO) {
                    SegmentHistoryRepository.load(context)
                }
                delay(5_000L)
            } while (section == AppSection.HISTORY)
        }
    }

    val selectedRecord = selectedSegmentId?.let { id ->
        historyRecords.firstOrNull { it.segmentId == id }
    }

    BackHandler(enabled = selectedSegmentId != null) {
        selectedSegmentId = null
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val title = when {
            selectedRecord != null -> "記録の詳細"
            section == AppSection.HOME -> "24hRecoder"
            section == AppSection.HISTORY -> "記録"
            else -> "設定"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title)
                            if (section == AppSection.HOME && selectedRecord == null) {
                                Text(
                                    "端末内録音・ローカル文字起こし",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (selectedRecord != null) {
                            IconButton(onClick = { selectedSegmentId = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "記録一覧へ戻る")
                            }
                        }
                    },
                    actions = {
                        if (section == AppSection.HISTORY && selectedRecord == null) {
                            IconButton(onClick = { historyRefresh++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "記録を更新")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!expanded && selectedRecord == null) {
                    NavigationBar {
                        AppSection.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = section == destination,
                                onClick = {
                                    section = destination
                                    selectedSegmentId = null
                                },
                                icon = { SectionIcon(destination) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (expanded && selectedRecord == null) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(8.dp))
                        AppSection.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = section == destination,
                                onClick = {
                                    section = destination
                                    selectedSegmentId = null
                                },
                                icon = { SectionIcon(destination) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    when {
                        selectedRecord != null -> RecordDetailScreen(record = selectedRecord)
                        section == AppSection.HOME -> HomeScreen(
                            dashboard = dashboard,
                            onRequestStart = onRequestStart,
                            onStop = onStop,
                            onOpenHistory = {
                                section = AppSection.HISTORY
                                selectedSegmentId = null
                                historyRefresh++
                            }
                        )
                        section == AppSection.HISTORY -> HistoryScreen(
                            records = historyRecords,
                            onSelect = { selectedSegmentId = it.segmentId }
                        )
                        section == AppSection.SETTINGS -> SettingsScreen(
                            dashboard = dashboard,
                            onDownloadModel = {
                                WhisperModelManager.enqueueDownload(context)
                                AppLogger.event(context, "UI_WHISPER_MODEL_DOWNLOAD_REQUESTED")
                                Toast.makeText(
                                    context,
                                    "Whisper baseモデルのダウンロードを開始します",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onRetryTranscription = {
                                if (!WhisperModelManager.isReady(context)) {
                                    Toast.makeText(
                                        context,
                                        "先にWhisperモデルを準備してください",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val count = TranscriptionScheduler.enqueueExisting(context)
                                    Toast.makeText(
                                        context,
                                        "${count}件の未処理音声を確認しました",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onDeleteModel = {
                                val deleted = WhisperModelManager.deleteModel(context)
                                AppLogger.event(
                                    context,
                                    if (deleted) "UI_WHISPER_MODEL_DELETED"
                                    else "UI_WHISPER_MODEL_DELETE_FAILED"
                                )
                                Toast.makeText(
                                    context,
                                    if (deleted) "モデルを削除しました" else "モデルを削除できませんでした",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onOpenSystemSettings = onOpenSystemSettings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionIcon(section: AppSection) {
    val icon = when (section) {
        AppSection.HOME -> Icons.Filled.Home
        AppSection.HISTORY -> Icons.Filled.List
        AppSection.SETTINGS -> Icons.Filled.Settings
    }
    Icon(icon, contentDescription = null)
}

@Composable
private fun HomeScreen(
    dashboard: DashboardSnapshot,
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var showStopConfirmation by remember { mutableStateOf(false) }
    val active = dashboard.state == "STARTING" ||
        dashboard.state == "RECORDING" ||
        dashboard.state == "STOPPING"

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("録音を停止しますか？") },
            text = {
                Text("24時間録音を停止します。停止後はホーム画面からいつでも再開できます。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmation = false
                        onStop()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("停止する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            RecordingHeroCard(
                dashboard = dashboard,
                active = active,
                onRequestStart = onRequestStart,
                onRequestStop = { showStopConfirmation = true }
            )
        }
        item {
            OverviewCard(dashboard)
        }
        item {
            TranscriptionSummaryCard(
                dashboard = dashboard,
                onOpenHistory = onOpenHistory
            )
        }
        item {
            Text(
                "設計上、録音処理は文字起こしやUIとは別プロセスです。後段処理が遅れても録音継続を優先します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RecordingHeroCard(
    dashboard: DashboardSnapshot,
    active: Boolean,
    onRequestStart: () -> Unit,
    onRequestStop: () -> Unit
) {
    val recording = dashboard.state == "RECORDING"
    val statusColor = when (dashboard.state) {
        "RECORDING" -> MaterialTheme.colorScheme.primary
        "ERROR" -> MaterialTheme.colorScheme.error
        "STARTING", "STOPPING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val statusLabel = recordingStateLabel(dashboard.state)
    val heartbeatStale = recording &&
        dashboard.heartbeatMs > 0L &&
        System.currentTimeMillis() - dashboard.heartbeatMs > 10_000L

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(statusLabel, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (recording) "バックグラウンドで録音しています"
                        else "24時間録音の状態をここで管理します",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }

            if (dashboard.segmentId.isNotBlank() && dashboard.segmentId != "null") {
                Text(
                    "現在のセグメント  ${dashboard.segmentId}",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (heartbeatStale) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "録音中ですがheartbeatが10秒以上更新されていません。ログ確認を推奨します。",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (dashboard.heartbeatMs > 0L) {
                Text(
                    "最終heartbeat  ${formatDateTime(dashboard.heartbeatMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                )
            }

            if (dashboard.error.isNotBlank() && dashboard.error != "null") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        dashboard.error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (active || dashboard.recordingRequested) {
                Button(
                    onClick = onRequestStop,
                    enabled = dashboard.state != "STOPPING",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (dashboard.state == "STOPPING") "停止中…" else "録音を停止")
                }
            } else {
                Button(
                    onClick = onRequestStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("録音を開始")
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(dashboard: DashboardSnapshot) {
    val audioProgress = dashboard.audioBytes.toFloat() / StoragePolicy.AUDIO_LIMIT_BYTES.toFloat()
    val appProgress = dashboard.appBytes.toFloat() / StoragePolicy.LOGICAL_APP_LIMIT_BYTES.toFloat()

    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("ストレージ", style = MaterialTheme.typography.titleLarge)

            StorageMeter(
                label = "未処理音声",
                value = "${formatMb(dashboard.audioBytes)} / 600 MB",
                progress = audioProgress
            )
            StorageMeter(
                label = "作業データ",
                value = "${formatMb(dashboard.appBytes)} / 1 GB",
                progress = appProgress
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("未処理", "${dashboard.pendingAudio}件")
                Metric("文字起こし", "${dashboard.transcriptCount}件")
                Metric("端末空き", formatStorage(dashboard.deviceFreeBytes))
            }

            Text(
                "Whisperモデルは1GBの作業データ制限には含めません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StorageMeter(label: String, value: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TranscriptionSummaryCard(
    dashboard: DashboardSnapshot,
    onOpenHistory: () -> Unit
) {
    val modelProgress = if (WhisperModelManager.EXPECTED_BYTES > 0L) {
        dashboard.modelBytes.toFloat() / WhisperModelManager.EXPECTED_BYTES.toFloat()
    } else 0f

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("ローカル文字起こし", style = MaterialTheme.typography.titleLarge)
            Text(
                "${LocalWhisperEngine.ENGINE_ID} / Whisper base",
                style = MaterialTheme.typography.bodyMedium
            )

            if (dashboard.modelReady) {
                StatusPill("モデル準備済み", StatusTone.SUCCESS)
            } else {
                StatusPill(
                    if (dashboard.modelBytes > 0L) "モデル取得中" else "モデル未準備",
                    StatusTone.WAITING
                )
                if (dashboard.modelBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { modelProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${formatMb(dashboard.modelBytes)} / ${formatMb(WhisperModelManager.EXPECTED_BYTES)}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            FilledTonalButton(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("録音データと文字起こしを見る")
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }

    val filtered = remember(records, query, filter) {
        records.filter { record ->
            val filterMatch = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.TRANSCRIBED -> record.hasTranscript
                HistoryFilter.AUDIO -> record.audioAvailable
                HistoryFilter.ATTENTION -> record.needsAttention
            }
            val queryMatch = query.isBlank() ||
                record.segmentId.contains(query, ignoreCase = true) ||
                (record.fileName?.contains(query, ignoreCase = true) == true) ||
                (record.transcriptText?.contains(query, ignoreCase = true) == true)
            filterMatch && queryMatch
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                placeholder = { Text("文字起こし・segment IDを検索") },
                modifier = Modifier.fillMaxWidth()
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 20.dp)
            ) {
                itemsIndexed(
                    items = HistoryFilter.entries,
                    key = { _, item -> item.name }
                ) { _, item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyHistory(filter = filter, query = query)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = filtered,
                    key = { _, item -> item.segmentId }
                ) { index, record ->
                    val day = formatDay(record.sortTimeMs)
                    val previousDay = if (index > 0) formatDay(filtered[index - 1].sortTimeMs) else null
                    if (day != previousDay) {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp, bottom = 2.dp)
                        )
                    }
                    SegmentCard(record = record, onClick = { onSelect(record) })
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(filter: HistoryFilter, query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (query.isBlank() && filter == HistoryFilter.ALL) "記録はまだありません"
                else "条件に一致する記録がありません",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "録音セグメントが確定すると、ここに時刻・音声状態・文字起こし結果が表示されます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SegmentCard(record: SegmentRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatTimeRange(record),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        record.segmentId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(recordStatusLabel(record), recordTone(record))
            }

            Text(
                when {
                    record.hasTranscript && record.transcriptText!!.isNotBlank() -> record.transcriptText
                    record.hasTranscript -> "文字起こし結果は空です（無音区間の可能性があります）"
                    record.status == "TRANSCRIBING" -> "端末内で文字起こし中です"
                    record.audioAvailable -> "音声は保存済みです。文字起こしを待っています"
                    else -> "文字起こし結果はまだありません"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (record.audioAvailable) {
                        "音声あり ${SegmentHistoryRepository.formatBytes(record.fileSizeBytes)}"
                    } else if (record.hasTranscript) {
                        "音声削除済み"
                    } else {
                        "音声なし"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (record.reason != null) {
                    Text(
                        record.reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private enum class StatusTone { SUCCESS, WAITING, ERROR, NEUTRAL }

@Composable
private fun StatusPill(text: String, tone: StatusTone) {
    val background = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.WAITING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surface
    }
    val foreground = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WAITING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Surface(color = background, contentColor = foreground, shape = CircleShape) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun RecordDetailScreen(record: SegmentRecord) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                formatTimeRange(record),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                formatDay(record.sortTimeMs),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                        StatusPill(recordStatusLabel(record), recordTone(record))
                    }
                    Text(
                        "segment ${record.segmentId}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (record.audioAvailable && record.audioPath != null) {
            item { AudioPlaybackCard(record) }
        } else {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("録音データ", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (record.hasTranscript) {
                                "文字起こし結果を永続保存したため、元のM4A音声は仕様どおり削除済みです。"
                            } else {
                                "現在参照できる音声ファイルはありません。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { TranscriptCard(record) }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("詳細", style = MaterialTheme.typography.titleLarge)
                    InfoRow("状態", record.status)
                    InfoRow("開始", formatDateTime(record.startedAtMs))
                    InfoRow("終了", formatDateTime(record.endedAtMs))
                    InfoRow(
                        "長さ",
                        if (record.durationMs > 0L) formatDuration(record.durationMs) else "-"
                    )
                    InfoRow("ファイル", record.fileName ?: "-")
                    InfoRow("サイズ", SegmentHistoryRepository.formatBytes(record.fileSizeBytes))
                    InfoRow("文字起こし", record.transcriptModel?.ifBlank { "-" } ?: "-")
                    if (record.transcribedAtMs > 0L) {
                        InfoRow("処理日時", formatDateTime(record.transcribedAtMs))
                    }
                    if (!record.reason.isNullOrBlank()) {
                        InfoRow("理由", record.reason)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlaybackCard(record: SegmentRecord) {
    val context = LocalContext.current
    var player by remember(record.audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(record.audioPath) { mutableStateOf(false) }
    var preparing by remember(record.audioPath) { mutableStateOf(false) }

    DisposableEffect(record.audioPath) {
        onDispose {
            try {
                player?.stop()
            } catch (_: Exception) {
            }
            player?.release()
            player = null
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("録音データ", style = MaterialTheme.typography.titleLarge)
            Text(
                "${record.fileName ?: "M4A"}  •  ${SegmentHistoryRepository.formatBytes(record.fileSizeBytes)}",
                style = MaterialTheme.typography.bodyMedium
            )

            FilledTonalButton(
                onClick = {
                    val existing = player
                    if (existing == null) {
                        try {
                            val created = MediaPlayer()
                            player = created
                            preparing = true
                            created.setDataSource(record.audioPath)
                            created.setOnPreparedListener {
                                preparing = false
                                it.start()
                                isPlaying = true
                            }
                            created.setOnCompletionListener {
                                isPlaying = false
                            }
                            created.setOnErrorListener { mp, _, _ ->
                                preparing = false
                                isPlaying = false
                                mp.release()
                                player = null
                                Toast.makeText(
                                    context,
                                    "音声を再生できませんでした",
                                    Toast.LENGTH_SHORT
                                ).show()
                                true
                            }
                            created.prepareAsync()
                        } catch (_: Exception) {
                            preparing = false
                            player?.release()
                            player = null
                            Toast.makeText(
                                context,
                                "音声を再生できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        try {
                            if (existing.isPlaying) {
                                existing.pause()
                                isPlaying = false
                            } else {
                                existing.start()
                                isPlaying = true
                            }
                        } catch (_: Exception) {
                            isPlaying = false
                        }
                    }
                },
                enabled = !preparing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        preparing -> "準備中…"
                        isPlaying -> "一時停止"
                        else -> "音声を再生"
                    }
                )
            }
        }
    }
}

@Composable
private fun TranscriptCard(record: SegmentRecord) {
    val context = LocalContext.current
    val transcript = record.transcriptText

    Card {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "文字起こし",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (transcript != null) {
                    TextButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("24hRecoder 文字起こし", transcript)
                            )
                            Toast.makeText(context, "文字起こしをコピーしました", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("コピー")
                    }
                }
            }

            if (transcript == null) {
                Text(
                    when (record.status) {
                        "TRANSCRIBING" -> "文字起こし処理中です。"
                        "FAILED" -> "文字起こしに失敗しました。音声が残っている場合は設定画面から再登録できます。"
                        else -> "文字起こし結果はまだありません。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Text(
                        if (transcript.isBlank()) "（文字起こし結果は空です）" else transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 26.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsScreen(
    dashboard: DashboardSnapshot,
    onDownloadModel: () -> Unit,
    onRetryTranscription: () -> Unit,
    onDeleteModel: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val modelProgress = dashboard.modelBytes.toFloat() /
        WhisperModelManager.EXPECTED_BYTES.toFloat()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Whisperモデル", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "base / 多言語 / ${formatMb(WhisperModelManager.EXPECTED_BYTES)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    StatusPill(
                        if (dashboard.modelReady) "準備済み"
                        else if (dashboard.modelBytes > 0L) "取得中"
                        else "未準備",
                        if (dashboard.modelReady) StatusTone.SUCCESS else StatusTone.WAITING
                    )

                    if (!dashboard.modelReady && dashboard.modelBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { modelProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (!dashboard.modelReady) {
                        Button(
                            onClick = onDownloadModel,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Whisper baseモデルをダウンロード")
                        }
                    }

                    OutlinedButton(
                        onClick = onRetryTranscription,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("未処理音声を文字起こしへ再登録")
                    }

                    if (dashboard.modelBytes > 0L) {
                        OutlinedButton(
                            onClick = onDeleteModel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Whisperモデルを削除")
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("プライバシー", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "録音音声と文字起こし処理は端末内で完結します。外部APIへ音声を送信しません。初回のWhisperモデル取得時のみネット接続を使用します。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "モデル本体は1GBの作業データ制限の対象外です。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Android設定", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "マイク権限、通知権限、バッテリー設定などはAndroidのアプリ情報画面で確認できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onOpenSystemSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("アプリ設定を開く")
                    }
                }
            }
        }

        item {
            Text(
                "端末再起動後はAndroidの制約により録音を自動開始しません。再起動後はホームから録音を再開してください。\n\n24hRecoder ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

private fun readDashboard(context: Context): DashboardSnapshot {
    val state: JSONObject = RecorderStateStore.read(context)
    return DashboardSnapshot(
        state = state.optString("state", "STOPPED"),
        heartbeatMs = state.optLong("heartbeatMs", 0L),
        segmentId = state.optString("segmentId", ""),
        error = state.optString("error", ""),
        recordingRequested = RecordingIntentStore.isRequested(context),
        audioBytes = StoragePolicy.audioBytes(context),
        appBytes = StoragePolicy.appDataBytes(context),
        deviceFreeBytes = context.filesDir.usableSpace,
        modelReady = WhisperModelManager.isReady(context),
        modelBytes = WhisperModelManager.downloadedBytes(context),
        pendingAudio = TranscriptionScheduler.pendingAudioCount(context),
        transcriptCount = TranscriptionRepository.count(context)
    )
}

private fun recordStatusLabel(record: SegmentRecord): String {
    if (record.hasTranscript) return "文字起こし済み"
    return when (record.status) {
        "TRANSCRIBING" -> "文字起こし中"
        "RETRY_WAIT" -> "再試行待ち"
        "FAILED" -> "失敗"
        "CORRUPT" -> "破損"
        "READY" -> "待機中"
        "DELETED" -> "削除済み"
        else -> record.status.lowercase().replaceFirstChar { it.titlecase() }
    }
}

private fun recordTone(record: SegmentRecord): StatusTone {
    return when {
        record.hasTranscript -> StatusTone.SUCCESS
        record.status == "FAILED" || record.status == "CORRUPT" -> StatusTone.ERROR
        record.status == "TRANSCRIBING" ||
            record.status == "RETRY_WAIT" ||
            record.status == "READY" -> StatusTone.WAITING
        else -> StatusTone.NEUTRAL
    }
}

private fun recordingStateLabel(state: String): String {
    return when (state) {
        "RECORDING" -> "録音中"
        "STARTING" -> "録音を開始中"
        "STOPPING" -> "録音を停止中"
        "ERROR" -> "録音エラー"
        else -> "録音停止中"
    }
}

private fun formatTimeRange(record: SegmentRecord): String {
    if (record.startedAtMs <= 0L) return "時刻不明"
    val start = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(record.startedAtMs))
    if (record.endedAtMs <= 0L) return start
    val end = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(record.endedAtMs))
    return "$start – $end"
}

private fun formatDay(millis: Long): String {
    if (millis <= 0L) return "日時不明"
    return SimpleDateFormat("M月d日 (E)", Locale.JAPAN).format(Date(millis))
}

private fun formatDateTime(millis: Long): String {
    if (millis <= 0L) return "-"
    return DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.MEDIUM,
        Locale.JAPAN
    ).format(Date(millis))
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "${minutes}分${seconds}秒" else "${seconds}秒"
}

private fun formatMb(bytes: Long): String {
    return String.format(Locale.JAPAN, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun formatStorage(bytes: Long): String {
    if (bytes <= 0L) return "-"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) {
        String.format(Locale.JAPAN, "%.1f GB", gb)
    } else {
        "${(bytes / 1024.0 / 1024.0).roundToInt()} MB"
    }
}
