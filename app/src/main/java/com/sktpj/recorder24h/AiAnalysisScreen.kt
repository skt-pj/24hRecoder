package com.sktpj.recorder24h

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sktpj.recorder24h.ai.AiAnalysisScheduler
import com.sktpj.recorder24h.ai.AiProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private enum class NotebookPeriod(val label: String) {
    DAY("日"),
    WEEK("週"),
    MONTH("月"),
    YEAR("年")
}

private data class AiAnalysisDocument(
    val kind: String,
    val periodStartMs: Long,
    val periodEndMs: Long,
    val generatedAtMs: Long,
    val model: String,
    val sourceCount: Int,
    val analysis: JSONObject
)

private data class AiAnalysisSnapshot(
    val aiConfigured: Boolean,
    val daily: List<AiAnalysisDocument>,
    val weekly: List<AiAnalysisDocument>,
    val monthly: List<AiAnalysisDocument>,
    val yearly: List<AiAnalysisDocument>,
    val hourly: List<AiAnalysisDocument>
) {
    val hasAnyResult: Boolean
        get() = daily.isNotEmpty() || weekly.isNotEmpty() || monthly.isNotEmpty() ||
            yearly.isNotEmpty() || hourly.isNotEmpty()

    fun searchable(): List<AiAnalysisDocument> =
        daily + weekly + monthly + yearly
}

