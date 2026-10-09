package com.sktpj.recorder24h

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.memoket.MemoketSettings
import com.sktpj.recorder24h.memoket.MemoketTestEngine
import com.sktpj.recorder24h.memoket.MemoketTestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoketTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MemoketTestTheme { MemoketTestApp(onClose = { finish() }) } }
    }
}

private enum class MemoketTestScreen { MENU, START_CASES, STOP_CASES, FILE_CASES, RUNNING, RESULT, HISTORY }
private data class TestCaseUi(val id: String, val title: String, val subtitle: String)
private data class TestStepUi(val title: String, val detail: String, val observeVibration: Boolean)

private val startCases = listOf(
    TestCaseUi("START_CURRENT", "現在の実装（標準）", "DATA + RESPONSE のみ。現在の24hRecoder相当"),
    TestCaseUi("START_OFFICIAL", "公式アプリ相当", "0036 / RESPONSE / DATA / 0039 を公式HCI順で設定"),
    TestCaseUi("START_DATA_ONLY", "DATA通知のみ", "認証後、03送信時はDATA通知だけを有効"),
    TestCaseUi("START_RESPONSE_ONLY", "RESPONSE通知のみ", "認証後、03送信時はRESPONSE通知だけを有効"),
    TestCaseUi("START_EXTRA5", "追加通知あり（0036）", "DATA + RESPONSE + c305/CCCD 0036"),
    TestCaseUi("START_EXTRA56", "追加通知あり（0036 + 0039）", "DATA + RESPONSE + c305 + c306"),
    TestCaseUi("START_NONE", "通知なし（最小構成）", "認証後にDATA/RESPONSEをOFFにして03を送信")
)

private val stopCases = listOf(
    TestCaseUi("STOP_A", "通知OFFのみ（A）", "DATA通知をOFFにするだけ"),
    TestCaseUi("STOP_B", "通知ONのみ（B）", "DATA通知をONにするだけ"),
    TestCaseUi("STOP_C", "01 00 00 のみ（C）", "停止候補の一覧要求だけを送信"),
    TestCaseUi("STOP_AB", "OFF → ON（A→B）", "DATA通知OFF → DATA通知ON"),
    TestCaseUi("STOP_AC", "OFF → 01 00 00（A→C）", "DATA通知OFF → 一覧要求"),
    TestCaseUi("STOP_BC", "ON → 01 00 00（B→C）", "DATA通知ON → 一覧要求"),
    TestCaseUi("STOP_ABC", "OFF → ON → 01 00 00（A→B→C）", "現在の24hRecoder停止系列"),
    TestCaseUi("STOP_OFFICIAL_TIMING", "公式アプリ相当（待機あり）", "OFF → 400ms → ON → 270ms → 01 00 00")
)

private val fileCases = listOf(
    TestCaseUi("FILE_ONE", "最新側から1件取得", "Gemの未取得ファイルを1件だけ取得"),
    TestCaseUi("FILE_THREE", "最大3件取得", "Gemの未取得ファイルを順に3件まで取得"),
    TestCaseUi("FILE_SPECIFIC", "指定ファイルまで取得", "指定名に到達するまで順に取得・ACKする"),
    TestCaseUi("FILE_LIST", "ファイル一覧のみ", "先頭の未取得ファイル名だけ確認。ダウンロードしない")
)

