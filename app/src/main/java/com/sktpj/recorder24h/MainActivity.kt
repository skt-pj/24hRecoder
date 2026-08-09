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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
                    onRequestStart = ::requestStart,
                    onStop = ::stopRecording,
                    onOpenSystemSettings = ::openSystemSettings
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
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
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        RecordingIntentStore.setRequested(this, true)
        RecorderStateStore.write(this, "STARTING", null, null)
        try {
            startForegroundService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            )
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
        try {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
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
    HOME("ホーム"), HISTORY("記録"), SETTINGS("設定")
}

private enum class HistoryFilter(val label: String) {
    ALL("すべて"), TRANSCRIBED("文字起こし済み"), AUDIO("音声あり"), ATTENTION("要確認")
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
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderApp(
    onRequestStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    val context = LocalContext.current
    var section by remember { mutableStateOf(AppSection.HOME) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<SegmentRecord>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var dashboard by remember { mutableStateOf(readDashboard(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            dashboard = withContext(Dispatchers.IO) { readDashboard(context) }
            delay(2_000L)
        }
    }
    LaunchedEffect(section, refresh) {
        if (section == AppSection.HISTORY) {
            do {
                records = withContext(Dispatchers.IO) { SegmentHistoryRepository.load(context) }
                delay(5_000L)
            } while (section == AppSection.HISTORY)
        }
    }

    val selected = selectedId?.let { id -> records.firstOrNull { it.segmentId == id } }
    BackHandler(enabled = selected != null) { selectedId = null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (selected != null) "記録の詳細" else sectionTitle(section))
                            if (section == AppSection.HOME && selected == null) {
                                Text(
                                    "端末内録音・ローカル文字起こし",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (selected != null) {
                            IconButton(onClick = { selectedId = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "記録一覧へ戻る")
                            }
                        }
                    },
                    actions = {
                        if (section == AppSection.HISTORY && selected == null) {
                            IconButton(onClick = { refresh++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "記録を更新")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!expanded && selected == null) {
                    NavigationBar {
                        AppSection.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = section == destination,
                                onClick = { section = destination; selectedId = null },
                                icon = { SectionIcon(destination) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (expanded && selected == null) {
                    NavigationRail(Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(8.dp))
                        AppSection.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = section == destination,
                                onClick = { section = destination; selectedId = null },
                                icon = { SectionIcon(destination) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        selected != null -> RecordDetailScreen(selected)
                        section == AppSection.HOME -> HomeScreen(
                            dashboard,
                            onRequestStart,
                            onStop,
                            onOpenHistory = { section = AppSection.HISTORY; refresh++ }
                        )
                        section == AppSection.HISTORY -> HistoryScreen(records) { selectedId = it.segmentId }
                        else -> SettingsScreen(
                            dashboard,
                            onDownloadModel = {
                                WhisperModelManager.enqueueDownload(context)
                                AppLogger.event(context, "UI_WHISPER_MODEL_DOWNLOAD_REQUESTED")
                                Toast.makeText(context, "Whisper large-v3 Q5モデルのダウンロードを開始します", Toast.LENGTH_SHORT).show()
                            },
                            onRetryTranscription = {
                                if (!WhisperModelManager.isReady(context)) {
                                    Toast.makeText(context, "先にWhisperモデルを準備してください", Toast.LENGTH_SHORT).show()
                                } else {
                                    val count = TranscriptionScheduler.enqueueExisting(context)
                                    Toast.makeText(context, "${count}件の未処理音声を確認しました", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDeleteModel = {
                                val ok = WhisperModelManager.deleteModel(context)
                                AppLogger.event(context, if (ok) "UI_WHISPER_MODEL_DELETED" else "UI_WHISPER_MODEL_DELETE_FAILED")
                                Toast.makeText(context, if (ok) "モデルを削除しました" else "モデルを削除できませんでした", Toast.LENGTH_SHORT).show()
                            },
                            onOpenSystemSettings = onOpenSystemSettings
                        )
                    }
                }
            }
        }
    }
}

private fun sectionTitle(section: AppSection) = when (section) {
    AppSection.HOME -> "24hRecoder"
    AppSection.HISTORY -> "記録"
    AppSection.SETTINGS -> "設定"
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
    var confirmStop by remember { mutableStateOf(false) }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("録音を停止しますか？") },
            text = { Text("24時間録音を停止します。停止後はホームから再開できます。") },
            confirmButton = {
                Button(
                    onClick = { confirmStop = false; onStop() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("停止する") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("キャンセル") } }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            RecordingCard(
                dashboard,
                onRequestStart,
                onRequestStop = { confirmStop = true }
            )
        }
        item { StorageCard(dashboard) }
        item { TranscriptionCard(dashboard, onOpenHistory) }
        item {
            Text(
                "録音処理はUI・文字起こしとは別プロセスです。後段処理が遅れても録音継続を優先します。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
private fun RecordingCard(
    dashboard: DashboardSnapshot,
    onRequestStart: () -> Unit,
    onRequestStop: () -> Unit
) {
    val active = dashboard.state in setOf("STARTING", "RECORDING", "STOPPING") || dashboard.recordingRequested
    val stale = dashboard.state == "RECORDING" && dashboard.heartbeatMs > 0L &&
        System.currentTimeMillis() - dashboard.heartbeatMs > 10_000L
    val dotColor = when (dashboard.state) {
        "RECORDING" -> MaterialTheme.colorScheme.primary
        "ERROR" -> MaterialTheme.colorScheme.error
        "STARTING", "STOPPING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(recordingStateLabel(dashboard.state), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (dashboard.state == "RECORDING") "バックグラウンドで録音しています" else "24時間録音の状態を管理します",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f)
                    )
                }
            }
            if (dashboard.segmentId.isNotBlank() && dashboard.segmentId != "null") {
                Text("現在のセグメント  ${dashboard.segmentId}", style = MaterialTheme.typography.labelLarge)
            }
            if (stale) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "heartbeatが10秒以上更新されていません。ログ確認を推奨します。",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else if (dashboard.heartbeatMs > 0L) {
                Text("最終heartbeat  ${formatDateTime(dashboard.heartbeatMs)}", style = MaterialTheme.typography.bodySmall)
            }
            if (dashboard.error.isNotBlank() && dashboard.error != "null") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(dashboard.error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
            if (active) {
                Button(
                    onClick = onRequestStop,
                    enabled = dashboard.state != "STOPPING",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text(if (dashboard.state == "STOPPING") "停止中…" else "録音を停止") }
            } else {
                Button(onClick = onRequestStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("録音を開始")
                }
            }
        }
    }
}

@Composable
private fun StorageCard(dashboard: DashboardSnapshot) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ストレージ", style = MaterialTheme.typography.titleLarge)
            Meter("未処理音声", "${formatMb(dashboard.audioBytes)} / 600 MB", dashboard.audioBytes.toFloat() / StoragePolicy.AUDIO_LIMIT_BYTES)
            Meter("作業データ", "${formatMb(dashboard.appBytes)} / 1 GB", dashboard.appBytes.toFloat() / StoragePolicy.LOGICAL_APP_LIMIT_BYTES)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
private fun Meter(label: String, value: String, progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TranscriptionCard(dashboard: DashboardSnapshot, onOpenHistory: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ローカル文字起こし", style = MaterialTheme.typography.titleLarge)
            Text("${LocalWhisperEngine.ENGINE_ID} / Whisper base")
            StatusPill(
                if (dashboard.modelReady) "モデル準備済み" else if (dashboard.modelBytes > 0L) "モデル取得中" else "モデル未準備",
                if (dashboard.modelReady) StatusTone.SUCCESS else StatusTone.WAITING
            )
            if (!dashboard.modelReady && dashboard.modelBytes > 0L) {
                LinearProgressIndicator(
                    progress = { (dashboard.modelBytes.toFloat() / WhisperModelManager.EXPECTED_BYTES).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            FilledTonalButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("録音データと文字起こしを見る")
            }
        }
    }
}

@Composable
private fun HistoryScreen(records: List<SegmentRecord>, onSelect: (SegmentRecord) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val filtered = remember(records, query, filter) {
        records.filter { record ->
            val statusOk = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.TRANSCRIBED -> record.hasTranscript
                HistoryFilter.AUDIO -> record.audioAvailable
                HistoryFilter.ATTENTION -> record.needsAttention
            }
            val queryOk = query.isBlank() ||
                record.segmentId.contains(query, true) ||
                record.fileName?.contains(query, true) == true ||
                record.transcriptText?.contains(query, true) == true
            statusOk && queryOk
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("文字起こし・segment IDを検索") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(HistoryFilter.entries) { _, item ->
                    FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.label) })
                }
            }
        }
        if (filtered.isEmpty()) {
            EmptyHistory(query, filter)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(filtered, key = { _, item -> item.segmentId }) { index, record ->
                    val day = formatDay(record.sortTimeMs)
                    val previous = if (index == 0) null else formatDay(filtered[index - 1].sortTimeMs)
                    if (day != previous) {
                        Text(day, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp))
                    }
                    SegmentCard(record) { onSelect(record) }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(query: String, filter: HistoryFilter) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (query.isBlank() && filter == HistoryFilter.ALL) "記録はまだありません" else "条件に一致する記録がありません",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "録音セグメントが確定すると、時刻・音声状態・文字起こし結果が表示されます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SegmentCard(record: SegmentRecord, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatTimeRange(record), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(record.segmentId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(recordStatusLabel(record), recordTone(record))
            }
            Text(
                when {
                    record.hasTranscript && !record.transcriptText.isNullOrBlank() -> record.transcriptText!!
                    record.hasTranscript -> "文字起こし結果は空です（無音区間の可能性があります）"
                    record.status == "TRANSCRIBING" -> "端末内で文字起こし中です"
                    record.audioAvailable -> "音声は保存済みです。文字起こしを待っています"
                    else -> "文字起こし結果はまだありません"
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        record.audioAvailable -> "音声あり ${SegmentHistoryRepository.formatBytes(record.fileSizeBytes)}"
                        record.hasTranscript -> "音声削除済み"
                        else -> "音声なし"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!record.reason.isNullOrBlank()) {
                    Text(record.reason, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 1)
                }
            }
        }
    }
}

private enum class StatusTone { SUCCESS, WAITING, ERROR, NEUTRAL }

@Composable
private fun StatusPill(text: String, tone: StatusTone) {
    val bg = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.WAITING -> MaterialTheme.colorScheme.tertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surface
    }
    val fg = when (tone) {
        StatusTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.WAITING -> MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Surface(color = bg, contentColor = fg, shape = CircleShape) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun RecordDetailScreen(record: SegmentRecord) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatTimeRange(record), style = MaterialTheme.typography.headlineMedium)
                            Text(formatDay(record.sortTimeMs), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                        }
                        StatusPill(recordStatusLabel(record), recordTone(record))
                    }
                    Text("segment ${record.segmentId}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            if (record.audioAvailable && record.audioPath != null) {
                AudioPlaybackCard(record)
            } else {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("録音データ", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (record.hasTranscript) "文字起こし結果を永続保存したため、元のM4A音声は削除済みです。" else "現在参照できる音声ファイルはありません。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item { TranscriptCard(record) }
        item { ModelComparisonCard(record) }
        item {
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("詳細", style = MaterialTheme.typography.titleLarge)
                    InfoRow("状態", record.status)
                    InfoRow("開始", formatDateTime(record.startedAtMs))
                    InfoRow("終了", formatDateTime(record.endedAtMs))
                    InfoRow("長さ", if (record.durationMs > 0L) formatDuration(record.durationMs) else "-")
                    InfoRow("ファイル", record.fileName ?: "-")
                    InfoRow("サイズ", SegmentHistoryRepository.formatBytes(record.fileSizeBytes))
                    InfoRow("文字起こし", record.transcriptModel?.ifBlank { "-" } ?: "-")
                    if (record.transcribedAtMs > 0L) InfoRow("処理日時", formatDateTime(record.transcribedAtMs))
                    if (!record.reason.isNullOrBlank()) InfoRow("理由", record.reason)
                }
            }
        }
    }
}

