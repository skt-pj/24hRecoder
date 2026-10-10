package com.sktpj.recorder24h

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val guideSteps = listOf(
    "準備" to "Gem本体の赤LED消灯・停止を確認。重要な未同期録音は先に保存。Androidの開発者向けオプションでBluetooth HCIスヌープをON→BluetoothをOFF/ON。公式アプリだけを使い、24hRecoderのBLEテストは実行しない。",
    "A：本体開始・停止" to "公式Memoketアプリに接続→本体ボタン約1秒長押し（振動1回、赤LED点灯）→10秒録音→本体ボタン1回（振動2回、消灯）→30秒待つ。操作時刻と公式アプリ状態を記録してバグレポートZIPを取得。",
    "B：録音中の接続" to "Gem停止中に公式アプリを画面外へ。Gem本体で録音開始→赤LED点灯中に公式アプリを開く→10秒待つ→Gem本体ボタン1回で停止。接続時刻、LED、アプリ表示を記録しバグレポートZIPを取得。",
    "C：公式アプリで停止" to "公式アプリに録音停止ボタンが存在する場合だけ。本体で開始→10秒後にアプリの停止を操作→振動とLEDを確認。停止しない場合は本体ボタンで停止。該当ボタンが無ければ「UI停止なし」と記録。",
    "提出" to "テストA/B（Cは該当時）のバグレポートZIP、操作時刻（JST秒単位）、振動、赤LED、公式アプリ表示を提出。HCIがZIPに含まれない場合はbtsnoop_hci.logも添付。ペアリング解除・初期化は行わない。"
)

@Composable
internal fun OfficialHciGuideScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("この画面は説明のみです。GemへBLEコマンドは送信しません。ログはAndroidのHCIスヌープで取得します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { Toast.makeText(context, "設定から開発者向けオプションを開いてください", Toast.LENGTH_LONG).show() }
            }, modifier = Modifier.fillMaxWidth()) { Text("開発者向けオプションを開く") }
        }
        items(guideSteps) { (title, detail) ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(detail)
                }
            }
        }
        item {
            Button(onClick = {
                val note = "\n\n【操作時刻メモ】\nA接続= / 開始= / 停止= / LED・振動= / 画面=\nB開始= / 再接続= / 停止= / LED・振動= / 画面=\nC停止UI有無= / 押下= / LED・振動= / 画面=\nZIPファイル名=\n"
                val text = guideSteps.joinToString("\n\n") { (title, detail) -> title + "\n" + detail } + note
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText("Memoket HCIログ採取", text))
                Toast.makeText(context, "手順と記入欄をコピーしました", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("手順と記入欄をコピー") }
        }
    }
}
