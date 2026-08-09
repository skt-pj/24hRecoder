from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count}, found {actual}: {old[:160]!r}")
    p.write_text(s.replace(old, new, count))


# ---------- Segment history: expose the latest state-change time for queue ordering ----------
path = 'app/src/main/java/com/sktpj/recorder24h/ui/SegmentHistoryRepository.kt'
replace(path,
'''    val status: String,
    val reason: String?,
    val audioPath: String?,''',
'''    val status: String,
    val reason: String?,
    val stateChangedAtMs: Long,
    val audioPath: String?,''')
replace(path,
'''                status = builder.status,
                reason = builder.reason,
                audioPath = audio?.absolutePath,''',
'''                status = builder.status,
                reason = builder.reason,
                stateChangedAtMs = builder.latestEventMs,
                audioPath = audio?.absolutePath,''')

# ---------- Scheduler: allow a queued/retry item to be removed explicitly ----------
path = 'app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionScheduler.java'
replace(path,
'''    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        return enqueueInternal(context, segmentId, file, true, ExistingWorkPolicy.REPLACE);
    }

    private static boolean enqueueAfterReset''',
'''    public static boolean enqueueForceRetranscription(Context context, String segmentId, File file) {
        return enqueueInternal(context, segmentId, file, true, ExistingWorkPolicy.REPLACE);
    }

    public static boolean removeFromQueue(Context context, String segmentId, File file) {
        if (segmentId == null || segmentId.isEmpty()) {
            return false;
        }
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(uniqueWorkName(segmentId));
        boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);
        SegmentRepository.appendWithoutNotify(
                context,
                segmentId,
                file,
                0L,
                System.currentTimeMillis(),
                hasTranscript ? "TRANSCRIBED" : "READY",
                "USER_REMOVED_FROM_TRANSCRIPTION_QUEUE");
        log(context, "TRANSCRIPTION_QUEUE_ITEM_REMOVED", segmentId, file,
                hasTranscript ? "OLD_TRANSCRIPT_RETAINED" : "AUDIO_RETAINED");
        return true;
    }

    private static boolean enqueueAfterReset''')

# If a queued Worker is cancelled while it is waiting for the Whisper lock, restore a stable
# journal state instead of leaving QUEUED behind.
path = 'app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionWorker.java'
replace(path,
'''            if (isStopped()) {
                log(context, "LOCAL_TRANSCRIPTION_STOPPED_BEFORE_START", segmentId, audioFile,
                        null, forceMetrics(forceRetranscribe), attempt);
                return Result.failure();
            }''',
'''            if (isStopped()) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                        System.currentTimeMillis(),
                        TranscriptionRepository.exists(context, segmentId) ? "TRANSCRIBED" : "READY",
                        "USER_REMOVED_FROM_TRANSCRIPTION_QUEUE");
                log(context, "LOCAL_TRANSCRIPTION_STOPPED_BEFORE_START", segmentId, audioFile,
                        "QUEUE_ITEM_REMOVED", forceMetrics(forceRetranscribe), attempt);
                return Result.failure();
            }''')

# ---------- Main UI ----------
path = 'app/src/main/java/com/sktpj/recorder24h/MainActivity.kt'
replace(path,
'import androidx.compose.material.icons.filled.Search\n',
'import androidx.compose.material.icons.filled.Search\nimport androidx.compose.material.icons.filled.Schedule\n')
replace(path,
'''private enum class AppSection(val label: String) {
    HOME("ホーム"), HISTORY("記録"), SETTINGS("設定")
}''',
'''private enum class AppSection(val label: String) {
    HOME("ホーム"), QUEUE("キュー"), HISTORY("記録"), SETTINGS("設定")
}''')
replace(path,
'''    LaunchedEffect(section, refresh, selectedId) {
        if (section == AppSection.HISTORY) {
            do {
                records = withContext(Dispatchers.IO) { SegmentHistoryRepository.load(context) }
                delay(if (selectedId != null) 1_000L else 5_000L)
            } while (section == AppSection.HISTORY)
        }
    }''',
'''    LaunchedEffect(section, refresh, selectedId) {
        if (section == AppSection.HISTORY || section == AppSection.QUEUE) {
            do {
                records = withContext(Dispatchers.IO) { SegmentHistoryRepository.load(context) }
                delay(if (selectedId != null || section == AppSection.QUEUE) 1_000L else 5_000L)
            } while (section == AppSection.HISTORY || section == AppSection.QUEUE)
        }
    }''')
