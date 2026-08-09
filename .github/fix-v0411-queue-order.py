from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text()
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count}, found {actual}: {old[:140]!r}")
    p.write_text(s.replace(old, new, count))

path = 'app/src/main/java/com/sktpj/recorder24h/ui/SegmentHistoryRepository.kt'
replace(path,
'''    val reason: String?,
    val stateChangedAtMs: Long,
    val audioPath: String?,''',
'''    val reason: String?,
    val stateChangedAtMs: Long,
    val queueEnqueuedAtMs: Long,
    val audioPath: String?,''')
replace(path,
'''        var latestEventMs: Long = 0L,
        var status: String = "READY",''',
'''        var latestEventMs: Long = 0L,
        var queueEnqueuedAtMs: Long = 0L,
        var status: String = "READY",''')
replace(path,
'''                stateChangedAtMs = builder.latestEventMs,
                audioPath = audio?.absolutePath,''',
'''                stateChangedAtMs = builder.latestEventMs,
                queueEnqueuedAtMs = builder.queueEnqueuedAtMs,
                audioPath = audio?.absolutePath,''')
replace(path,
'''                            builder.latestEventMs = maxOf(builder.latestEventMs, end)
                            builder.status = status''',
'''                            builder.latestEventMs = maxOf(builder.latestEventMs, end)
                            if (rawReason?.endsWith("WORK_ENQUEUED") == true && end > 0L) {
                                builder.queueEnqueuedAtMs = end
                            }
                            builder.status = status''')

path = 'app/src/main/java/com/sktpj/recorder24h/MainActivity.kt'
replace(path, 'import androidx.compose.material.icons.filled.Schedule\n', '')
replace(path, '        AppSection.QUEUE -> Icons.Filled.Schedule\n', '        AppSection.QUEUE -> Icons.Filled.Refresh\n')
replace(path,
'''    val waiting = remember(records) {
        records.filter { it.status == "QUEUED" }.sortedBy { it.stateChangedAtMs }
    }''',
'''    val waiting = remember(records) {
        records.filter { it.status == "QUEUED" }.sortedBy {
            if (it.queueEnqueuedAtMs > 0L) it.queueEnqueuedAtMs else it.stateChangedAtMs
        }
    }''')
replace(path,
'''            if (record.stateChangedAtMs > 0L) {
                Text(
                    "状態更新 ${formatDateTime(record.stateChangedAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }''',
'''            if (record.queueEnqueuedAtMs > 0L && record.status in setOf("QUEUED", "TRANSCRIBING", "RETRY_WAIT")) {
                Text(
                    "キュー登録 ${formatDateTime(record.queueEnqueuedAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.stateChangedAtMs > 0L) {
                Text(
                    "状態更新 ${formatDateTime(record.stateChangedAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }''')
replace(path,
'''private fun queueSourceLabel(record: SegmentRecord): String =
    if (record.reason.orEmpty().startsWith("MANUAL_")) "ユーザー追加" else "自動追加"''',
'''private fun queueSourceLabel(record: SegmentRecord): String = when {
    record.status == "READY" -> "キュー外"
    record.reason.orEmpty().startsWith("MANUAL_") -> "ユーザー追加"
    else -> "自動追加"
}''')