@Composable
private fun MemoketTestTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoketTestApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var screen by remember { mutableStateOf(MemoketTestScreen.MENU) }
    var selectedCase by remember { mutableStateOf(startCases.first()) }
    var specificFile by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(emptyList<TestStepUi>()) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf(emptyList<JSONObject>()) }
    var vibration by remember { mutableStateOf(-1) }

    fun refreshHistory() { history = jsonObjects(MemoketTestStore.history(context)) }

    fun runSelected() {
        val remoteState = MemoketSettings.remoteRecordingState(context)
        if (remoteState == "録音中" || remoteState == "接続中" || remoteState == "停止処理中") {
            result = JSONObject()
                .put("id", "local-" + System.currentTimeMillis())
                .put("caseId", selectedCase.id)
                .put("status", "FAILED")
                .put("error", "通常のGem録音が動作中です。通常録音を停止してからテストしてください。")
            vibration = -1
            screen = MemoketTestScreen.RESULT
            return
        }
        progress = emptyList()
        result = null
        vibration = -1
        screen = MemoketTestScreen.RUNNING
        scope.launch {
            val finished = withContext(Dispatchers.IO) {
                MemoketTestEngine.run(
                    context,
                    selectedCase.id,
                    specificFile,
                    MemoketTestEngine.ProgressListener { title, detail, observe ->
                        mainHandler.post { progress = progress + TestStepUi(title, detail, observe) }
                    }
                )
            }
            result = finished
            vibration = finished.optInt("vibration", -1)
            refreshHistory()
            screen = MemoketTestScreen.RESULT
        }
    }

    BackHandler {
        when (screen) {
            MemoketTestScreen.MENU -> onClose()
            MemoketTestScreen.RUNNING -> Unit
            else -> screen = MemoketTestScreen.MENU
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle(screen)) },
                navigationIcon = {
                    IconButton(
                        enabled = screen != MemoketTestScreen.RUNNING,
                        onClick = { if (screen == MemoketTestScreen.MENU) onClose() else screen = MemoketTestScreen.MENU }
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                }
            )
        }
    ) { padding ->
        when (screen) {
            MemoketTestScreen.MENU -> TestMenu(
                Modifier.padding(padding),
                onStart = { selectedCase = startCases.first(); screen = MemoketTestScreen.START_CASES },
                onStop = { selectedCase = stopCases.first(); screen = MemoketTestScreen.STOP_CASES },
                onFile = { selectedCase = fileCases.first(); screen = MemoketTestScreen.FILE_CASES },
                onHistory = { refreshHistory(); screen = MemoketTestScreen.HISTORY }
            )
            MemoketTestScreen.START_CASES -> CaseSelection(
                Modifier.padding(padding),
                "録音開始時の振動と、実際に約5秒の録音ファイルが生成されるかを調査します。安全停止時の振動は開始時の回答に含めません。",
                startCases, selectedCase, { selectedCase = it }, ::runSelected
            )
            MemoketTestScreen.STOP_CASES -> CaseSelection(
                Modifier.padding(padding),
                "各ケースで自動的に5秒録音してから停止候補を実行します。3秒後に安全停止して録音時間を比較し、候補操作で止まったかを推定します。",
                stopCases, selectedCase, { selectedCase = it }, ::runSelected
            )
            MemoketTestScreen.FILE_CASES -> FileCaseSelection(
                Modifier.padding(padding), selectedCase, specificFile,
                { specificFile = it }, { selectedCase = it }, ::runSelected
            )
            MemoketTestScreen.RUNNING -> RunningScreen(Modifier.padding(padding), selectedCase, progress)
            MemoketTestScreen.RESULT -> ResultScreen(
                Modifier.padding(padding), selectedCase, result, vibration,
                onVibration = { value ->
                    vibration = value
                    val id = result?.optString("id").orEmpty()
                    if (id.isNotEmpty() && !id.startsWith("local-")) {
                        MemoketTestStore.updateVibration(context, id, value)
                        try { result?.put("vibration", value) } catch (_: Exception) { }
                    }
                },
                onHistory = { refreshHistory(); screen = MemoketTestScreen.HISTORY },
                onMenu = { screen = MemoketTestScreen.MENU }
            )
            MemoketTestScreen.HISTORY -> HistoryScreen(Modifier.padding(padding), history)
        }
    }
}

@Composable
private fun TestMenu(modifier: Modifier, onStart: () -> Unit, onStop: () -> Unit, onFile: () -> Unit, onHistory: () -> Unit) {
    val context = LocalContext.current
    val address = MemoketSettings.address(context)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Gemの録音・停止・振動の因果関係を調べる診断画面です。各テストは1件ずつ実行してください。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("接続中のデバイス", fontWeight = FontWeight.SemiBold)
                    Text("Memoket Gem")
                    Text(if (address.isEmpty()) "未選択" else address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { MenuCard("録音開始テスト", "開始時の振動と録音成立を調査", onStart) }
        item { MenuCard("録音停止テスト", "停止時の振動とファイル確定を調査", onStop) }
        item { MenuCard("ファイル取得テスト", "一覧・メタデータ・ダウンロードを確認", onFile) }
        item { MenuCard("テスト履歴", "実行ケース、振動回数、録音時間、結果を確認", onHistory) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("停止・取得テストはGem上の録音を確定し、取得成功時はACKを送ります。未取得ファイルをGemに残したい場合は実行しないでください。",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("開く") }
        }
    }
}

@Composable
private fun CaseSelection(modifier: Modifier, intro: String, cases: List<TestCaseUi>, selected: TestCaseUi, onSelect: (TestCaseUi) -> Unit, onRun: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(intro, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(cases) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = if (selected.id == item.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = selected.id == item.id, onClick = { onSelect(item) })
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("このケースで実行") } }
    }
}

@Composable
private fun FileCaseSelection(modifier: Modifier, selected: TestCaseUi, specificFile: String, onSpecificFileChange: (String) -> Unit, onSelect: (TestCaseUi) -> Unit, onRun: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Gemの未取得ファイルに対する一覧・取得経路を確認します。取得ケースでは成功後にACKを送ります。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(fileCases) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = if (selected.id == item.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = selected.id == item.id, onClick = { onSelect(item) })
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (selected.id == "FILE_SPECIFIC") {
            item { OutlinedTextField(value = specificFile, onValueChange = onSpecificFileChange, label = { Text("例: 20261009_213958_2.opus") }, modifier = Modifier.fillMaxWidth()) }
        }
        item { Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("このケースで実行") } }
    }
}