replace(path,
'''                        if (section == AppSection.HISTORY && selected == null) {
                            IconButton(onClick = { refresh++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "記録を更新")
                            }
                        }''',
'''                        if ((section == AppSection.HISTORY || section == AppSection.QUEUE) && selected == null) {
                            IconButton(onClick = { refresh++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "表示を更新")
                            }
                        }''')
replace(path,
'''                        section == AppSection.HISTORY -> HistoryScreen(records) { selectedId = it.segmentId }
                        else -> SettingsScreen(''',
'''                        section == AppSection.QUEUE -> QueueScreen(
                            records = records,
                            onSelect = { selectedId = it.segmentId },
                            onRemove = { record ->
                                val file = record.audioPath?.let { java.io.File(it) }
                                val removed = TranscriptionScheduler.removeFromQueue(context, record.segmentId, file)
                                if (removed) {
                                    refresh++
                                    Toast.makeText(context, "キューから外しました。音声は残します", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onAdd = { record ->
                                val audioPath = record.audioPath
                                val added = audioPath != null && TranscriptionScheduler.enqueueForceRetranscription(
                                    context, record.segmentId, java.io.File(audioPath)
                                )
                                if (added) {
                                    refresh++
                                    Toast.makeText(context, "文字起こしキューに追加しました", Toast.LENGTH_SHORT).show()
                                } else if (!WhisperModelManager.isReady(context)) {
                                    Toast.makeText(context, "large-v3 Q5 / VADモデルが未準備です", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "キューに追加できませんでした", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        section == AppSection.HISTORY -> HistoryScreen(records) { selectedId = it.segmentId }
                        else -> SettingsScreen(''')
replace(path,
'''private fun sectionTitle(section: AppSection) = when (section) {
    AppSection.HOME -> "24hRecoder"
    AppSection.HISTORY -> "記録"
    AppSection.SETTINGS -> "設定"
}''',
'''private fun sectionTitle(section: AppSection) = when (section) {
    AppSection.HOME -> "24hRecoder"
    AppSection.QUEUE -> "文字起こしキュー"
    AppSection.HISTORY -> "記録"
    AppSection.SETTINGS -> "設定"
}''')
replace(path,
'''    val icon = when (section) {
        AppSection.HOME -> Icons.Filled.Home
        AppSection.HISTORY -> Icons.Filled.List
        AppSection.SETTINGS -> Icons.Filled.Settings
    }''',
'''    val icon = when (section) {
        AppSection.HOME -> Icons.Filled.Home
        AppSection.QUEUE -> Icons.Filled.Schedule
        AppSection.HISTORY -> Icons.Filled.List
        AppSection.SETTINGS -> Icons.Filled.Settings
    }''')

