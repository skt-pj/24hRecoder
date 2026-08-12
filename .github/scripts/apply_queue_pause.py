from pathlib import Path
import re


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing replacement target: {label}")
    return text.replace(old, new, 1)


scheduler = Path("app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionScheduler.java")
s = scheduler.read_text()
s = replace_once(
    s,
    '    private static final String KEY_SINGLE_RUNNER_MIGRATED = "single_runner_migrated_v1";\n',
    '    private static final String KEY_SINGLE_RUNNER_MIGRATED = "single_runner_migrated_v1";\n'
    '    private static final String KEY_QUEUE_PAUSED = "queue_paused";\n',
    "scheduler pause key",
)
pattern = re.compile(
    r"    public static boolean isBacklogPaused\(Context context\) \{.*?\n    public static void enqueue\(Context context, String segmentId, File file\) \{",
    re.S,
)
manual_block = '''    public static boolean isQueuePaused(Context context) {
        Context app = context.getApplicationContext();
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_QUEUE_PAUSED, false);
    }

    /**
     * Explicit user-controlled pause for the persisted transcription backlog.
     * A currently running item is not cancelled; the drain stops before starting the next item.
     */
    public static void setQueuePaused(Context context, boolean paused) {
        Context app = context.getApplicationContext();
        boolean before = isQueuePaused(app);
        if (before == paused) {
            if (!paused) ensureDrainScheduled(app);
            return;
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_QUEUE_PAUSED, paused)
                .commit();
        try {
            JSONObject details = new JSONObject();
            details.put("paused", paused);
            details.put("queuedItemsRetained", true);
            details.put("runningItemAllowedToFinish", true);
            AppLogger.event(app,
                    paused ? "TRANSCRIPTION_QUEUE_PAUSED_BY_USER"
                            : "TRANSCRIPTION_QUEUE_RESUMED_BY_USER",
                    details);
        } catch (Exception ignored) {
        }
        if (!paused) {
            ensureDrainScheduled(app);
        }
    }

    public static void enqueue(Context context, String segmentId, File file) {'''
s, n = pattern.subn(manual_block, s, count=1)
if n != 1:
    raise SystemExit("manual scheduler block replacement failed")
s = s.replace("isBacklogPaused(", "isQueuePaused(")
s = s.replace("MANUAL_RETRANSCRIPTION_QUEUED_LIVE_PAUSED", "MANUAL_RETRANSCRIPTION_QUEUED_PAUSED")
s = s.replace("QUEUED_ITEM_RETAINED_UNTIL_POSTPROCESS_MODE", "QUEUED_ITEM_RETAINED_UNTIL_QUEUE_RESUME")
s = s.replace("backlogPausedForLive", "queuePaused")
s = re.sub(
    r"        if \(isQueuePaused\(app\)\) \{\n            WorkManager\.getInstance\(app\)\.cancelUniqueWork\(DRAIN_WORK_NAME\);\n            return;\n        \}",
    "        if (isQueuePaused(app)) {\n            return;\n        }",
    s,
    count=1,
)
scheduler.write_text(s)

worker = Path("app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionWorker.java")
s = worker.read_text()
s = replace_once(
    s,
    "        Context context = getApplicationContext();\n        if (!TranscriptionExecutionGate.tryAcquire()) {",
    "        Context context = getApplicationContext();\n"
    "        if (TranscriptionScheduler.isQueuePaused(context)) {\n"
    "            AppLogger.event(context, \"TRANSCRIPTION_DRAIN_WORKER_SKIPPED_QUEUE_PAUSED\");\n"
    "            return Result.success();\n"
    "        }\n"
    "        if (!TranscriptionExecutionGate.tryAcquire()) {",
    "worker pre-acquire pause",
)
s = replace_once(
    s,
    "        try {\n            SegmentRecord next = nextQueuedRecord(context);",
    "        try {\n"
    "            if (TranscriptionScheduler.isQueuePaused(context)) {\n"
    "                AppLogger.event(context, \"TRANSCRIPTION_DRAIN_WORKER_HELD_QUEUE_PAUSED\");\n"
    "                return Result.success();\n"
    "            }\n"
    "            SegmentRecord next = nextQueuedRecord(context);",
    "worker post-acquire pause",
)
worker.write_text(s)

service = Path("app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionQueueService.java")
s = service.read_text()
if "isBacklogPaused(" not in s:
    raise SystemExit("service live-pause guard missing")
s = s.replace("isBacklogPaused(", "isQueuePaused(")
s = s.replace("TRANSCRIPTION_DIRECT_QUEUE_KICK_IGNORED_LIVE_PAUSED", "TRANSCRIPTION_DIRECT_QUEUE_KICK_IGNORED_QUEUE_PAUSED")
s = s.replace("TRANSCRIPTION_DIRECT_QUEUE_START_SKIPPED_LIVE_PAUSED", "TRANSCRIPTION_DIRECT_QUEUE_START_SKIPPED_QUEUE_PAUSED")
s = s.replace("TRANSCRIPTION_DIRECT_QUEUE_DRAIN_SKIPPED_LIVE_PAUSED", "TRANSCRIPTION_DIRECT_QUEUE_DRAIN_SKIPPED_QUEUE_PAUSED")
s = s.replace("TRANSCRIPTION_DIRECT_QUEUE_PAUSED_AFTER_CURRENT_FOR_LIVE", "TRANSCRIPTION_DIRECT_QUEUE_PAUSED_AFTER_CURRENT_ITEM")
service.write_text(s)

