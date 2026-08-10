from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one match in {path}, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


screen = Path("app/src/main/java/com/sktpj/recorder24h/AiAnalysisScreen.kt")
text = screen.read_text(encoding="utf-8")

old_refresh = '''            if (selectedDayStartMs == null ||
                next.daily.none { it.periodStartMs == selectedDayStartMs }
            ) {
                selectedDayStartMs = next.daily.firstOrNull()?.periodStartMs
            }
            if (selectedWeekStartMs == null ||
                next.weekly.none { it.periodStartMs == selectedWeekStartMs }
            ) {
                selectedWeekStartMs = next.weekly.firstOrNull()?.periodStartMs
            }
            if (selectedMonth == null) {
                selectedMonth = next.daily.firstOrNull()?.let {
                    YearMonth.from(localDate(it.periodStartMs, zone))
                } ?: YearMonth.now(zone)
            }
            if (selectedYear == null) {
                selectedYear = next.daily.firstOrNull()?.let {
                    localDate(it.periodStartMs, zone).year
                } ?: LocalDate.now(zone).year
            }
'''
new_refresh = '''            val today = LocalDate.now(zone)
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
'''
if old_refresh not in text:
    raise SystemExit("refresh block not found")
text = text.replace(old_refresh, new_refresh, 1)

old_day_call = '''                NotebookPeriod.DAY -> item {
                    val month = selectedMonth ?: YearMonth.now(zone)
                    DayNotebook(
                        snapshot = snapshot,
                        month = month,
                        selectedDayStartMs = selectedDayStartMs,
                        onMonthChange = { nextMonth ->
                            selectedMonth = nextMonth
                            selectedDayStartMs = snapshot.daily
                                .firstOrNull { YearMonth.from(localDate(it.periodStartMs, zone)) == nextMonth }
                                ?.periodStartMs
                        },
                        onSelectDay = { doc ->
                            selectedDayStartMs = doc.periodStartMs
                            selectedMonth = YearMonth.from(localDate(doc.periodStartMs, zone))
                            selectedYear = localDate(doc.periodStartMs, zone).year
                        }
                    )
                }
'''
new_day_call = '''                NotebookPeriod.DAY -> item {
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
'''
if old_day_call not in text:
    raise SystemExit("day call block not found")
text = text.replace(old_day_call, new_day_call, 1)

start_marker = '''@Composable
private fun DayNotebook('''
end_marker = '''@Composable
private fun WeekNotebook('''
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("DayNotebook function markers not found")
new_day_function = '''@Composable
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
                    message = "日次ノートは1日が完了した後に生成します。今日については、下に1時間ごとのLuna更新結果を表示します。"
                )
            }

            Text("今日の1時間ごとの更新", style = MaterialTheme.typography.titleLarge)
            val hourly = snapshot.hourly.filter {
                it.periodStartMs >= todayStartMs && it.periodStartMs < todayEndMs
            }
            if (hourly.isEmpty()) {
                EmptyAnalysisCard(
                    title = "今日の時間別まとめはまだありません",
                    message = "1時間単位のLuna分析が完了すると、最新の時間帯からここに表示されます。"
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

'''
text = text[:start] + new_day_function + text[end:]

old_calendar_label = '''                                    Text(
                                        if (doc != null) "●" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
'''
new_calendar_label = '''                                    Text(
                                        when {
                                            doc?.kind == "today" -> "今日"
                                            doc != null -> "●"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
'''
if old_calendar_label not in text:
    raise SystemExit("calendar label block not found")
text = text.replace(old_calendar_label, new_calendar_label, 1)

screen.write_text(text, encoding="utf-8")
replace_once("app/build.gradle", "        versionCode 24\n        versionName '0.6.3'", "        versionCode 25\n        versionName '0.6.4'")
