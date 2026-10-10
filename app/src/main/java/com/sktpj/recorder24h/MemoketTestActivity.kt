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
        setContent { TestTheme { TestApp { finish() } } }
    }
}

private enum class Screen { MENU, OFFICIAL, START, STOP, FILE, RUNNING, RESULT, HISTORY }
private data class CaseUi(val id: String, val title: String, val subtitle: String)
private data class StepUi(val title: String, val detail: String, val observe: Boolean)

private val starts = listOf(
    CaseUi("START_CURRENT", "現在の実装（標準）", "DATA + RESPONSE"),
    CaseUi("START_OFFICIAL", "公式アプリ相当", "0036 / RESPONSE / DATA / 0039 を公式HCI順で設定"),
    CaseUi("START_DATA_ONLY", "DATA通知のみ", "03送信時はDATA通知だけ"),
    CaseUi("START_RESPONSE_ONLY", "RESPONSE通知のみ", "03送信時はRESPONSE通知だけ"),
    CaseUi("START_EXTRA5", "追加通知あり（0036）", "DATA + RESPONSE + 0036"),
    CaseUi("START_EXTRA56", "追加通知あり（0036 + 0039）", "DATA + RESPONSE + 0036 + 0039"),
    CaseUi("START_NONE", "通知なし", "認証後に通知をOFFにして03")
)

private val stops = listOf(
    CaseUi("STOP_A", "通知OFFのみ（A）", "DATA通知OFF"),
    CaseUi("STOP_B", "通知ONのみ（B）", "DATA通知ON"),
    CaseUi("STOP_C", "01 00 00 のみ（C）", "一覧要求のみ"),
    CaseUi("STOP_AB", "OFF → ON（A→B）", "DATA OFF → ON"),
    CaseUi("STOP_AC", "OFF → 01 00 00（A→C）", "DATA OFF → 一覧要求"),
    CaseUi("STOP_BC", "ON → 01 00 00（B→C）", "DATA ON → 一覧要求"),
    CaseUi("STOP_ABC", "OFF → ON → 01 00 00（A→B→C）", "現在の停止系列"),
    CaseUi("STOP_OFFICIAL_TIMING", "公式アプリ相当（待機あり）", "OFF → 400ms → ON → 270ms → 01 00 00")
)

private val files = listOf(
    CaseUi("FILE_ONE", "1件取得", "未取得ファイルを1件取得"),
    CaseUi("FILE_THREE", "最大3件取得", "未取得ファイルを3件まで取得"),
    CaseUi("FILE_SPECIFIC", "指定ファイルまで取得", "指定名に到達するまで順に取得"),
    CaseUi("FILE_LIST", "一覧のみ", "ファイル名だけ確認")
)