@Composable
internal fun AiAnalysisScreen(
    refreshToken: Int,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var snapshot by remember {
        mutableStateOf(
            AiAnalysisSnapshot(
                AiProviderStore.isConfigured(context),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList()
            )
        )
    }
    var selectedPeriod by remember { mutableStateOf(NotebookPeriod.DAY) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDayStartMs by remember { mutableStateOf<Long?>(null) }
    var selectedWeekStartMs by remember { mutableStateOf<Long?>(null) }
    var selectedMonth by remember { mutableStateOf<YearMonth?>(null) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(refreshToken) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                loadAiAnalysisSnapshot(context.filesDir, AiProviderStore.isConfigured(context))
            }
            snapshot = next

            val today = LocalDate.now(zone)
            val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
            if (selectedDayStartMs == null) {
                selectedDayStartMs = todayStartMs
            } else if (selectedDayStartMs != todayStartMs &&
                next.daily.none { it.periodStartMs == selectedDayStartMs }
            ) {
                selectedDayStartMs = next.daily.firstOrNull()?.periodStartMs ?: todayStartMs
            }
            if (selectedWeekStartMs == null ||
                next.weekly.none { it.periodStartMs == selectedWeekStartMs }
            ) {
                selectedWeekStartMs = next.weekly.firstOrNull()?.periodStartMs
            }
            if (selectedMonth == null) {
                selectedMonth = YearMonth.now(zone)
            }
            if (selectedYear == null) {
                selectedYear = today.year
            }
            delay(15_000L)
        }
    }

    val searchHits = remember(snapshot, searchQuery) {
        searchNotebook(snapshot, searchQuery)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AiStatusCard(
                aiConfigured = snapshot.aiConfigured,
                hasAnyResult = snapshot.hasAnyResult,
                onOpenSettings = onOpenSettings,
                onAnalyzeNow = {
                    AiAnalysisScheduler.enqueueNow(context)
                }
            )
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("AIノートを検索") },
                supportingText = {
                    Text("日・週・月・年の保存済みノートを横断検索します")
                }
            )
        }

        if (searchQuery.isNotBlank()) {
            item {
                SearchResults(
                    query = searchQuery,
                    results = searchHits,
                    onOpen = { doc ->
                        when (doc.kind) {
                            "daily" -> {
                                selectedPeriod = NotebookPeriod.DAY
                                selectedDayStartMs = doc.periodStartMs
                                selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                                selectedYear = localDate(doc.periodStartMs, zone).year
                            }
                            "weekly" -> {
                                selectedPeriod = NotebookPeriod.WEEK
                                selectedWeekStartMs = doc.periodStartMs
                            }
                            "monthly" -> {
                                selectedPeriod = NotebookPeriod.MONTH
                                selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                                selectedYear = localDate(doc.periodStartMs, zone).year
                            }
                            "yearly" -> {
                                selectedPeriod = NotebookPeriod.YEAR
                                selectedYear = localDate(doc.periodStartMs, zone).year
                            }
                        }
                        searchQuery = ""
                    }
                )
            }
        } else {
            item {
                PeriodSelector(
                    selected = selectedPeriod,
                    onSelect = { selectedPeriod = it }
                )
            }

            when (selectedPeriod) {
                NotebookPeriod.DAY -> item {
                    val month = selectedMonth ?: YearMonth.now(zone)
                    val currentMonth = YearMonth.now(zone)
                    DayNotebook(
                        snapshot = snapshot,
                        month = month,
                        selectedDayStartMs = selectedDayStartMs,
                        onMonthChange = { nextMonth ->
                            selectedMonth = nextMonth
                            selectedDayStartMs = if (nextMonth == currentMonth) {
                                LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                            } else {
                                snapshot.daily
                                    .firstOrNull { YearMonth.from(localDate(it.periodStartMs, zone)) == nextMonth }
                                    ?.periodStartMs
                            }
                        },
                        onSelectDay = { doc ->
                            selectedDayStartMs = doc.periodStartMs
                            selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                            selectedYear = localDate(doc.periodStartMs, zone).year
                        }
                    )
                }

                NotebookPeriod.WEEK -> item {
                    WeekNotebook(
                        snapshot = snapshot,
                        selectedWeekStartMs = selectedWeekStartMs,
                        onSelectWeek = { selectedWeekStartMs = it },
                        onOpenDay = { doc ->
                            selectedPeriod = NotebookPeriod.DAY
                            selectedDayStartMs = doc.periodStartMs
                            selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                            selectedYear = localDate(doc.periodStartMs, zone).year
                        }
                    )
                }

                NotebookPeriod.MONTH -> item {
                    val month = selectedMonth ?: YearMonth.now(zone)
                    MonthNotebook(
                        snapshot = snapshot,
                        month = month,
                        onMonthChange = { selectedMonth = it },
                        onOpenDay = { doc ->
                            selectedPeriod = NotebookPeriod.DAY
                            selectedDayStartMs = doc.periodStartMs
                            selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                            selectedYear = localDate(doc.periodStartMs, zone).year
                        }
                    )
                }

                NotebookPeriod.YEAR -> item {
                    val year = selectedYear ?: LocalDate.now(zone).year
                    YearNotebook(
                        snapshot = snapshot,
                        year = year,
                        onYearChange = { selectedYear = it },
                        onOpenMonth = { month ->
                            selectedPeriod = NotebookPeriod.MONTH
                            selectedMonth = month
                            selectedYear = month.year
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AiStatusCard(
    aiConfigured: Boolean,
    hasAnyResult: Boolean,
    onOpenSettings: () -> Unit,
    onAnalyzeNow: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AIノート", style = MaterialTheme.typography.titleLarge)
            Text(
                if (aiConfigured) {
                    "時間別・日次ノートを生成し、完了した日次ノートから週・月・年の振り返りを段階的に作ります。"
                } else {
                    "分析を使うには設定でAIプロバイダを設定してください。"
                }
            )
            if (!aiConfigured) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("AI設定を開く")
                }
            } else {
                FilledTonalButton(onClick = onAnalyzeNow, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasAnyResult) "期間を指定して再分析" else "期間を指定して分析")
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    selected: NotebookPeriod,
    onSelect: (NotebookPeriod) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("表示期間", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(NotebookPeriod.entries) { period ->
                FilterChip(
                    selected = period == selected,
                    onClick = { onSelect(period) },
                    label = { Text(period.label) }
                )
            }
        }
    }
}

@Composable
private fun DayNotebook(
    snapshot: AiAnalysisSnapshot,
    month: YearMonth,
    selectedDayStartMs: Long?,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDay: (AiAnalysisDocument) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val currentMonth = YearMonth.from(today)
    val selectedStartMs = selectedDayStartMs ?: if (month == currentMonth) {
        todayStartMs
    } else {
        snapshot.daily.firstOrNull {
            YearMonth.from(localDate(it.periodStartMs, zone)) == month
        }?.periodStartMs
    }
    val selectedDaily = snapshot.daily.firstOrNull { it.periodStartMs == selectedStartMs }
    val selectedIsToday = selectedStartMs == todayStartMs
    val calendarDaily = if (month == currentMonth &&
        snapshot.daily.none { it.periodStartMs == todayStartMs }
    ) {
        snapshot.daily + AiAnalysisDocument(
            kind = "today",
            periodStartMs = todayStartMs,
            periodEndMs = todayEndMs,
            generatedAtMs = 0L,
            model = "",
            sourceCount = 0,
            analysis = JSONObject()
        )
    } else {
        snapshot.daily
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("日", style = MaterialTheme.typography.headlineMedium)
        PeriodNavigation(
            title = "${month.year}年${month.monthValue}月",
            previousLabel = "前月",
            nextLabel = "次月",
            onPrevious = { onMonthChange(month.minusMonths(1)) },
            onNext = { onMonthChange(month.plusMonths(1)) },
            nextEnabled = month < currentMonth
        )
        MonthCalendar(
            month = month,
            daily = calendarDaily,
            selectedDayStartMs = selectedStartMs,
            onSelect = onSelectDay
        )

        if (selectedIsToday) {
            if (selectedDaily != null) {
                DailyAnalysisCard(selectedDaily)
            } else {
                EmptyAnalysisCard(
                    title = "今日の日次ノートはまだ確定していません",
                    message = "日次ノートは1日が完了した後に生成します。今日については、下に1時間ごとのAI更新結果を表示します。"
                )
            }

            Text("今日の1時間ごとの更新", style = MaterialTheme.typography.titleLarge)
            val hourly = snapshot.hourly.filter {
                it.periodStartMs >= todayStartMs && it.periodStartMs < todayEndMs
            }
            if (hourly.isEmpty()) {
                EmptyAnalysisCard(
                    title = "今日の時間別まとめはまだありません",
                    message = "1時間単位のAI分析が完了すると、最新の時間帯からここに表示されます。"
                )
            } else {
                hourly.sortedByDescending { it.periodStartMs }.forEach { HourlySummaryCard(it) }
            }
        } else if (selectedDaily != null) {
            DailyAnalysisCard(selectedDaily)
        } else {
            EmptyAnalysisCard(
                title = "この月の日次ノートはありません",
                message = "日次ノートが生成されると、カレンダーの日付から直接開けます。"
            )
        }
    }
}

@Composable
private fun WeekNotebook(
    snapshot: AiAnalysisSnapshot,
    selectedWeekStartMs: Long?,
    onSelectWeek: (Long) -> Unit,
    onOpenDay: (AiAnalysisDocument) -> Unit
) {
    val weekly = snapshot.weekly
    val selected = weekly.firstOrNull { it.periodStartMs == selectedWeekStartMs }
        ?: weekly.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("週", style = MaterialTheme.typography.headlineMedium)

        if (selected == null) {
            EmptyAnalysisCard(
                title = "週のまとめはまだありません",
                message = "1週間が完了すると、その週の日次ノートをAIがまとめます。"
            )
        } else {
            val index = weekly.indexOfFirst { it.periodStartMs == selected.periodStartMs }
            PeriodNavigation(
                title = formatPeriodRange(selected.periodStartMs, selected.periodEndMs),
                previousLabel = "前の週",
                nextLabel = "次の週",
                onPrevious = {
                    weekly.getOrNull(index + 1)?.let { onSelectWeek(it.periodStartMs) }
                },
                onNext = {
                    weekly.getOrNull(index - 1)?.let { onSelectWeek(it.periodStartMs) }
                },
                previousEnabled = index >= 0 && index + 1 < weekly.size,
                nextEnabled = index > 0
            )
            RollupAnalysisCard(selected)

            val days = snapshot.daily
                .filter {
                    it.periodStartMs >= selected.periodStartMs &&
                        it.periodStartMs < selected.periodEndMs
                }
                .sortedBy { it.periodStartMs }
            if (days.isNotEmpty()) {
                Text("この週の日次ノート", style = MaterialTheme.typography.titleLarge)
                days.forEach { doc ->
                    SummaryLinkCard(
                        title = formatDay(doc.periodStartMs),
                        summary = doc.analysis.optString("summary", ""),
                        onClick = { onOpenDay(doc) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthNotebook(
    snapshot: AiAnalysisSnapshot,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onOpenDay: (AiAnalysisDocument) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val monthly = snapshot.monthly.firstOrNull {
        YearMonth.from(localDate(it.periodStartMs, zone)) == month
    }
    val currentMonth = YearMonth.now(zone)
    val days = snapshot.daily.filter {
        YearMonth.from(localDate(it.periodStartMs, zone)) == month
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("月", style = MaterialTheme.typography.headlineMedium)
        PeriodNavigation(
            title = "${month.year}年${month.monthValue}月",
            previousLabel = "前月",
            nextLabel = "次月",
            onPrevious = { onMonthChange(month.minusMonths(1)) },
            onNext = { onMonthChange(month.plusMonths(1)) },
            nextEnabled = month < currentMonth
        )

        if (monthly != null) {
            RollupAnalysisCard(monthly)
        } else {
            EmptyAnalysisCard(
                title = if (month == currentMonth) "今月は進行中です" else "月のまとめはありません",
                message = if (month == currentMonth) {
                    "月が完了すると、その月の日次ノートをAIがまとめます。日ごとのノートは下のカレンダーから確認できます。"
                } else {
                    "この月に保存済み日次ノートがないか、月次まとめがまだ生成されていません。"
                }
            )
        }

        MonthCalendar(
            month = month,
            daily = snapshot.daily,
            selectedDayStartMs = null,
            onSelect = onOpenDay
        )
        Text("${days.size}日分の日次ノート", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun YearNotebook(
    snapshot: AiAnalysisSnapshot,
    year: Int,
    onYearChange: (Int) -> Unit,
    onOpenMonth: (YearMonth) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val currentYear = LocalDate.now(zone).year
    val yearly = snapshot.yearly.firstOrNull {
        localDate(it.periodStartMs, zone).year == year
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("年", style = MaterialTheme.typography.headlineMedium)
        PeriodNavigation(
            title = "${year}年",
            previousLabel = "前年",
            nextLabel = "翌年",
            onPrevious = { onYearChange(year - 1) },
            onNext = { onYearChange(year + 1) },
            nextEnabled = year < currentYear
        )

        if (yearly != null) {
            RollupAnalysisCard(yearly)
        } else {
            EmptyAnalysisCard(
                title = if (year == currentYear) "今年は進行中です" else "年のまとめはありません",
                message = if (year == currentYear) {
                    "年が完了すると、月次ノートを中心にAIが年間の振り返りを生成します。"
                } else {
                    "この年の年間まとめはまだ生成されていません。"
                }
            )
        }

        Text("月別", style = MaterialTheme.typography.titleLarge)
        for (rowStart in 1..12 step 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (monthValue in rowStart until minOf(rowStart + 3, 13)) {
                    val month = YearMonth.of(year, monthValue)
                    val dailyCount = snapshot.daily.count {
                        YearMonth.from(localDate(it.periodStartMs, zone)) == month
                    }
                    val hasMonthly = snapshot.monthly.any {
                        YearMonth.from(localDate(it.periodStartMs, zone)) == month
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenMonth(month) }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("${monthValue}月", fontWeight = FontWeight.SemiBold)
                            Text("${dailyCount}日", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (hasMonthly) "まとめ済み" else "未確定",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodNavigation(
    title: String,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = previousEnabled
            ) {
                Text(previousLabel)
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedButton(
                onClick = onNext,
                enabled = nextEnabled
            ) {
                Text(nextLabel)
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    daily: List<AiAnalysisDocument>,
    selectedDayStartMs: Long?,
    onSelect: (AiAnalysisDocument) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val docsByDate = daily.associateBy { localDate(it.periodStartMs, zone) }
    val firstOffset = month.atDay(1).dayOfWeek.value % 7
    val totalSlots = firstOffset + month.lengthOfMonth()
    val rows = (totalSlots + 6) / 7
    val weekdayLabels = listOf("日", "月", "火", "水", "木", "金", "土")

    Card {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val slot = row * 7 + column
                        val day = slot - firstOffset + 1
                        if (day < 1 || day > month.lengthOfMonth()) {
                            Box(Modifier.weight(1f).height(54.dp))
                        } else {
                            val date = month.atDay(day)
                            val doc = docsByDate[date]
                            val selected = doc?.periodStartMs == selectedDayStartMs
                            val containerColor = when {
                                selected -> MaterialTheme.colorScheme.primaryContainer
                                doc != null -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .height(54.dp)
                                    .clickable(enabled = doc != null) {
                                        doc?.let(onSelect)
                                    },
                                color = containerColor,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        day.toString(),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        when {
                                            doc?.kind == "today" -> "今日"
                                            doc != null -> "●"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    results: List<AiAnalysisDocument>,
    onOpen: (AiAnalysisDocument) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("「${query.trim()}」の検索結果", style = MaterialTheme.typography.headlineSmall)
        if (results.isEmpty()) {
            EmptyAnalysisCard(
                title = "一致するAIノートはありません",
                message = "保存済みの日・週・月・年ノートを検索しています。"
            )
        } else {
            results.forEach { doc ->
                SummaryLinkCard(
                    title = periodLabel(doc),
                    summary = doc.analysis.optString("summary", ""),
                    onClick = { onOpen(doc) }
                )
            }
        }
    }
}

@Composable
private fun SummaryLinkCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (summary.isNotBlank()) {
                Text(
                    summary.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            listOf(time, event).filter(String::isNotEmpty).joinToString("  ")
        }
        StringListCard("重要イベント", analysis.optJSONArray("keyEvents"))
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
private fun RollupAnalysisCard(doc: AiAnalysisDocument) {
    val analysis = doc.analysis
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(periodLabel(doc), style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append(doc.model)
                        if (doc.sourceCount > 0) append(" • ").append(doc.sourceCount).append("件の下位ノート")
                        append(" • ").append(formatDateTime(doc.generatedAtMs)).append("生成")
                    },
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

        StringListCard("ハイライト", analysis.optJSONArray("highlights"))
        ObjectListCard("トピック", analysis.optJSONArray("topics")) { row ->
            val name = row.optString("name", "").trim()
            val summary = row.optString("summary", "").trim()
            val count = row.optInt("count", 0)
            buildString {
                append(name)
                if (count > 0) append("  ×").append(count)
                if (summary.isNotEmpty()) append("\n").append(summary)
            }
        }
        ObjectListCard("傾向", analysis.optJSONArray("trends")) { row ->
            val label = row.optString("label", "").trim()
            val summary = row.optString("summary", "").trim()
            if (summary.isEmpty()) label else "$label\n$summary"
        }
        StringListCard("意思決定", analysis.optJSONArray("decisions"))
        ObjectListCard("TODO・約束", analysis.optJSONArray("todos")) { row ->
            buildString {
                append(row.optString("task", ""))
                val status = row.optString("status", "").trim()
                val evidence = row.optString("evidence", "").trim()
                if (status.isNotEmpty()) append("\n状態: ").append(status)
                if (evidence.isNotEmpty()) append("\n根拠: ").append(evidence)
            }
        }
        StringListCard("アイデア", analysis.optJSONArray("ideas"))
        StringListCard("未解決", analysis.optJSONArray("unresolved"))
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
private fun HourlySummaryCard(doc: AiAnalysisDocument) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                formatHourRange(doc.periodStartMs, doc.periodEndMs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val summary = doc.analysis.optString("summary", "").trim()
            if (summary.isNotEmpty()) Text(summary)
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
        var level = 0
        val visited = mutableSetOf<String>()
        while (current.isNotEmpty() && level < 6 && visited.add(current)) {
            val parent = byId[current] ?: break
            level++
            current = parent.optString("parentId", "").trim()
        }
        return level
    }

    AnalysisSectionCard("マインドマップ") {
        nodes.forEach { node ->
            val label = node.optString("label", "").trim()
            if (label.isNotEmpty()) {
                val level = depth(node)
                Surface(
                    tonalElevation = if (level == 0) 2.dp else 0.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(
                        start = (level * 18).dp,
                        top = 4.dp,
                        bottom = 4.dp
                    )
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

private fun loadAiAnalysisSnapshot(filesDir: File, aiConfigured: Boolean): AiAnalysisSnapshot {
    val analysisDir = File(filesDir, "analysis")
    return AiAnalysisSnapshot(
        aiConfigured = aiConfigured,
        daily = loadAnalysisDocuments(File(analysisDir, "daily"), "daily", 2_000),
        weekly = loadAnalysisDocuments(File(analysisDir, "weekly"), "weekly", 600),
        monthly = loadAnalysisDocuments(File(analysisDir, "monthly"), "monthly", 240),
        yearly = loadAnalysisDocuments(File(analysisDir, "yearly"), "yearly", 100),
        hourly = loadAnalysisDocuments(File(analysisDir, "hourly"), "hourly", 168)
    )
}

private fun loadAnalysisDocuments(
    root: File,
    expectedKind: String,
    limit: Int
): List<AiAnalysisDocument> {
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
                    model = wrapper.optString("model", "AI"),
                    sourceCount = when {
                        wrapper.has("sourceAnalysisCount") ->
                            wrapper.optInt("sourceAnalysisCount", 0)
                        wrapper.has("sourceTranscriptCount") ->
                            wrapper.optInt("sourceTranscriptCount", 0)
                        else -> 0
                    },
                    analysis = analysis
                )
            } catch (_: Exception) {
                null
            }
        }
        .sortedByDescending { it.periodStartMs }
        .toList()
}

private fun searchNotebook(
    snapshot: AiAnalysisSnapshot,
    rawQuery: String
): List<AiAnalysisDocument> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return emptyList()
    return snapshot.searchable()
        .asSequence()
        .filter { doc ->
            periodLabel(doc).contains(query, ignoreCase = true) ||
                doc.analysis.toString().contains(query, ignoreCase = true)
        }
        .sortedByDescending { it.periodStartMs }
        .take(40)
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
    for (index in 0 until length()) optJSONObject(index)?.let(result::add)
    return result
}

private fun localDate(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

private fun periodLabel(doc: AiAnalysisDocument): String = when (doc.kind) {
    "daily" -> formatDay(doc.periodStartMs)
    "weekly" -> "週 ${formatPeriodRange(doc.periodStartMs, doc.periodEndMs)}"
    "monthly" -> {
        val month = YearMonth.from(localDate(doc.periodStartMs))
        "${month.year}年${month.monthValue}月"
    }
    "yearly" -> "${localDate(doc.periodStartMs).year}年"
    else -> formatDay(doc.periodStartMs)
}

private fun formatDay(ms: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.JAPAN).format(Date(ms))

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(ms))

private fun formatHourRange(startMs: Long, endMs: Long): String {
    val format = SimpleDateFormat("HH:mm", Locale.JAPAN)
    return "${format.format(Date(startMs))}–${format.format(Date(endMs))}"
}

private fun formatPeriodRange(startMs: Long, endMs: Long): String {
    val zone = ZoneId.systemDefault()
    val start = localDate(startMs, zone)
    val endExclusive = localDate(endMs, zone)
    val end = endExclusive.minus(1, ChronoUnit.DAYS)
    return if (start.year == end.year) {
        "${start.year}年${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
    } else {
        "${start.year}/${start.monthValue}/${start.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
    }
}