@Composable
private fun AudioPlaybackCard(record: SegmentRecord) {
    val context = LocalContext.current
    var player by remember(record.audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(record.audioPath) { mutableStateOf(false) }
    var preparing by remember(record.audioPath) { mutableStateOf(false) }

    DisposableEffect(record.audioPath) {
        onDispose {
            try { player?.stop() } catch (_: Exception) { }
            player?.release()
            player = null
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("録音データ", style = MaterialTheme.typography.titleLarge)
            Text("${record.fileName ?: "M4A"}  •  ${SegmentHistoryRepository.formatBytes(record.fileSizeBytes)}")
            FilledTonalButton(
                onClick = {
                    val current = player
                    if (current == null) {
                        try {
                            val created = MediaPlayer()
                            player = created
                            preparing = true
                            created.setDataSource(record.audioPath)
                            created.setOnPreparedListener { preparing = false; it.start(); playing = true }
                            created.setOnCompletionListener { playing = false }
                            created.setOnErrorListener { mp, _, _ ->
                                preparing = false
                                playing = false
                                mp.release()
                                player = null
                                Toast.makeText(context, "音声を再生できませんでした", Toast.LENGTH_SHORT).show()
                                true
                            }
                            created.prepareAsync()
                        } catch (_: Exception) {
                            preparing = false
                            player?.release()
                            player = null
                            Toast.makeText(context, "音声を再生できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        try {
                            if (current.isPlaying) { current.pause(); playing = false }
                            else { current.start(); playing = true }
                        } catch (_: Exception) { playing = false }
                    }
                },
                enabled = !preparing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!playing && !preparing) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (preparing) "準備中…" else if (playing) "一時停止" else "音声を再生")
            }
        }
    }
}

