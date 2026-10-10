package com.sktpj.recorder24h

import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
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
import com.sktpj.recorder24h.memoket.MemoketStopAudioVerifier
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
    CaseUi("STOP_OFFICIAL_TIMING", "公式ログ類似：時間差あり", "OFF→400ms→ON→270ms→一覧要求"),
    CaseUi("STOP_OFF_WAIT_ON", "OFF→1秒→ON", "通知切替のみ"),
    CaseUi("STOP_ON_OFF", "ON→OFF", "逆順"),
    CaseUi("STOP_LIST_DELAY", "OFF→ON→1秒→一覧", "通知切替と一覧要求を分離"),
    CaseUi("STOP_03_REPEAT", "録音中に03再送", "03の状態依存仮説"),
    CaseUi("STOP_DISCONNECT", "通知操作なしで切断", "GATT切断のみ"),
    CaseUi("STOP_OFF_DISCONNECT", "OFF→切断", "通知OFFの後、接続終了")
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
    var physicalReady by remember { mutableStateOf(false) }
    var stopObserved by remember { mutableStateOf("UNSET") }
    var verificationError by remember { mutableStateOf("") }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val original = result
                if (original != null && original.optString("caseId").startsWith("STOP_")) {
                    val verified = MemoketStopAudioVerifier.verify(context, uri, original)
                    result = JSONObject(original.toString()).apply {
                        put("audioFile", verified.optString("fileName"))
                        put("audioDurationMs", verified.optLong("durationMs"))
                        put("audioBytes", verified.optLong("fileBytes"))
                        put("audioFileCrc32", verified.optString("localFileCrc32"))
                        put("fileMatchesTrial", verified.optBoolean("fileMatchesTrial"))
                        put("stopInference", verified.optString("inference"))
                        put("audioVerification", verified)
                    }
                    MemoketTestStore.save(context, result)
                    verificationError = ""
                }
            } catch (error: Exception) {
                verificationError = error.message ?: "音声ファイルを解析できませんでした"
            }
        }
    }

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
        stopObserved = "UNSET"
        verificationError = ""
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
            stopObserved = r.optString("stopObserved", "UNSET")
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
                { selected = stops.first(); physicalReady = false; screen = Screen.STOP },
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
            Screen.STOP -> StopCaseScreen(
                Modifier.padding(pad), stops, selected, { selected = it },
                physicalReady, { physicalReady = it }, ::runCase
            )
            Screen.FILE -> FileScreen(
                Modifier.padding(pad), selected, specific,
                { selected = it }, { specific = it }, ::runCase
            )
            Screen.RUNNING -> RunningScreen(Modifier.padding(pad), selected, steps)
            Screen.RESULT -> ResultScreen(
                Modifier.padding(pad), selected, result, vibration, stopObserved, verificationError,
                { value ->
                    vibration = value
                    val id = result?.optString("id").orEmpty()
                    if (id.isNotEmpty()) MemoketTestStore.updateVibration(context, id, value)
                },
                { value ->
                    stopObserved = value
                    result?.put("stopObserved", value)
                    val id = result?.optString("id").orEmpty()
                    if (id.isNotEmpty()) MemoketTestStore.updateStopObservation(context, id, value)
                },
                { audioPicker.launch(arrayOf("*/*")) },
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
        item { MenuCard("録音停止テスト", "BLE候補を比較し録音ファイルの長さで検証", onStop) }
        item { MenuCard("ファイル取得テスト", "一覧・メタデータ・ダウンロードを確認", onFile) }
        item { MenuCard("テスト履歴", "候補の実行時刻・音声長・検証結果を確認", onHistory) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("停止候補テスト自体はファイル取得・ACKをしません。ファイル取得テストは取得成功時にGemへACKを送るため、未取得の重要音声がある場合は注意してください。",
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
private fun StopCaseScreen(modifier: Modifier, cases: List<CaseUi>, selected: CaseUi,
    onSelect: (CaseUi) -> Unit, ready: Boolean, onReady: (Boolean) -> Unit, onRun: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("03で録音開始→約5秒後に停止候補を1回送信→10秒観測→切断。録音が続けばGem本体ボタンで停止し、ファイルを取得して長さを検証します。テスト自体はファイル取得・削除ACKを行いません。", color = MaterialTheme.colorScheme.error) }
        items(cases) { c -> CaseCard(c, selected.id == c.id) { onSelect(c) } }
        item {
            Row {
                Checkbox(checked = ready, onCheckedChange = onReady)
                Text("現在Gem赤LEDが消灯し、本体ボタンで手動停止できることを確認した")
            }
        }
        item { Button(onClick = { onReady(false); onRun() }, enabled = ready,
            modifier = Modifier.fillMaxWidth()) { Text("この候補だけ実行") } }
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
private fun ResultScreen(modifier: Modifier, selected: CaseUi, result: JSONObject?, vibration: Int, stopObserved: String, verificationError: String, onVibration: (Int) -> Unit, onStopObserved: (String) -> Unit, onImportFile: () -> Unit, onHistory: () -> Unit, onMenu: () -> Unit) {
    val ok = result?.optString("status") == "COMPLETED"
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ok) "BLE候補を実行しました（音声検証待ち）" else "BLEテストに失敗しました", fontWeight = FontWeight.Bold)
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
                    if (selected.id.startsWith("STOP_")) Text("候補操作: " + formatTime(result?.optLong("candidateAtMs", 0L) ?: 0L))
                    val listed = result?.optString("listedFile").orEmpty()
                    if (listed.isNotEmpty()) Text("一覧結果: " + listed)
                }
            }
        }
        if (selected.id.startsWith("START_")) {
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
        if (selected.id.startsWith("STOP_")) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("音声ファイルによる停止検証", fontWeight = FontWeight.Bold)
                        Text("候補操作後も10秒観測しています。Gemが録音中なら本体ボタンで停止し、該当する録音を公式アプリまたは24hRecoderで保存・書き出してください。")
                        Text("約5秒の音声なら候補時の停止と整合、約15秒以上なら録音継続と整合します。どちらも別ファイルの混入・再録音がないか確認が必要です。")
                        Button(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                            Text("今回の録音ファイルを選択して検証")
                        }
                        if (verificationError.isNotEmpty()) Text("検証エラー: " + verificationError, color = MaterialTheme.colorScheme.error)
                        val info = result?.optJSONObject("audioVerification")
                        if (info != null) {
                            Text("読み取った長さ: " + String.format(Locale.JAPAN, "%.2f秒", info.optLong("durationMs") / 1000.0))
                            Text("候補前: " + String.format(Locale.JAPAN, "%.2f秒", info.optLong("candidateElapsedMs") / 1000.0))
                            Text("観測終了まで: " + String.format(Locale.JAPAN, "%.2f秒", info.optLong("observedElapsedMs") / 1000.0))
                            Text("ファイル名・時刻照合: " + if (info.optBoolean("fileMatchesTrial")) "一致" else "未一致／判定不能")
                            Text("比較結果: " + info.optString("inference"))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Memoketテスト結果", result?.toString(2) ?: "{}"))
                Toast.makeText(context, "結果をコピーしました", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("BLE結果・操作記録をコピー") }
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
                    if (!row.optString("caseId").startsWith("STOP_")) {
                        Text("振動: " + if (vib < 0) "未入力" else if (vib >= 3) "その他" else vib.toString() + "回")
                    }
                    val duration = row.optLong("audioDurationMs", 0L)
                    if (duration > 0) Text(String.format(Locale.JAPAN, "録音: %.1f秒", duration / 1000.0))
                    val inference = row.optString("stopInference")
                    if (inference.isNotEmpty()) Text(inference)
                    if (row.optString("caseId").startsWith("STOP_")) {
                        Text("目視結果: " + when (row.optString("stopObserved")) {
                            "STOPPED" -> "消灯・停止"; "RECORDING" -> "点灯・継続"; "UNKNOWN" -> "不明"; else -> "未確認"
                        })
                    }
                    Text(if (row.optString("status") == "COMPLETED") "通信完了" else "失敗: " + row.optString("error"))
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