@Composable
private fun RunningScreen(modifier: Modifier, case: TestCaseUi, steps: List<TestStepUi>) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Column {
                    Text("テストを実行しています…", style = MaterialTheme.typography.titleLarge)
                    Text(case.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(steps) { step ->
            Card(colors = CardDefaults.cardColors(containerColor = if (step.observeVibration) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.title, fontWeight = FontWeight.SemiBold)
                    Text(step.detail)
                    if (step.observeVibration) Text("このステップ直後の振動だけを覚えてください。", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("テスト中はアプリを閉じず、Gemを近くに置いてください。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ResultScreen(modifier: Modifier, case: TestCaseUi, result: JSONObject?, vibration: Int, onVibration: (Int) -> Unit, onHistory: () -> Unit, onMenu: () -> Unit) {
    val ok = result?.optString("status") == "COMPLETED"
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (ok) "テストが完了しました" else "テストに失敗しました", fontWeight = FontWeight.Bold)
                    Text(case.title)
                    if (!ok) Text(result?.optString("error").orEmpty())
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("自動判定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    ResultRow("録音ファイル", result?.optString("audioFile").orEmpty().ifEmpty { "未確認" })
                    val duration = result?.optLong("audioDurationMs", 0L) ?: 0L
                    ResultRow("録音時間", if (duration > 0) String.format(Locale.JAPAN, "%.1f秒", duration / 1000.0) else "未確認")
                    ResultRow("取得件数", "${result?.optInt("downloadedFiles", 0) ?: 0}件")
                    val inference = result?.optString("stopInference").orEmpty()
                    if (inference.isNotEmpty()) ResultRow("停止判定", inference)
                    val listed = result?.optString("listedFile").orEmpty()
                    if (listed.isNotEmpty()) ResultRow("一覧結果", listed)
                }
            }
        }
        if (case.id.startsWith("START_") || case.id.startsWith("STOP_")) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("振動の確認（手動入力）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("テスト操作を実行した瞬間のGem本体の振動だけを入力してください。後で行う安全停止の振動は含めません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = vibration == 0, onClick = { onVibration(0) }, label = { Text("なし") })
                            FilterChip(selected = vibration == 1, onClick = { onVibration(1) }, label = { Text("1回") })
                            FilterChip(selected = vibration == 2, onClick = { onVibration(2) }, label = { Text("2回") })
                            FilterChip(selected = vibration >= 3, onClick = { onVibration(3) }, label = { Text("その他") })
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("テスト履歴を見る") } }
        item { Button(onClick = onMenu, modifier = Modifier.fillMaxWidth()) { Text("テストメニューへ戻る") } }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.35f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun HistoryScreen(modifier: Modifier, history: List<JSONObject>) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (history.isEmpty()) item { Text("テスト履歴はまだありません。") }
        items(history) { row ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val id = row.optString("caseId")
                    Text(caseLabel(id), fontWeight = FontWeight.SemiBold)
                    Text(formatTime(row.optLong("startedAtMs")), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val vib = row.optInt("vibration", -1)
                    Text("振動: ${if (vib < 0) "未入力" else if (vib >= 3) "その他" else "${vib}回"}")
                    val duration = row.optLong("audioDurationMs", 0L)
                    if (duration > 0) Text(String.format(Locale.JAPAN, "録音: %.1f秒", duration / 1000.0))
                    val inference = row.optString("stopInference")
                    if (inference.isNotEmpty()) Text(inference)
                    val status = row.optString("status")
                    Text(if (status == "COMPLETED") "成功" else "失敗: ${row.optString("error")}")
                }
            }
        }
    }
}

private fun screenTitle(screen: MemoketTestScreen): String = when (screen) {
    MemoketTestScreen.MENU -> "Memoket テスト"
    MemoketTestScreen.START_CASES -> "録音開始テスト"
    MemoketTestScreen.STOP_CASES -> "録音停止テスト"
    MemoketTestScreen.FILE_CASES -> "ファイル取得テスト"
    MemoketTestScreen.RUNNING -> "テスト実行中"
    MemoketTestScreen.RESULT -> "テスト結果"
    MemoketTestScreen.HISTORY -> "テスト履歴"
}

private fun caseLabel(id: String): String = (startCases + stopCases + fileCases).firstOrNull { it.id == id }?.title ?: id
private fun jsonObjects(array: JSONArray): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    for (index in 0 until array.length()) array.optJSONObject(index)?.let(result::add)
    return result
}
private fun formatTime(value: Long): String {
    if (value <= 0) return "-"
    return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))
}
