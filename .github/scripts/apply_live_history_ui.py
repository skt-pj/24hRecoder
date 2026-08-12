from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing pattern: {label}")
    return text.replace(old, new, 1)

# Version
p = Path('app/build.gradle')
s = p.read_text()
s = replace_once(s, "versionCode 1018\n        versionName '0.7.18'", "versionCode 1019\n        versionName '0.7.19'", 'version')
s = replace_once(s, "// The persisted transcription backlog has explicit pause/resume; pause aborts current postprocess inference.\n", "// The persisted transcription backlog has explicit pause/resume; pause aborts current postprocess inference.\n        // Record history exposes a live view backed by durable rolling full-streaming final utterances.\n", 'version comment')
p.write_text(s)

# Rolling live-final store
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/FullStreamingStateStore.java')
s = p.read_text()
s = replace_once(s, "import java.nio.file.StandardCopyOption;\n", "import java.nio.file.StandardCopyOption;\nimport java.util.ArrayList;\nimport java.util.List;\n", 'store imports')
s = replace_once(s, "    private static final String DIR = \"metadata/full-streaming\";\n", "    private static final String DIR = \"metadata/full-streaming\";\n    private static final String RECENT_FILE = \"recent.json\";\n    private static final long RECENT_RETENTION_MS = 24L * 60L * 60L * 1000L;\n    private static final int RECENT_MAX_ENTRIES = 1000;\n", 'store constants')
insert_after = '''    public static LiveState readLiveState(Context context) {
        File file = currentFile(context);
        if (!file.isFile()) return LiveState.empty();
        try {
            JSONObject row = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return new LiveState(
                    row.optString("state", "OFF"),
                    row.isNull("backend") ? null : row.optString("backend", null),
                    row.optString("partialText", ""),
                    row.optString("latestFinalText", ""),
                    row.optString("accumulatedText", ""),
                    row.optInt("queueDepth", 0),
                    row.isNull("error") ? null : row.optString("error", null),
                    row.optLong("updatedAtMs", 0L));
        } catch (Exception ignored) {
            return LiveState.empty();
        }
    }
'''
append_methods = insert_after + '''
    /** Durable rolling final utterances for the Record > Realtime view. */
    public static void appendRecentFinal(Context context,
                                         long startAtMs, long endAtMs,
                                         long startPtsUs, long endPtsUs,
                                         String text, String backend,
                                         JSONArray segments) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                long cutoff = now - RECENT_RETENTION_MS;
                JSONObject root = readRecentRoot(context);
                JSONArray old = root.optJSONArray("entries");
                if (old == null) old = new JSONArray();
                JSONArray kept = new JSONArray();
                int earliestIndex = Math.max(0, old.length() - (RECENT_MAX_ENTRIES - 1));
                for (int i = earliestIndex; i < old.length(); i++) {
                    JSONObject row = old.optJSONObject(i);
                    if (row == null) continue;
                    long rowEnd = row.optLong("endAtMs", row.optLong("createdAtMs", 0L));
                    if (rowEnd >= cutoff) kept.put(row);
                }
                JSONObject entry = new JSONObject();
                entry.put("id", now + "-" + startPtsUs + "-" + endPtsUs);
                entry.put("startAtMs", Math.max(0L, startAtMs));
                entry.put("endAtMs", Math.max(startAtMs, endAtMs));
                entry.put("startPtsUs", startPtsUs);
                entry.put("endPtsUs", endPtsUs);
                entry.put("text", text.trim());
                entry.put("backend", backend == null ? JSONObject.NULL : backend);
                String speaker = firstSpeaker(segments);
                entry.put("speaker", speaker == null ? JSONObject.NULL : speaker);
                entry.put("segments", segments == null ? new JSONArray() : new JSONArray(segments.toString()));
                entry.put("createdAtMs", now);
                kept.put(entry);
                root.put("schemaVersion", 1);
                root.put("retentionMs", RECENT_RETENTION_MS);
                root.put("updatedAtMs", now);
                root.put("entries", kept);
                writeAtomic(recentFile(context), root.toString());
            } catch (Exception ignored) {
                // Live display persistence must never fail the authoritative transcription.
            }
        }
    }

    public static List<RecentFinal> readRecentFinals(Context context) {
        List<RecentFinal> out = new ArrayList<>();
        synchronized (LOCK) {
            try {
                JSONObject root = readRecentRoot(context);
                JSONArray rows = root.optJSONArray("entries");
                if (rows == null) return out;
                long cutoff = System.currentTimeMillis() - RECENT_RETENTION_MS;
                int start = Math.max(0, rows.length() - RECENT_MAX_ENTRIES);
                for (int i = start; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    long endAtMs = row.optLong("endAtMs", 0L);
                    if (endAtMs > 0L && endAtMs < cutoff) continue;
                    out.add(new RecentFinal(
                            row.optString("id", "live-" + i),
                            row.optLong("startAtMs", 0L),
                            endAtMs,
                            row.optLong("startPtsUs", -1L),
                            row.optLong("endPtsUs", -1L),
                            row.optString("text", ""),
                            row.isNull("speaker") ? null : row.optString("speaker", null),
                            row.isNull("backend") ? null : row.optString("backend", null)));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static JSONObject readRecentRoot(Context context) {
        File file = recentFile(context);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String firstSpeaker(JSONArray segments) {
        if (segments == null) return null;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject row = segments.optJSONObject(i);
            if (row == null) continue;
            String speaker = row.optString("speaker", "").trim();
            if (!speaker.isEmpty()) return speaker;
            speaker = row.optString("speakerId", "").trim();
            if (!speaker.isEmpty()) return speaker;
        }
        return null;
    }
'''
s = replace_once(s, insert_after, append_methods, 'recent methods')
s = replace_once(s, '''    private static File currentFile(Context context) {
        return new File(dir(context), "current.json");
    }
''', '''    private static File currentFile(Context context) {
        return new File(dir(context), "current.json");
    }

    private static File recentFile(Context context) {
        return new File(dir(context), RECENT_FILE);
    }
''', 'recent file')
class_anchor = '''    public static final class LiveState {
'''
recent_class = '''    public static final class RecentFinal {
        public final String id;
        public final long startAtMs;
        public final long endAtMs;
        public final long startPtsUs;
        public final long endPtsUs;
        public final String text;
        public final String speaker;
        public final String backend;

        RecentFinal(String id, long startAtMs, long endAtMs,
                    long startPtsUs, long endPtsUs, String text,
                    String speaker, String backend) {
            this.id = id;
            this.startAtMs = startAtMs;
            this.endAtMs = endAtMs;
            this.startPtsUs = startPtsUs;
            this.endPtsUs = endPtsUs;
            this.text = text;
            this.speaker = speaker;
            this.backend = backend;
        }
    }

'''
s = replace_once(s, class_anchor, recent_class + class_anchor, 'recent class')
p.write_text(s)