# Insert the queue screen before HistoryScreen.
marker = '''@Composable
private fun HistoryScreen(records: List<SegmentRecord>, onSelect: (SegmentRecord) -> Unit) {'''
queue_ui = r'''@Composable
private fun QueueScreen(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit,
    onRemove: (SegmentRecord) -> Unit,
    onAdd: (SegmentRecord) -> Unit
) {
    val running = remember(records) {
        records.filter { it.status == "TRANSCRIBING" }.sortedBy { it.stateChangedAtMs }
    }
    val waiting = remember(records) {
        records.filter { it.status == "QUEUED" }.sortedBy { it.stateChangedAtMs }
    }
    val retry = remember(records) {
        records.filter { it.status == "RETRY_WAIT" }.sortedBy { it.stateChangedAtMs }
    }
    val failed = remember(records) {
        records.filter { it.status == "FAILED" && it.audioAvailable }.sortedByDescending { it.stateChangedAtMs }
    }
    val unqueued = remember(records) {
        records.filter { it.status == "READY" && it.audioAvailable }
            .sortedByDescending { it.sortTimeMs }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("現在のキュー", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("実行中", "${running.size}件")
                        Metric("待機", "${waiting.size}件")
                        Metric("再試行", "${retry.size}件")
                    }
                    Text(
                        "カードをタップすると、その音声の記録詳細を開きます。追加元（自動/ユーザー）は状態ではなく補助情報として表示します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                    )
                }
            }
        }

        if (running.isNotEmpty()) {
            item { QueueSectionHeader("実行中", "Whisper large-v3 Q5が現在処理している音声") }
            itemsIndexed(running, key = { _, item -> "running:${item.segmentId}" }) { _, record ->
                QueueRecordCard(record, onOpen = { onSelect(record) })
            }
        }

        if (waiting.isNotEmpty()) {
            item { QueueSectionHeader("待機中", "上から登録時刻の古い順。状態理由を省略せず表示") }
            itemsIndexed(waiting, key = { _, item -> "waiting:${item.segmentId}" }) { index, record ->
                QueueRecordCard(
                    record,
                    position = index + 1,
                    onOpen = { onSelect(record) },
                    actionLabel = "キューから外す",
                    onAction = { onRemove(record) }
                )
            }
        }

        if (retry.isNotEmpty()) {
            item { QueueSectionHeader("再試行待ち", "前回処理が失敗し、WorkManagerの再試行を待っている音声") }
            itemsIndexed(retry, key = { _, item -> "retry:${item.segmentId}" }) { _, record ->
                QueueRecordCard(
                    record,
                    onOpen = { onSelect(record) },
                    actionLabel = "キューから外す",
                    onAction = { onRemove(record) }
                )
            }
        }

        if (running.isEmpty() && waiting.isEmpty() && retry.isEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("キューは空です", style = MaterialTheme.typography.titleLarge)
                        Text("保存済み音声は下の「キュー外の音声」から追加できます。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (failed.isNotEmpty()) {
            item { QueueSectionHeader("失敗・要確認", "キューからは外れています。音声詳細を開いて原因を確認できます") }
            itemsIndexed(failed, key = { _, item -> "failed:${item.segmentId}" }) { _, record ->
                QueueRecordCard(
                    record,
                    onOpen = { onSelect(record) },
                    actionLabel = "キューに追加",
                    onAction = { onAdd(record) }
                )
            }
        }

        if (unqueued.isNotEmpty()) {
            item { QueueSectionHeader("キュー外の音声", "音声は残っていますが、現在キューには入っていません") }
            itemsIndexed(unqueued, key = { _, item -> "ready:${item.segmentId}" }) { _, record ->
                QueueRecordCard(
                    record,
                    onOpen = { onSelect(record) },
                    actionLabel = "キューに追加",
                    onAction = { onAdd(record) }
                )
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(title: String, description: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueRecordCard(
    record: SegmentRecord,
    position: Int? = null,
    onOpen: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (position != null) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            position.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(formatTimeRange(record), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(formatDay(record.sortTimeMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(queueStateLabel(record), queueStateTone(record))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(queueSourceLabel(record), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (record.audioAvailable) SegmentHistoryRepository.formatBytes(record.fileSizeBytes) else "音声なし",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                queueStateDetail(record),
                style = MaterialTheme.typography.bodyMedium,
                color = if (record.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (record.stateChangedAtMs > 0L) {
                Text(
                    "状態更新 ${formatDateTime(record.stateChangedAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (actionLabel != null && onAction != null) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAction) {
                        if (actionLabel.contains("外す")) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(actionLabel)
                    }
                }
            } else {
                Text(
                    "タップして音声・会話ログを開く",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun queueSourceLabel(record: SegmentRecord): String =
    if (record.reason.orEmpty().startsWith("MANUAL_")) "ユーザー追加" else "自動追加"

private fun queueStateLabel(record: SegmentRecord): String = when (record.status) {
    "QUEUED" -> if (record.reason.orEmpty().endsWith("SLOT_WAIT")) "Whisper枠待ち" else "Worker開始待ち"
    "TRANSCRIBING" -> "実行中"
    "RETRY_WAIT" -> "再試行待ち"
    "FAILED" -> "失敗"
    "READY" -> "キュー外"
    else -> record.status
}

private fun queueStateTone(record: SegmentRecord): StatusTone = when (record.status) {
    "FAILED" -> StatusTone.ERROR
    "TRANSCRIBING", "QUEUED", "RETRY_WAIT" -> StatusTone.WAITING
    else -> StatusTone.NEUTRAL
}

private fun queueStateDetail(record: SegmentRecord): String = when {
    record.status == "QUEUED" && record.reason.orEmpty().endsWith("SLOT_WAIT") ->
        "Workerは起動済みです。他のWhisper処理またはモデル比較がLocalWhisperEngineの排他枠を使用しているため待機しています。"
    record.status == "QUEUED" && record.reason.orEmpty().endsWith("WORK_ENQUEUED") ->
        "WorkManagerへ登録済みですがWorkerはまだ開始していません。現行実装で明示している制約はbattery-not-lowです。OSスケジューラ待ちとの区別はまだ計測していません。"
    record.status == "TRANSCRIBING" ->
        "Whisper実行枠を取得済みです。M4A復号・前処理・VAD・large-v3 Q5推論・保存の処理中です。"
    record.status == "RETRY_WAIT" ->
        "前回処理で例外が発生しました。最大3回まで指数バックオフで再試行します。"
    record.status == "FAILED" ->
        "文字起こし処理は終了しています。理由: ${record.reason ?: "詳細不明"}"
    record.status == "READY" ->
        "音声は保存されていますが、現在の文字起こしキューには登録されていません。"
    else -> record.reason ?: record.status
}

@Composable
private fun HistoryScreen(records: List<SegmentRecord>, onSelect: (SegmentRecord) -> Unit) {'''
replace(path, marker, queue_ui)

