from pathlib import Path
import re


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)

main_path = Path('app/src/main/java/com/sktpj/recorder24h/MainActivity.kt')
text = main_path.read_text()

text = replace_once(
    text,
    'import androidx.compose.foundation.background\n',
    'import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.combinedClickable\n',
    'foundation imports'
)
text = replace_once(
    text,
    'import androidx.compose.material3.CardDefaults\n',
    'import androidx.compose.material3.CardDefaults\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.DropdownMenuItem\n',
    'material imports'
)

caller_pattern = re.compile(
    r'''                        section == AppSection\.QUEUE -> QueueScreen\(\n.*?\n                        \)\n                        section == AppSection\.HISTORY ->''',
    re.S,
)
caller_replacement = '''                        section == AppSection.QUEUE -> QueueScreen(
                            records = records,
                            onSelect = { selectedId = it.segmentId },
                            onRemove = { record ->
                                val file = record.audioPath?.let { java.io.File(it) }
                                val removed = TranscriptionScheduler.removeFromQueue(context, record.segmentId, file)
                                if (removed) {
                                    refresh++
                                    Toast.makeText(context, "キューから削除しました", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        section == AppSection.HISTORY ->'''
text, count = caller_pattern.subn(caller_replacement, text, count=1)
if count != 1:
    raise SystemExit(f'queue caller: expected 1 match, found {count}')

queue_pattern = re.compile(
    r'''@Composable\nprivate fun QueueScreen\(.*?\n@Composable\nprivate fun HistoryScreen''',
    re.S,
)
queue_replacement = '''@Composable
private fun QueueScreen(
    records: List<SegmentRecord>,
    onSelect: (SegmentRecord) -> Unit,
    onRemove: (SegmentRecord) -> Unit
) {
    val queued = remember(records) {
        records
            .filter { it.status == "QUEUED" || it.status == "RETRY_WAIT" }
            .sortedBy {
                if (it.queueEnqueuedAtMs > 0L) it.queueEnqueuedAtMs else it.stateChangedAtMs
            }
    }

    if (queued.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("キューは空です", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(queued, key = { _, item -> item.segmentId }) { _, record ->
            QueueRow(
                record = record,
                onOpen = { onSelect(record) },
                onRemove = { onRemove(record) }
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueRow(
    record: SegmentRecord,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember(record.segmentId) { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatQueueDateTime(record),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                queueSourceLabel(record),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("キューから削除") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onRemove()
                }
            )
        }
    }
}

private fun queueSourceLabel(record: SegmentRecord): String =
    if (record.reason.orEmpty().startsWith("MANUAL_")) "ユーザー追加" else "自動追加"

private fun formatQueueDateTime(record: SegmentRecord): String {
    if (record.startedAtMs <= 0L) return "日時不明"
    val day = SimpleDateFormat("M/d", Locale.JAPAN).format(Date(record.startedAtMs))
    val time = SimpleDateFormat("HH:mm", Locale.JAPAN)
    val start = time.format(Date(record.startedAtMs))
    return if (record.endedAtMs > record.startedAtMs) {
        "$day $start–${time.format(Date(record.endedAtMs))}"
    } else {
        "$day $start"
    }
}

@Composable
private fun HistoryScreen'''
text, count = queue_pattern.subn(queue_replacement, text, count=1)
if count != 1:
    raise SystemExit(f'queue screen: expected 1 match, found {count}')

text = text.replace('"0.4.10-debug"', '"0.4.12-debug"')
main_path.write_text(text)

build_path = Path('app/build.gradle')
build = build_path.read_text()
build = replace_once(build, "versionCode 15", "versionCode 16", 'versionCode')
build = replace_once(build, "versionName '0.4.11'", "versionName '0.4.12'", 'versionName')
build = build.replace(
    '// 0.4.11 exposes queued/running/retry transcription work as a dedicated UI section.',
    '// 0.4.12 reduces the queue UI to one-line date/time + source rows with long-press deletion.'
)
build_path.write_text(build)
