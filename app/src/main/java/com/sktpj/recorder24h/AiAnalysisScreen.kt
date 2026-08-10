package com.sktpj.recorder24h

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.OpenAiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AiAnalysisDocument(
    val kind: String,
    val periodStartMs: Long,
    val periodEndMs: Long,
    val generatedAtMs: Long,
    val model: String,
    val analysis: JSONObject
)

private data class AiAnalysisSnapshot(
    val hasApiKey: Boolean,
    val daily: List<AiAnalysisDocument>,
    val hourly: List<AiAnalysisDocument>
)

@Composable
internal fun AiAnalysisScreen(
    refreshToken: Int,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var snapshot by remember {
        mutableStateOf(AiAnalysisSnapshot(OpenAiKeyStore.hasKey(context), emptyList(), emptyList()))
    }

    LaunchedEffect(refreshToken) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { loadAiAnalysisSnapshot(context.filesDir) }
            delay(5_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AiStatusCard(
                hasApiKey = snapshot.hasApiKey,
                hasAnyResult = snapshot.daily.isNotEmpty() || snapshot.hourly.isNotEmpty(),
                onOpenSettings = onOpenSettings,
                onAnalyzeNow = {
                    AiAnalysisScheduler.enqueueNow(context)
                    Toast.makeText(context, "Luna分析を登録しました", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (snapshot.daily.isEmpty()) {
            item {
                EmptyAnalysisCard(
                    title = "1日のノートはまだありません",
                    message = "日次分析は前日0:00〜24:00の確定済み文字起こし全体を使って生成します。API key登録後は自動実行されます。"
                )
            }
        } else {
            item {
                Text("1日のノート", style = MaterialTheme.typography.headlineMedium)
            }
            item { DailyAnalysisCard(snapshot.daily.first()) }
            if (snapshot.daily.size > 1) {
                item {
                    Text("過去の日次ノート", style = MaterialTheme.typography.titleLarge)
                }
                items(snapshot.daily.drop(1).take(13), key = { "daily-${it.periodStartMs}" }) { doc ->
                    CompactDailyCard(doc)
                }
            }
        }

        item {
            Text("時間別まとめ", style = MaterialTheme.typography.headlineMedium)
        }
        if (snapshot.hourly.isEmpty()) {
            item {
                EmptyAnalysisCard(
                    title = "時間別まとめはまだありません",
                    message = "直前の完了した1時間に文字起こしがある場合、GPT-5.6 Lunaが要点を構造化して保存します。"
                )
            }
        } else {
            items(snapshot.hourly.take(24), key = { "hourly-${it.periodStartMs}" }) { doc ->
                HourlyAnalysisCard(doc)
            }
        }
    }
}

@Composable
private fun AiStatusCard(
    hasApiKey: Boolean,
    hasAnyResult: Boolean,
    onOpenSettings: () -> Unit,
    onAnalyzeNow: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GPT-5.6 Luna", style = MaterialTheme.typography.titleLarge)
            Text(
                if (hasApiKey) "API key登録済み。文字起こしテキストだけを使って時間別・日次分析を生成します。"
                else "分析を使うには設定でOpenAI API keyを登録してください。"
            )
            if (!hasApiKey) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("API keyを設定する")
                }
            } else {
                FilledTonalButton(onClick = onAnalyzeNow, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasAnyResult) "分析を今すぐ更新" else "今すぐ分析")
                }
            }
        }
    }
}