# Make the detail action wording work for both first transcription and retranscription.
replace(path,
'''                    Text(when {
                        manualRetranscriptionActive && record.status == "TRANSCRIBING" -> "手動再文字起こし中…"
                        manualRetranscriptionActive -> "手動再文字起こし待ち…"
                        record.status == "QUEUED" -> "この音声を手動で再文字起こし"
                        else -> "この音声を再文字起こし"
                    })''',
'''                    Text(when {
                        record.status == "TRANSCRIBING" -> "文字起こし中…"
                        record.status == "QUEUED" || record.status == "RETRY_WAIT" -> "文字起こしキュー待ち…"
                        record.hasTranscript -> "この音声を再文字起こし"
                        else -> "文字起こしキューに追加"
                    })''')
replace(path,
'''                Text(
                    "処理済みの文字起こしでも、元M4Aが残っていれば何度でも再実行できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )''',
'''                Text(
                    if (record.hasTranscript) "元M4Aが残っていれば、現在の結果を保持したまま再度キューへ追加できます。"
                    else "保存済みM4Aを文字起こしキューへ追加します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )''')

# Do not merge two distinct QUEUED reasons into one vague sentence.
old_message = '''private fun transcriptionActivityMessage(record: SegmentRecord): String? {
    return when {
        isManualRetranscriptionState(record) && record.status == "QUEUED" ->
            "手動再文字起こしを登録済みです。Whisper処理枠または低バッテリー制約の解除を待っています。"
        isManualRetranscriptionState(record) && record.status == "READY" ->
            "手動再文字起こしの開始待ちです。"
        isManualRetranscriptionState(record) && record.status == "TRANSCRIBING" ->
            "Whisper large-v3 Q5で手動再文字起こし中です。"
        isManualRetranscriptionState(record) && record.status == "RETRY_WAIT" ->
            "手動再文字起こしが一時失敗し、再試行を待っています。"
        record.status == "QUEUED" ->
            "自動文字起こしを登録済みです。Whisper処理枠または低バッテリー制約の解除を待っています。"
        record.status == "TRANSCRIBING" ->
            "Whisper large-v3 Q5で端末内文字起こし中です。"
        record.status == "RETRY_WAIT" ->
            "文字起こしが一時失敗し、再試行を待っています。"
        record.status == "READY" && record.audioAvailable ->
            "音声は保存済みです。文字起こしWorkerの登録またはモデル準備を待っています。"
        else -> null
    }
}'''
new_message = '''private fun transcriptionActivityMessage(record: SegmentRecord): String? {
    return when {
        record.status == "QUEUED" && record.reason.orEmpty().endsWith("SLOT_WAIT") ->
            "Worker起動済み。現在はWhisper実行枠を待っています。他のWhisper処理またはモデル比較が実行枠を使用中です。"
        record.status == "QUEUED" && record.reason.orEmpty().endsWith("WORK_ENQUEUED") ->
            "WorkManager登録済み・Worker未開始です。明示制約はbattery-not-lowです。OSスケジューラ待ちとの区別は現時点では計測していません。"
        record.status == "TRANSCRIBING" ->
            "Whisper large-v3 Q5の実行枠を取得済みです。端末内で文字起こし処理中です。"
        record.status == "RETRY_WAIT" ->
            "前回処理が失敗し、WorkManagerの再試行を待っています。"
        record.status == "READY" && record.audioAvailable ->
            "音声は保存済みですが、現在は文字起こしキュー外です。"
        else -> null
    }
}'''
replace(path, old_message, new_message)