# Append authoritative live finals to rolling view state.
p = Path('app/src/main/java/com/sktpj/recorder24h/transcription/StreamingTranscriptionService.java')
s = p.read_text()
s = replace_once(s, '''        lastScheduledFinalEndUs = endUs;
        submit("final", () -> {
''', '''        lastScheduledFinalEndUs = endUs;
        final long queuedAtMs = System.currentTimeMillis();
        submit("final", () -> {
''', 'queued wall time')
s = replace_once(s, '''                Recognition result = recognize(samples, accumulator, false);
                accumulator.addFinal(result, startUs, endUs);
                writeState("FINAL", "", accumulator.latestFinalText, accumulator, null);
''', '''                Recognition result = recognize(samples, accumulator, false);
                accumulator.addFinal(result, startUs, endUs);
                long speechDurationMs = Math.max(0L, (endUs - startUs) / 1000L);
                long endAtMs = queuedAtMs;
                long startAtMs = Math.max(0L, endAtMs - speechDurationMs);
                FullStreamingStateStore.appendRecentFinal(
                        this, startAtMs, endAtMs, startUs, endUs,
                        result.text, accumulator.config.asrBackend, result.segments);
                writeState("FINAL", "", accumulator.latestFinalText, accumulator, null);
''', 'append final')
p.write_text(s)

# Record UI live/history switch.
p = Path('app/src/main/java/com/sktpj/recorder24h/MainActivity.kt')
s = p.read_text()
s = replace_once(s, 'import com.sktpj.recorder24h.transcription.LocalWhisperEngine\n', 'import com.sktpj.recorder24h.transcription.FullStreamingStateStore\nimport com.sktpj.recorder24h.transcription.LocalWhisperEngine\n', 'main import')
s = replace_once(s, '''private enum class HistoryFilter(val label: String) {
    ALL("すべて"), TRANSCRIBED("文字起こし済み"), AUDIO("音声あり"), ATTENTION("要確認")
}
''', '''private enum class HistoryFilter(val label: String) {
    ALL("すべて"), TRANSCRIBED("文字起こし済み"), AUDIO("音声あり"), ATTENTION("要確認")
}

private enum class HistoryViewMode(val label: String) {
    HISTORY("履歴"), REALTIME("リアルタイム")
}
''', 'history mode enum')
s = replace_once(s, '''    var historyQuery by remember { mutableStateOf("") }
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }
''', '''    var historyQuery by remember { mutableStateOf("") }
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var historyViewMode by remember { mutableStateOf(HistoryViewMode.HISTORY) }
    var liveState by remember { mutableStateOf<FullStreamingStateStore.LiveState?>(null) }
    var liveFinals by remember { mutableStateOf<List<FullStreamingStateStore.RecentFinal>>(emptyList()) }
''', 'history live state')
loop_anchor = '''    LaunchedEffect(section, refresh, selectedId) {
        if (section == AppSection.HISTORY || section == AppSection.QUEUE) {
            do {
                records = withContext(Dispatchers.IO) { SegmentHistoryRepository.load(context) }
                delay(if (selectedId != null || section == AppSection.QUEUE) 1_000L else 5_000L)
            } while (section == AppSection.HISTORY || section == AppSection.QUEUE)
        }
    }
'''
loop_new = loop_anchor + '''    LaunchedEffect(section, historyViewMode, refresh) {
        if (section == AppSection.HISTORY && historyViewMode == HistoryViewMode.REALTIME) {
            do {
                val snapshot = withContext(Dispatchers.IO) {
                    FullStreamingStateStore.readLiveState(context) to
                        FullStreamingStateStore.readRecentFinals(context)
                }
                liveState = snapshot.first
                liveFinals = snapshot.second
                delay(500L)
            } while (section == AppSection.HISTORY && historyViewMode == HistoryViewMode.REALTIME)
        }
    }
'''
s = replace_once(s, loop_anchor, loop_new, 'live poll loop')
s = replace_once(s, '''                        section == AppSection.HISTORY -> HistoryScreen(
                            records = records,
                            listState = historyListState,
                            query = historyQuery,
                            filter = historyFilter,
                            onQueryChange = { historyQuery = it },
                            onFilterChange = { historyFilter = it },
                            onSelect = { selectedId = it.segmentId }
                        )
''', '''                        section == AppSection.HISTORY -> HistorySection(
                            records = records,
                            listState = historyListState,
                            query = historyQuery,
                            filter = historyFilter,
                            viewMode = historyViewMode,
                            liveState = liveState,
                            liveFinals = liveFinals,
                            onViewModeChange = { historyViewMode = it },
                            onQueryChange = { historyQuery = it },
                            onFilterChange = { historyFilter = it },
                            onSelect = { selectedId = it.segmentId }
                        )
''', 'history call')