ui = Path("app/src/main/java/com/sktpj/recorder24h/UnifiedQueueScreen.kt")
s = ui.read_text()
s = replace_once(
    s,
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\n",
    "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.setValue\n",
    "ui coroutine scope import",
)
s = replace_once(
    s,
    "import com.sktpj.recorder24h.transcription.TranscriptionPriorityController\n",
    "import com.sktpj.recorder24h.transcription.TranscriptionPriorityController\n"
    "import com.sktpj.recorder24h.transcription.TranscriptionScheduler\n",
    "ui scheduler import",
)
s = replace_once(
    s,
    "import kotlinx.coroutines.withContext\n",
    "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.launch\n",
    "ui launch import",
)
s = replace_once(
    s,
    "    val context = LocalContext.current\n    var selectedPriorityId by remember { mutableStateOf<String?>(null) }\n",
    "    val context = LocalContext.current\n"
    "    val scope = rememberCoroutineScope()\n"
    "    var queuePaused by remember { mutableStateOf(TranscriptionScheduler.isQueuePaused(context)) }\n"
    "    var selectedPriorityId by remember { mutableStateOf<String?>(null) }\n",
    "ui pause state",
)
s = replace_once(
    s,
    "    LaunchedEffect(queued) {\n        if (selectedPriorityId != null && queued.none { it.segmentId == selectedPriorityId }) {\n            selectedPriorityId = null\n        }\n    }\n\n    if (queued.isEmpty()) {",
    "    LaunchedEffect(queued) {\n"
    "        if (selectedPriorityId != null && queued.none { it.segmentId == selectedPriorityId }) {\n"
    "            selectedPriorityId = null\n"
    "        }\n"
    "    }\n"
    "    LaunchedEffect(Unit) {\n"
    "        while (true) {\n"
    "            queuePaused = withContext(Dispatchers.IO) { TranscriptionScheduler.isQueuePaused(context) }\n"
    "            delay(500L)\n"
    "        }\n"
    "    }\n\n"
    "    TranscriptionQueuePauseControls(\n"
    "        paused = queuePaused,\n"
    "        onToggle = {\n"
    "            val next = !queuePaused\n"
    "            queuePaused = next\n"
    "            scope.launch(Dispatchers.IO) {\n"
    "                TranscriptionScheduler.setQueuePaused(context, next)\n"
    "            }\n"
    "        }\n"
    "    )\n\n"
    "    if (queued.isEmpty()) {",
    "ui pause controls placement",
)
marker = "\nprivate fun swapQueueItem(\n"
pause_fn = '''
@Composable
private fun TranscriptionQueuePauseControls(
    paused: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (paused) "通常文字起こしキュー: 一時停止中" else "通常文字起こしキュー: 実行中",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (paused) {
                    "待機中の項目は保持します。実行中の1件がある場合は完了後に停止します。"
                } else {
                    "完全ストリーミング中に通常キューを止めたい場合は一時停止してください。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onToggle) {
            Text(if (paused) "再開" else "一時停止")
        }
    }
}
'''
if marker not in s:
    raise SystemExit("ui pause function marker missing")
s = s.replace(marker, "\n" + pause_fn + marker, 1)
ui.write_text(s)

settings = Path("app/src/main/java/com/sktpj/recorder24h/TranscriptionBackendSettingsCard.kt")
s = settings.read_text()
target = '''            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING &&
                pipeline.vadBackend != TranscriptionPipelineSettings.VAD_STREAMING_SILERO
            ) {'''
insert = '''            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING) {
                Text(
                    "通常の文字起こしキューは自動停止しません。ライブ中に止める場合は、キュー画面の「一時停止」を使ってください。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

''' + target
s = replace_once(s, target, insert, "settings live queue note")
settings.write_text(s)

diag = Path("app/src/main/java/com/sktpj/recorder24h/util/DriveLogSync.java")
s = diag.read_text()
s = replace_once(
    s,
    '        transcription.put("pendingAudioCount", TranscriptionScheduler.pendingAudioCount(context));\n',
    '        transcription.put("pendingAudioCount", TranscriptionScheduler.pendingAudioCount(context));\n'
    '        transcription.put("queuePaused", TranscriptionScheduler.isQueuePaused(context));\n',
    "diagnostics pause state",
)
diag.write_text(s)

gradle = Path("app/build.gradle")
s = gradle.read_text()
s = replace_once(
    s,
    "        versionCode 1016\n        versionName '0.7.16'\n",
    "        versionCode 1017\n        versionName '0.7.17'\n",
    "version bump",
)
s = replace_once(
    s,
    "        // Full streaming runs ASR in a dedicated process so heavy inference cannot block the AudioRecord loop.\n",
    "        // Full streaming runs ASR in a dedicated process so heavy inference cannot block the AudioRecord loop.\n"
    "        // The persisted transcription backlog has an explicit user-controlled pause/resume state.\n",
    "version comment",
)
gradle.write_text(s)