# Manual/automatic is now source metadata; status labels are common.
start = '''private fun recordStatusLabel(record: SegmentRecord): String {
    if (isManualRetranscriptionState(record)) {
        return when (record.status) {
            "QUEUED", "READY" -> "手動再文字起こし待ち"
            "TRANSCRIBING" -> "手動再文字起こし中"
            "RETRY_WAIT" -> "手動再文字起こし再試行待ち"
            "FAILED" -> "手動再文字起こし失敗"
            else -> if (record.hasTranscript) "文字起こし済み" else record.status
        }
    }
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
}'''
common = '''private fun recordStatusLabel(record: SegmentRecord): String {
    return when (record.status) {
        "QUEUED" -> "キュー待ち"
        "TRANSCRIBING" -> "文字起こし中"
        "RETRY_WAIT" -> "再試行待ち"
        "FAILED" -> "失敗"
        "READY" -> if (record.hasTranscript) "文字起こし済み" else "キュー外"
        "CORRUPT" -> "破損"
        "DELETED" -> if (record.hasTranscript) "文字起こし済み" else "削除済み"
        "TRANSCRIBED" -> "文字起こし済み"
        else -> if (record.hasTranscript) "文字起こし済み" else record.status
    }
}'''
replace(path, start, common)
replace(path, '"0.4.10-debug"', '"0.4.11-debug"', count=2)

# ---------- version ----------
path = 'app/build.gradle'
replace(path, "        versionCode 14\n        versionName '0.4.10'", "        versionCode 15\n        versionName '0.4.11'")

# ---------- README ----------
path = 'README.md'
replace(path, '- 現在のdebug APK: `0.4.10-debug` / versionCode 14', '- 現在のdebug APK: `0.4.11-debug` / versionCode 15')
p = Path(path)
s = p.read_text()
marker = '## 文字起こしキュー\n'
addition = '''### 0.4.11: 文字起こしキュー一覧\n\n0.4.11-debugでは「ホーム / キュー / 記録 / 設定」の4画面構成とし、現在の文字起こしWorkを専用のキュー画面で確認する。実行中・待機中・再試行待ち・失敗/要確認・キュー外音声を区分して表示し、各カードは録音時刻、追加元、状態更新時刻、状態理由を表示する。カード全体をタップすると同じsegmentIdの記録詳細（音声再生・会話ログ）へ遷移する。\n\n待機中/再試行待ちはキュー画面から明示的に外すことができ、WorkManagerの`transcribe:<segmentId>`をcancelして、元音声と既存文字起こしは保持する。キュー外/失敗音声は同じ画面から再追加できる。手動/自動は別状態として扱わず追加元の補助情報とし、QUEUEDは`WORK_ENQUEUED`（WorkManager登録済み・Worker未開始）と`SLOT_WAIT`（Worker起動済み・Whisper排他枠待ち）を分けて説明する。\n\n'''
if marker not in s:
    raise SystemExit('README queue marker not found')
p.write_text(s.replace(marker, addition + marker, 1))