history_anchor = '''@Composable
private fun HistoryScreen(
'''
live_ui = '''@Composable
private fun HistorySection(
    records: List<SegmentRecord>,
    listState: LazyListState,
    query: String,
    filter: HistoryFilter,
    viewMode: HistoryViewMode,
    liveState: FullStreamingStateStore.LiveState?,
    liveFinals: List<FullStreamingStateStore.RecentFinal>,
    onViewModeChange: (HistoryViewMode) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilter) -> Unit,
    onSelect: (SegmentRecord) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(HistoryViewMode.entries) { _, mode ->
                FilterChip(
                    selected = viewMode == mode,
                    onClick = { onViewModeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (viewMode == HistoryViewMode.HISTORY) {
                HistoryScreen(records, listState, query, filter, onQueryChange, onFilterChange, onSelect)
            } else {
                LiveHistoryScreen(liveState, liveFinals)
            }
        }
    }
}

@Composable
private fun LiveHistoryScreen(
    liveState: FullStreamingStateStore.LiveState?,
    finals: List<FullStreamingStateStore.RecentFinal>
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val pipeline = TranscriptionPipelineSettings.snapshot(context)
    val state = liveState?.state ?: "OFF"
    val partial = liveState?.partialText.orEmpty()
    val backendId = liveState?.backend ?: pipeline.asrBackend
    val backendLabel = TranscriptionPipelineSettings.asrLabel(backendId)
    val vadLabel = TranscriptionPipelineSettings.vadLabel(pipeline.vadBackend)
    val stateLabel = when (state) {
        "LIVE_PARTIAL" -> "認識中"
        "FINAL" -> "確定"
        "WAITING" -> "待機中"
        "ERROR" -> "エラー"
        else -> "停止"
    }
    val stateTone = when (state) {
        "LIVE_PARTIAL", "FINAL" -> StatusTone.SUCCESS
        "WAITING" -> StatusTone.WAITING
        "ERROR" -> StatusTone.ERROR
        else -> StatusTone.NEUTRAL
    }

    LaunchedEffect(finals.size, liveState?.updatedAtMs) {
        if (finals.isNotEmpty()) {
            listState.scrollToItem(finals.lastIndex)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ライブ文字起こし", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    StatusPill(stateLabel, stateTone)
                }
                Text("$backendLabel / $vadLabel", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("推論キュー ${liveState?.queueDepth ?: 0}", style = MaterialTheme.typography.labelMedium)
                    if ((liveState?.updatedAtMs ?: 0L) > 0L) {
                        Text("更新 ${formatLiveTime(liveState!!.updatedAtMs)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!liveState?.error.isNullOrBlank()) {
                    Text(liveState!!.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state == "OFF" && !TranscriptionPipelineSettings.isLiveStreaming(pipeline)) {
                    Text("設定で「完全ストリーミング」を選ぶと、この画面に会話がリアルタイム表示されます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (finals.isEmpty() && partial.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("まだ確定したライブ会話はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(finals, key = { _, item -> item.id }) { _, item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatLiveTime(item.startAtMs), style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    item.speaker?.takeIf { it.isNotBlank() } ?: "確定",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(item.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (partial.isNotBlank()) {
                    item(key = "live-partial") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("認識中（暫定）", style = MaterialTheme.typography.labelLarge)
                                Text(partial, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLiveTime(value: Long): String {
    if (value <= 0L) return "--:--:--"
    return SimpleDateFormat("M/d HH:mm:ss", Locale.JAPAN).format(Date(value))
}

'''
s = replace_once(s, history_anchor, live_ui + history_anchor, 'live UI')
p.write_text(s)