@Composable
private fun TestTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    var screen by remember { mutableStateOf(Screen.MENU) }
    var selected by remember { mutableStateOf(starts.first()) }
    var specific by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf(emptyList<StepUi>()) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf(emptyList<JSONObject>()) }
    var vibration by remember { mutableStateOf(-1) }

    fun reloadHistory() { history = toList(MemoketTestStore.history(context)) }

    fun runCase() {
        val normalState = MemoketSettings.remoteRecordingState(context)
        if (normalState == "録音中" || normalState == "接続中" || normalState == "停止処理中") {
            result = JSONObject().put("status", "FAILED").put("error", "通常のGem録音を停止してからテストしてください。")
            screen = Screen.RESULT
            return
        }
        steps = emptyList()
        vibration = -1
        screen = Screen.RUNNING
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                MemoketTestEngine.run(
                    context,
                    selected.id,
                    specific,
                    MemoketTestEngine.ProgressListener { title, detail, observe ->
                        handler.post { steps = steps + StepUi(title, detail, observe) }
                    }
                )
            }
            result = r
            vibration = r.optInt("vibration", -1)
            reloadHistory()
            screen = Screen.RESULT
        }
    }

    BackHandler {
        if (screen == Screen.RUNNING) return@BackHandler
        if (screen == Screen.MENU) onClose() else screen = Screen.MENU
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(screen)) },
                navigationIcon = {
                    IconButton(
                        enabled = screen != Screen.RUNNING,
                        onClick = { if (screen == Screen.MENU) onClose() else screen = Screen.MENU }
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                }
            )
        }
    ) { pad ->
        when (screen) {
            Screen.MENU -> MenuScreen(
                Modifier.padding(pad),
                { selected = starts.first(); screen = Screen.START },
                { selected = stops.first(); screen = Screen.STOP },
                { selected = files.first(); screen = Screen.FILE },
                { reloadHistory(); screen = Screen.HISTORY },
                { screen = Screen.OFFICIAL }
            )
            Screen.OFFICIAL -> OfficialHciGuideScreen(Modifier.padding(pad))
            Screen.START -> CaseScreen(
                Modifier.padding(pad),
                "録音開始時の振動と、実際に約5秒の録音ファイルができるかを確認します。安全停止時の振動は回答に含めません。",
                starts, selected, { selected = it }, ::runCase
            )
            Screen.STOP -> CaseScreen(
                Modifier.padding(pad),
                "自動で5秒録音し、停止候補を実行して3秒観測した後、安全停止します。録音時間から候補操作で停止したかを推定します。",
                stops, selected, { selected = it }, ::runCase
            )
            Screen.FILE -> FileScreen(
                Modifier.padding(pad), selected, specific,
                { selected = it }, { specific = it }, ::runCase
            )
            Screen.RUNNING -> RunningScreen(Modifier.padding(pad), selected, steps)
            Screen.RESULT -> ResultScreen(
                Modifier.padding(pad), selected, result, vibration,
                { value ->
                    vibration = value
                    val id = result?.optString("id").orEmpty()
                    if (id.isNotEmpty()) MemoketTestStore.updateVibration(context, id, value)
                },
                { reloadHistory(); screen = Screen.HISTORY },
                { screen = Screen.MENU }
            )
            Screen.HISTORY -> HistoryScreen(Modifier.padding(pad), history)
        }
    }
}

@Composable
private fun MenuScreen(modifier: Modifier, onStart: () -> Unit, onStop: () -> Unit, onFile: () -> Unit, onHistory: () -> Unit, onOfficial: () -> Unit) {
    val context = LocalContext.current
    val address = MemoketSettings.address(context)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("未検証の診断コマンドはGemの録音を開始したまま停止できなくする可能性があります。開始・停止テストは本体を手動停止できる状況でのみ実行してください。", color = MaterialTheme.colorScheme.error) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("接続中のデバイス", fontWeight = FontWeight.Bold)
                    Text("Memoket Gem")
                    Text(if (address.isEmpty()) "未選択" else address)
                }
            }
        }
        item { MenuCard("公式アプリHCI採取", "本体の録音操作を公式アプリの通信ログで調査", onOfficial) }
        item { MenuCard("録音開始テスト", "開始時の振動と録音成立を調査", onStart) }
        item { MenuCard("録音停止テスト", "停止時の振動とファイル確定を調査", onStop) }
        item { MenuCard("ファイル取得テスト", "一覧・メタデータ・ダウンロードを確認", onFile) }
        item { MenuCard("テスト履歴", "ケース・振動回数・録音時間・結果を確認", onHistory) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("停止・取得テストは取得成功時にGemへACKを送ります。未取得ファイルをGemに残したい場合は実行しないでください。",
                    Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("開く") }
        }
    }
}

@Composable
private fun CaseScreen(modifier: Modifier, intro: String, cases: List<CaseUi>, selected: CaseUi, onSelect: (CaseUi) -> Unit, onRun: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(intro, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(cases) { c -> CaseCard(c, selected.id == c.id) { onSelect(c) } }
        item { Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("このケースで実行") } }
    }
}

@Composable
private fun CaseCard(c: CaseUi, selected: Boolean, onSelect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = selected, onClick = onSelect)
                Text(c.title, fontWeight = FontWeight.Bold)
            }
            Text(c.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileScreen(modifier: Modifier, selected: CaseUi, specific: String, onSelect: (CaseUi) -> Unit, onSpecific: (String) -> Unit, onRun: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Gemの未取得ファイルに対する取得経路を確認します。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(files) { c -> CaseCard(c, selected.id == c.id) { onSelect(c) } }
        if (selected.id == "FILE_SPECIFIC") {
            item { OutlinedTextField(value = specific, onValueChange = onSpecific, label = { Text("ファイル名") }, modifier = Modifier.fillMaxWidth()) }
        }
        item { Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("このケースで実行") } }
    }
}