@Composable
private fun TranscriptCard(record: SegmentRecord) {
    val context = LocalContext.current
    val text = record.transcriptText
    var confirmRetranscribe by remember(record.segmentId) { mutableStateOf(false) }
    val retranscriptionActive = record.hasTranscript && record.status in setOf("QUEUED", "TRANSCRIBING", "RETRY_WAIT")
    val canRetranscribe = record.audioAvailable && record.audioPath != null

    if (confirmRetranscribe) {
        AlertDialog(
            onDismissRequest = { confirmRetranscribe = false },
            title = { Text("この音声を再文字起こししますか？") },
            text = {
                Text("保存済みの音声を現在のWhisper + VAD設定で再処理します。現在の文字起こしは、新しい結果の保存に成功するまで保持します。")
            },
            confirmButton = {
                Button(onClick = {
                    confirmRetranscribe = false
                    val audioPath = record.audioPath
                    val queued = audioPath != null && TranscriptionScheduler.enqueueForceRetranscription(
                        context,
                        record.segmentId,
                        java.io.File(audioPath)
                    )
                    if (queued) {
                        AppLogger.event(context, "UI_MANUAL_RETRANSCRIPTION_REQUESTED")
                        Toast.makeText(context, "再文字起こしを登録しました", Toast.LENGTH_SHORT).show()
                    } else if (!WhisperModelManager.isReady(context)) {
                        Toast.makeText(context, "モデルを準備中です。準備後にもう一度実行してください", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "再文字起こしを登録できませんでした", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("再文字起こし") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetranscribe = false }) { Text("キャンセル") }
            }
        )
    }

    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文字起こし", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (text != null) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("24hRecoder 文字起こし", text))
                        Toast.makeText(context, "文字起こしをコピーしました", Toast.LENGTH_SHORT).show()
                    }) { Text("コピー") }
                }
            }
            if (text == null) {
                Text(
                    when (record.status) {
                        "TRANSCRIBING" -> "文字起こし処理中です。"
                        "FAILED" -> "文字起こしに失敗しました。元音声が残っていればこの画面から再実行できます。"
                        else -> "文字起こし結果はまだありません。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (retranscriptionActive) {
                    Text(
                        "再文字起こし中も現在の結果を表示しています。新しい結果が正常保存された時点で置き換わります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SelectionContainer {
                    Text(if (text.isBlank()) "（文字起こし結果は空です）" else text, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
                }
            }

            if (canRetranscribe) {
                FilledTonalButton(
                    onClick = { confirmRetranscribe = true },
                    enabled = record.status !in setOf("QUEUED", "TRANSCRIBING", "RETRY_WAIT"),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (retranscriptionActive) "再文字起こし中…" else "この音声を再文字起こし")
                }
                Text(
                    "処理済みの文字起こしでも、元M4Aが残っていれば何度でも再実行できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (record.hasTranscript) {
                Text(
                    "元音声が削除済みのため、この記録は再文字起こしできません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(value, modifier = Modifier.weight(1f))
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
    val context = LocalContext.current
    val versionName = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.4.8-debug" }
        catch (_: Exception) { "0.4.8-debug" }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Whisperモデル", style = MaterialTheme.typography.titleLarge)
                    Text("large-v3 Q5 / 多言語 / デフォルト / ${formatMb(WhisperModelManager.EXPECTED_BYTES)}")
                    StatusPill(
                        if (dashboard.modelReady) "準備済み" else if (dashboard.modelBytes > 0L) "取得中" else "未準備",
                        if (dashboard.modelReady) StatusTone.SUCCESS else StatusTone.WAITING
                    )
                    if (!dashboard.modelReady && dashboard.modelBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { (dashboard.modelBytes.toFloat() / WhisperModelManager.EXPECTED_BYTES).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (!dashboard.modelReady) {
                        Button(onClick = onDownloadModel, modifier = Modifier.fillMaxWidth()) { Text("Whisper large-v3 Q5モデルをダウンロード") }
                    }
                    OutlinedButton(onClick = onRetryTranscription, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("未処理・旧方式の音声を再登録")
                    }
                    if (dashboard.modelBytes > 0L) {
                        OutlinedButton(
                            onClick = onDeleteModel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
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
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("プライバシー", style = MaterialTheme.typography.titleLarge)
                    Text("録音音声と文字起こしは端末内で完結します。外部APIへ音声を送信しません。初回のWhisperモデル取得時のみネット接続を使用します。")
                    Text("モデル本体は1GBの作業データ制限の対象外です。", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Android設定", style = MaterialTheme.typography.titleLarge)
                    Text("マイク権限、通知権限、バッテリー設定などをAndroidのアプリ情報画面で確認できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenSystemSettings, modifier = Modifier.fillMaxWidth()) { Text("アプリ設定を開く") }
                }
            }
        }
        item {
            Text(
                "端末再起動後はAndroidの制約により録音を自動開始しません。再起動後はホームから録音を再開してください。\n\n24hRecoder $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp)
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
    if (record.hasTranscript) {
        return when (record.status) {
            "QUEUED", "READY" -> "再文字起こし待ち"
            "TRANSCRIBING" -> "再文字起こし中"
            "RETRY_WAIT" -> "再文字起こし再試行待ち"
            "FAILED" -> "再文字起こし失敗"
            else -> "文字起こし済み"
        }
    }
    return when (record.status) {
        "QUEUED", "READY" -> "待機中"
        "TRANSCRIBING" -> "文字起こし中"
        "RETRY_WAIT" -> "再試行待ち"
        "FAILED" -> "失敗"
        "CORRUPT" -> "破損"
        "DELETED" -> "削除済み"
        else -> record.status
    }
}

private fun recordTone(record: SegmentRecord) = when {
    record.hasTranscript && record.status in setOf("QUEUED", "READY", "TRANSCRIBING", "RETRY_WAIT") -> StatusTone.WAITING
    record.hasTranscript && record.status == "FAILED" -> StatusTone.ERROR
    record.hasTranscript -> StatusTone.SUCCESS
    record.status == "FAILED" || record.status == "CORRUPT" -> StatusTone.ERROR
    record.status == "QUEUED" || record.status == "TRANSCRIBING" || record.status == "RETRY_WAIT" || record.status == "READY" -> StatusTone.WAITING
    else -> StatusTone.NEUTRAL
}

private fun recordingStateLabel(state: String) = when (state) {
    "RECORDING" -> "録音中"
    "STARTING" -> "録音を開始中"
    "STOPPING" -> "録音を停止中"
    "ERROR" -> "録音エラー"
    else -> "録音停止中"
}

private fun formatTimeRange(record: SegmentRecord): String {
    if (record.startedAtMs <= 0L) return "時刻不明"
    val formatter = SimpleDateFormat("HH:mm", Locale.JAPAN)
    val start = formatter.format(Date(record.startedAtMs))
    val end = if (record.endedAtMs > 0L) formatter.format(Date(record.endedAtMs)) else null
    return if (end == null) start else "$start – $end"
}

private fun formatDay(millis: Long): String =
    if (millis <= 0L) "日時不明" else SimpleDateFormat("M月d日 (E)", Locale.JAPAN).format(Date(millis))

private fun formatDateTime(millis: Long): String =
    if (millis <= 0L) "-" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.JAPAN).format(Date(millis))

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    return if (seconds >= 60L) "${seconds / 60L}分${seconds % 60L}秒" else "${seconds}秒"
}

private fun formatMb(bytes: Long) = String.format(Locale.JAPAN, "%.1f MB", bytes / 1024.0 / 1024.0)

private fun formatStorage(bytes: Long): String {
    if (bytes <= 0L) return "-"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) String.format(Locale.JAPAN, "%.1f GB", gb)
    else "${(bytes / 1024.0 / 1024.0).roundToInt()} MB"
}