@Composable
private fun EmptyAnalysisCard(title: String, message: String) {
    Card {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailyAnalysisCard(doc: AiAnalysisDocument) {
    val analysis = doc.analysis
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(formatDay(doc.periodStartMs), style = MaterialTheme.typography.titleLarge)
                Text(
                    "${doc.model} • ${formatDateTime(doc.generatedAtMs)}生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                val summary = analysis.optString("summary", "").trim()
                if (summary.isNotEmpty()) {
                    HorizontalDivider()
                    Text(summary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        ObjectListCard("TODO・約束", analysis.optJSONArray("todos")) { row ->
            buildString {
                append(row.optString("task", ""))
                val status = row.optString("status", "").trim()
                val evidence = row.optString("evidence", "").trim()
                if (status.isNotEmpty()) append("\n状態: ").append(status)
                if (evidence.isNotEmpty()) append("\n根拠: ").append(evidence)
            }
        }
        StringListCard("意思決定", analysis.optJSONArray("decisions"))
        StringListCard("アイデア", analysis.optJSONArray("ideas"))
        StringListCard("未解決", analysis.optJSONArray("unresolved"))
        ObjectListCard("トピック", analysis.optJSONArray("topics")) { row ->
            val name = row.optString("name", "").trim()
            val summary = row.optString("summary", "").trim()
            if (summary.isEmpty()) name else "$name\n$summary"
        }
        ObjectListCard("タイムライン", analysis.optJSONArray("timeline")) { row ->
            val time = row.optString("time", "").trim()
            val event = row.optString("event", "").trim()
            listOf(time, event).filter { it.isNotEmpty() }.joinToString("  ")
        }
        ObjectListCard("重要イベント", analysis.optJSONArray("keyEvents")) { row ->
            val time = row.optString("time", "").trim()
            val event = row.optString("event", "").trim()
            listOf(time, event).filter { it.isNotEmpty() }.joinToString("  ")
        }
        MindMapCard(analysis.optJSONArray("mindMap"))
        ObjectListCard("時間配分", analysis.optJSONArray("timeAllocation")) { row ->
            val category = row.optString("category", "").trim()
            val minutes = row.optInt("minutes", 0)
            val evidence = row.optString("evidence", "").trim()
            buildString {
                append(category)
                if (minutes > 0) append("  ").append(minutes).append("分")
                if (evidence.isNotEmpty()) append("\n根拠: ").append(evidence)
            }
        }
        ObjectListCard("繰り返し出た話題", analysis.optJSONArray("recurringTopics")) { row ->
            val topic = row.optString("topic", "").trim()
            val count = row.optInt("count", 0)
            val summary = row.optString("summary", "").trim()
            buildString {
                append(topic)
                if (count > 0) append("  ×").append(count)
                if (summary.isNotEmpty()) append("\n").append(summary)
            }
        }
        ObjectListCard("人物", analysis.optJSONArray("people")) { row ->
            val name = row.optString("name", "").trim()
            val summary = row.optString("summary", "").trim()
            if (summary.isEmpty()) name else "$name\n$summary"
        }
        ObjectListCard("場所", analysis.optJSONArray("places")) { row ->
            val name = row.optString("name", "").trim()
            val summary = row.optString("summary", "").trim()
            if (summary.isEmpty()) name else "$name\n$summary"
        }
        StringListCard("検索キーワード", analysis.optJSONArray("searchIndex"))
    }
}

@Composable
private fun CompactDailyCard(doc: AiAnalysisDocument) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(formatDay(doc.periodStartMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val summary = doc.analysis.optString("summary", "").trim()
            Text(
                if (summary.isEmpty()) "要約なし" else summary,
                maxLines = 5,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HourlyAnalysisCard(doc: AiAnalysisDocument) {
    val analysis = doc.analysis
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatHourRange(doc.periodStartMs, doc.periodEndMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(formatShortDate(doc.periodStartMs), style = MaterialTheme.typography.labelMedium)
            }
            val summary = analysis.optString("summary", "").trim()
            if (summary.isNotEmpty()) Text(summary)

            val topics = analysis.optJSONArray("topics").stringValues()
            if (topics.isNotEmpty()) {
                Text("話題: ${topics.take(6).joinToString(" / ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val decisions = analysis.optJSONArray("decisions").stringValues()
            if (decisions.isNotEmpty()) {
                Text("決定: ${decisions.take(3).joinToString(" / ")}")
            }
            val todos = analysis.optJSONArray("todos").objectValues().mapNotNull { it.optString("task", "").trim().takeIf(String::isNotEmpty) }
            if (todos.isNotEmpty()) {
                Text("TODO: ${todos.take(3).joinToString(" / ")}")
            }
        }
    }
}

@Composable
private fun StringListCard(title: String, array: JSONArray?) {
    val values = array.stringValues()
    if (values.isEmpty()) return
    AnalysisSectionCard(title) {
        values.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            Text("• $value", modifier = Modifier.padding(vertical = 7.dp))
        }
    }
}

@Composable
private fun ObjectListCard(
    title: String,
    array: JSONArray?,
    formatter: (JSONObject) -> String
) {
    val values = array.objectValues().map(formatter).map(String::trim).filter(String::isNotEmpty)
    if (values.isEmpty()) return
    AnalysisSectionCard(title) {
        values.forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider()
            Text(value, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun MindMapCard(array: JSONArray?) {
    val nodes = array.objectValues()
    if (nodes.isEmpty()) return
    val byId = nodes.associateBy { it.optString("id", "") }
    fun depth(node: JSONObject): Int {
        var current = node.optString("parentId", "").trim()
        var depth = 0
        val visited = mutableSetOf<String>()
        while (current.isNotEmpty() && depth < 6 && visited.add(current)) {
            val parent = byId[current] ?: break
            depth++
            current = parent.optString("parentId", "").trim()
        }
        return depth
    }

    AnalysisSectionCard("マインドマップ") {
        nodes.forEach { node ->
            val label = node.optString("label", "").trim()
            if (label.isNotEmpty()) {
                val level = depth(node)
                Surface(
                    tonalElevation = if (level == 0) 2.dp else 0.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(start = (level * 18).dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        if (level == 0) label else "↳ $label",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        fontWeight = if (level == 0) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisSectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

private fun loadAiAnalysisSnapshot(filesDir: File): AiAnalysisSnapshot {
    val analysisDir = File(filesDir, "analysis")
    val daily = loadAnalysisDocuments(File(analysisDir, "daily"), "daily", 30)
    val hourly = loadAnalysisDocuments(File(analysisDir, "hourly"), "hourly", 72)
    val contextDir = filesDir.parentFile
    val hasKey = contextDir != null && try {
        // API-key state is read in the Composable; this fallback is replaced below by caller state.
        true
    } catch (_: Exception) {
        false
    }
    return AiAnalysisSnapshot(hasApiKey = hasKey, daily = daily, hourly = hourly)
}

private fun loadAnalysisDocuments(root: File, expectedKind: String, limit: Int): List<AiAnalysisDocument> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .sortedByDescending { it.lastModified() }
        .take(limit)
        .mapNotNull { file ->
            try {
                val wrapper = JSONObject(file.readText(Charsets.UTF_8))
                if (wrapper.optString("kind", "") != expectedKind) return@mapNotNull null
                val analysis = wrapper.optJSONObject("analysis") ?: return@mapNotNull null
                AiAnalysisDocument(
                    kind = expectedKind,
                    periodStartMs = wrapper.optLong("periodStartMs", 0L),
                    periodEndMs = wrapper.optLong("periodEndMs", 0L),
                    generatedAtMs = wrapper.optLong("generatedAtMs", file.lastModified()),
                    model = wrapper.optString("model", "GPT-5.6 Luna"),
                    analysis = analysis
                )
            } catch (_: Exception) {
                null
            }
        }
        .sortedByDescending { it.periodStartMs }
        .toList()
}

private fun JSONArray?.stringValues(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        val value = optString(index, "").trim()
        if (value.isNotEmpty() && value != "null") result += value
    }
    return result
}

private fun JSONArray?.objectValues(): List<JSONObject> {
    if (this == null) return emptyList()
    val result = ArrayList<JSONObject>(length())
    for (index in 0 until length()) {
        optJSONObject(index)?.let(result::add)
    }
    return result
}

private fun formatDay(ms: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date(ms))

private fun formatShortDate(ms: Long): String =
    SimpleDateFormat("M/d", Locale.JAPAN).format(Date(ms))

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(ms))

private fun formatHourRange(startMs: Long, endMs: Long): String {
    val format = SimpleDateFormat("HH:mm", Locale.JAPAN)
    return "${format.format(Date(startMs))}–${format.format(Date(endMs))}"
}