@Composable
private fun RunningScreen(modifier: Modifier, selected: CaseUi, steps: List<StepUi>) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("テストを実行しています…", fontWeight = FontWeight.Bold)
                Text(selected.title)
            }
        }
        items(steps) { step ->
            Card(colors = CardDefaults.cardColors(containerColor = if (step.observe) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(step.title, fontWeight = FontWeight.Bold)
                    Text(step.detail)
                    if (step.observe) Text("このステップ直後の振動だけを覚えてください。", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ResultScreen(modifier: Modifier, selected: CaseUi, result: JSONObject?, vibration: Int, onVibration: (Int) -> Unit, onHistory: () -> Unit, onMenu: () -> Unit) {
    val ok = result?.optString("status") == "COMPLETED"
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ok) "テストが完了しました" else "テストに失敗しました", fontWeight = FontWeight.Bold)
                    Text(selected.title)
                    if (!ok) Text(result?.optString("error").orEmpty())
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("自動判定", fontWeight = FontWeight.Bold)
                    Text("録音ファイル: " + result?.optString("audioFile").orEmpty().ifEmpty { "未確認" })
                    val duration = result?.optLong("audioDurationMs", 0L) ?: 0L
                    Text("録音時間: " + if (duration > 0) String.format(Locale.JAPAN, "%.1f秒", duration / 1000.0) else "未確認")
                    Text("取得件数: " + (result?.optInt("downloadedFiles", 0) ?: 0) + "件")
                    val inference = result?.optString("stopInference").orEmpty()
                    if (inference.isNotEmpty()) Text("停止判定: " + inference)
                    val listed = result?.optString("listedFile").orEmpty()
                    if (listed.isNotEmpty()) Text("一覧結果: " + listed)
                }
            }
        }
        if (selected.id.startsWith("START_") || selected.id.startsWith("STOP_")) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("振動の確認（手動入力）", fontWeight = FontWeight.Bold)
                        Text("テスト操作を実行した瞬間の振動だけを入力してください。安全停止の振動は含めません。")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun HistoryScreen(modifier: Modifier, history: List<JSONObject>) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (history.isEmpty()) item { Text("テスト履歴はまだありません。") }
        items(history) { row ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(labelFor(row.optString("caseId")), fontWeight = FontWeight.Bold)
                    Text(formatTime(row.optLong("startedAtMs")))
                    val vib = row.optInt("vibration", -1)
                    Text("振動: " + if (vib < 0) "未入力" else if (vib >= 3) "その他" else vib.toString() + "回")
                    val duration = row.optLong("audioDurationMs", 0L)
                    if (duration > 0) Text(String.format(Locale.JAPAN, "録音: %.1f秒", duration / 1000.0))
                    val inference = row.optString("stopInference")
                    if (inference.isNotEmpty()) Text(inference)
                    Text(if (row.optString("status") == "COMPLETED") "成功" else "失敗: " + row.optString("error"))
                }
            }
        }
    }
}

private fun titleFor(screen: Screen) = when (screen) {
    Screen.MENU -> "Memoket テスト"
    Screen.OFFICIAL -> "公式アプリHCI採取"
    Screen.START -> "録音開始テスト"
    Screen.STOP -> "録音停止テスト"
    Screen.FILE -> "ファイル取得テスト"
    Screen.RUNNING -> "テスト実行中"
    Screen.RESULT -> "テスト結果"
    Screen.HISTORY -> "テスト履歴"
}

private fun labelFor(id: String) = (starts + stops + files).firstOrNull { it.id == id }?.title ?: id
private fun toList(array: JSONArray): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    for (i in 0 until array.length()) array.optJSONObject(i)?.let(out::add)
    return out
}
private fun formatTime(ms: Long): String =
    if (ms <= 0) "-" else SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(ms))
