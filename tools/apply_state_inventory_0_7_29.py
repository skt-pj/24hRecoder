from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing replacement target: {label}")
    return text.replace(old, new, 1)

# Version bump.
p = "app/build.gradle"
s = read(p)
s = replace_once(s, "versionCode 1028", "versionCode 1029", "versionCode")
s = replace_once(s, "versionName '0.7.28'", "versionName '0.7.29'", "versionName")
write(p, s)

# Expose the durable live ownership result so reconciliation can preserve failures.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/FullStreamingStateStore.java"
s = read(p)
old = '''    public static boolean isOwned(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return false;
        JSONObject row = readOwnership(context, segmentId);
        return row.optBoolean("owned", false);
    }
'''
new = '''    public static boolean isOwned(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return false;
        JSONObject row = readOwnership(context, segmentId);
        return row.optBoolean("owned", false);
    }

    /** Read-only durable ownership result for diagnostics and state reconciliation. */
    public static OwnershipState readOwnershipState(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return OwnershipState.empty();
        JSONObject row = readOwnership(context, segmentId);
        return new OwnershipState(
                row.optBoolean("owned", false),
                row.optString("state", ""),
                row.isNull("engineId") ? null : row.optString("engineId", null),
                row.isNull("error") ? null : row.optString("error", null),
                row.optLong("updatedAtMs", 0L));
    }

    public static final class OwnershipState {
        public final boolean owned;
        public final String state;
        public final String engineId;
        public final String error;
        public final long updatedAtMs;

        OwnershipState(boolean owned, String state, String engineId, String error, long updatedAtMs) {
            this.owned = owned;
            this.state = state == null ? "" : state;
            this.engineId = engineId;
            this.error = error;
            this.updatedAtMs = updatedAtMs;
        }

        static OwnershipState empty() {
            return new OwnershipState(false, "", null, null, 0L);
        }
    }
'''
s = replace_once(s, old, new, "ownership snapshot")
write(p, s)

# Give every history row explicit data/queue semantics and surface audio-only orphan rows as attention.
p = "app/src/main/java/com/sktpj/recorder24h/ui/SegmentHistoryRepository.kt"
s = read(p)
old = '''    val needsAttention: Boolean
        get() = status == "FAILED" || status == "CORRUPT" || status == "RETRY_WAIT"
'''
new = '''    val queueState: String
        get() = when (status) {
            "QUEUED", "RETRY_WAIT", "TRANSCRIBING" -> status
            else -> "NONE"
        }

    val dataState: String
        get() = when {
            status == "CORRUPT" -> "CORRUPT"
            hasTranscript && audioAvailable -> "AUDIO_AND_TRANSCRIPT"
            hasTranscript -> "TRANSCRIPT_ONLY"
            audioAvailable && queueState != "NONE" -> "AUDIO_PROCESSING"
            audioAvailable && liveOwned && !fiveMinuteFinalEnabled -> "LIVE_AUDIO_NO_TRANSCRIPT"
            audioAvailable -> "AUDIO_ONLY"
            else -> "METADATA_ONLY"
        }

    val needsAttention: Boolean
        get() = status == "FAILED" || status == "CORRUPT" || status == "RETRY_WAIT" ||
            (audioAvailable && !hasTranscript && queueState == "NONE")
'''
s = replace_once(s, old, new, "segment state semantics")
write(p, s)

# Preserve live failures when five-minute finalization is disabled and reconcile stale live orphans.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionScheduler.java"
s = read(p)
s = replace_once(
    s,
    '    private static final ExecutorService RECOVERY_EXECUTOR = Executors.newSingleThreadExecutor();\n',
    '    private static final ExecutorService RECOVERY_EXECUTOR = Executors.newSingleThreadExecutor();\n'
    '    private static final long LIVE_ORPHAN_STALE_MS = 30L * 60L * 1000L;\n',
    "orphan stale constant",
)
old = '''    static void settleAutomaticLiveFinalDisabled(Context context, SegmentRecord record, String reason) {
        if (record == null) return;
        String audioPath = record.getAudioPath();
        File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
        boolean hasTranscript = TranscriptionRepository.exists(context, record.getSegmentId());
        SegmentRepository.appendWithoutNotify(
                context,
                record.getSegmentId(),
                audioFile,
                audioFile != null && audioFile.isFile() ? audioFile.lastModified() : 0L,
                System.currentTimeMillis(),
                hasTranscript ? "TRANSCRIBED" : "READY",
                reason);
    }
'''
new = '''    static void settleAutomaticLiveFinalDisabled(Context context, SegmentRecord record, String reason) {
        if (record == null) return;
        String audioPath = record.getAudioPath();
        File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
        boolean hasTranscript = TranscriptionRepository.exists(context, record.getSegmentId());
        FullStreamingStateStore.OwnershipState ownership =
                FullStreamingStateStore.readOwnershipState(context, record.getSegmentId());
        String status;
        String storedReason = reason;
        if (hasTranscript) {
            status = "TRANSCRIBED";
        } else if (ownership.owned && "FAILED".equals(ownership.state)) {
            status = "FAILED";
            storedReason = ownership.error == null || ownership.error.isEmpty()
                    ? "FULL_STREAMING_ASR_FAILED"
                    : "FULL_STREAMING_ASR_FAILED:" + ownership.error;
        } else {
            status = "READY";
        }
        SegmentRepository.appendWithoutNotify(
                context,
                record.getSegmentId(),
                audioFile,
                audioFile != null && audioFile.isFile() ? audioFile.lastModified() : 0L,
                System.currentTimeMillis(),
                status,
                storedReason);
    }
'''
s = replace_once(s, old, new, "preserve live failure on final off")
old = '''                JSONObject details = new JSONObject();
                details.put("legacyWorkCancelled", migrated);
                details.put("recoveredTranscribingCount", recovered);
                details.put("disabledLiveFinalCount", disabledLiveFinals);
                details.put("queuePaused", isQueuePaused(app));
                AppLogger.event(app, "TRANSCRIPTION_SINGLE_RUNNER_RECOVERY_COMPLETED", details);
'''
new = '''                int liveOrphansReconciled = reconcileStaleLiveOrphans(app);

                JSONObject details = new JSONObject();
                details.put("legacyWorkCancelled", migrated);
                details.put("recoveredTranscribingCount", recovered);
                details.put("disabledLiveFinalCount", disabledLiveFinals);
                details.put("liveOrphansReconciled", liveOrphansReconciled);
                details.put("queuePaused", isQueuePaused(app));
                AppLogger.event(app, "TRANSCRIPTION_SINGLE_RUNNER_RECOVERY_COMPLETED", details);
'''
s = replace_once(s, old, new, "recovery reconciliation call")
marker = '''    static boolean isAutomaticLiveFinalDisabled(Context context, SegmentRecord record) {
'''
helper = '''    private static int reconcileStaleLiveOrphans(Context context) {
        long now = System.currentTimeMillis();
        int reconciled = 0;
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (!record.getLiveOwned() || !record.getAudioAvailable() || record.getHasTranscript()) continue;
            String status = record.getStatus();
            if ("QUEUED".equals(status) || "RETRY_WAIT".equals(status) || "TRANSCRIBING".equals(status)) continue;
            FullStreamingStateStore.OwnershipState ownership =
                    FullStreamingStateStore.readOwnershipState(context, record.getSegmentId());
            boolean durableFailure = ownership.owned && "FAILED".equals(ownership.state);
            long ageBase = Math.max(record.getEndedAtMs(), record.getStateChangedAtMs());
            boolean staleOwned = ownership.owned && "OWNED".equals(ownership.state)
                    && ageBase > 0L && now - ageBase >= LIVE_ORPHAN_STALE_MS;
            if (!durableFailure && !staleOwned) continue;

            String audioPath = record.getAudioPath();
            File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);
            String reason = durableFailure
                    ? (ownership.error == null || ownership.error.isEmpty()
                        ? "FULL_STREAMING_ASR_FAILED"
                        : "FULL_STREAMING_ASR_FAILED:" + ownership.error)
                    : "LIVE_CANONICAL_TRANSCRIPT_MISSING";
            if (!"FAILED".equals(status) || !reason.equals(record.getReason())) {
                SegmentRepository.appendWithoutNotify(
                        context,
                        record.getSegmentId(),
                        audioFile,
                        audioFile != null && audioFile.isFile() ? audioFile.lastModified() : 0L,
                        now,
                        "FAILED",
                        reason);
                reconciled++;
            }
        }
        if (reconciled > 0) {
            try {
                JSONObject details = new JSONObject();
                details.put("count", reconciled);
                details.put("staleThresholdMs", LIVE_ORPHAN_STALE_MS);
                AppLogger.event(context, "TRANSCRIPTION_LIVE_ORPHANS_RECONCILED", details);
            } catch (Exception ignored) {
            }
        }
        return reconciled;
    }

'''
if marker not in s:
    raise SystemExit("missing replacement target: reconciliation insertion")
s = s.replace(marker, helper + marker, 1)
write(p, s)

# Home: separate retained data, actual queue, automatic candidates and attention counts.
p = "app/src/main/java/com/sktpj/recorder24h/MainActivity.kt"
s = read(p)
old = '''    val audioBytes: Long,
    val appBytes: Long,
    val deviceFreeBytes: Long,
    val modelReady: Boolean,
    val modelBytes: Long,
    val pendingAudio: Int,
    val transcriptCount: Int
'''
new = '''    val retainedAudioBytes: Long,
    val retainedAudioCount: Int,
    val audioWithoutTranscriptCount: Int,
    val automaticProcessingCount: Int,
    val activeQueueCount: Int,
    val needsAttentionCount: Int,
    val corruptCount: Int,
    val appBytes: Long,
    val deviceFreeBytes: Long,
    val modelReady: Boolean,
    val modelBytes: Long,
    val transcriptCount: Int
'''
s = replace_once(s, old, new, "dashboard fields")
old = '''            Meter("未処理音声", "${formatMb(dashboard.audioBytes)} / 600 MB", dashboard.audioBytes.toFloat() / StoragePolicy.AUDIO_LIMIT_BYTES)
            Meter("作業データ", "${formatMb(dashboard.appBytes)} / 1 GB", dashboard.appBytes.toFloat() / StoragePolicy.LOGICAL_APP_LIMIT_BYTES)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("未処理", "${dashboard.pendingAudio}件")
                Metric("文字起こし", "${dashboard.transcriptCount}件")
                Metric("端末空き", formatStorage(dashboard.deviceFreeBytes))
            }
'''
new = '''            Meter("保持音声", "${formatMb(dashboard.retainedAudioBytes)} / 600 MB", dashboard.retainedAudioBytes.toFloat() / StoragePolicy.AUDIO_LIMIT_BYTES)
            Meter("作業データ", "${formatMb(dashboard.appBytes)} / 1 GB", dashboard.appBytes.toFloat() / StoragePolicy.LOGICAL_APP_LIMIT_BYTES)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("保持音声", "${dashboard.retainedAudioCount}件")
                Metric("確定文字起こし", "${dashboard.transcriptCount}件")
                Metric("端末空き", formatStorage(dashboard.deviceFreeBytes))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("音声のみ", "${dashboard.audioWithoutTranscriptCount}件")
                Metric("実キュー", "${dashboard.activeQueueCount}件")
                Metric("要確認", "${dashboard.needsAttentionCount}件")
            }
            Text(
                "自動処理対象: ${dashboard.automaticProcessingCount}件 / 破損: ${dashboard.corruptCount}件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
'''
s = replace_once(s, old, new, "home storage semantics")
old = '''    val health = RecorderHealth.evaluate(state, recordingRequested, System.currentTimeMillis())
    return DashboardSnapshot(
'''
new = '''    val health = RecorderHealth.evaluate(state, recordingRequested, System.currentTimeMillis())
    val records = SegmentHistoryRepository.load(context)
    val retainedAudioCount = records.count { it.audioAvailable && it.fileName?.endsWith(".m4a") == true }
    val audioWithoutTranscriptCount = records.count {
        it.audioAvailable && it.fileName?.endsWith(".m4a") == true && !it.hasTranscript
    }
    val activeQueueCount = records.count {
        it.status == "QUEUED" || it.status == "RETRY_WAIT" || it.status == "TRANSCRIBING"
    }
    val needsAttentionCount = records.count { it.needsAttention }
    val corruptCount = records.count { it.status == "CORRUPT" }
    return DashboardSnapshot(
'''
s = replace_once(s, old, new, "dashboard record counts")
s = replace_once(s, '        audioBytes = StoragePolicy.audioBytes(context),\n',
                 '        retainedAudioBytes = StoragePolicy.audioBytes(context),\n'
                 '        retainedAudioCount = retainedAudioCount,\n'
                 '        audioWithoutTranscriptCount = audioWithoutTranscriptCount,\n'
                 '        automaticProcessingCount = TranscriptionScheduler.pendingAudioCount(context),\n'
                 '        activeQueueCount = activeQueueCount,\n'
                 '        needsAttentionCount = needsAttentionCount,\n'
                 '        corruptCount = corruptCount,\n', "dashboard count assignments")
s = replace_once(s, '        pendingAudio = TranscriptionScheduler.pendingAudioCount(context),\n', '', "remove ambiguous pending field")
write(p, s)

# Drive diagnostics: export a complete sanitized segment inventory and explicit AI queue snapshot.
p = "app/src/main/java/com/sktpj/recorder24h/util/DriveLogSync.java"
s = read(p)
s = replace_once(s,
    'import com.sktpj.recorder24h.ai.OpenAiKeyStore;\n',
    'import com.sktpj.recorder24h.ai.AiQueueStore;\nimport com.sktpj.recorder24h.ai.OpenAiKeyStore;\n',
    "AI queue import")
s = replace_once(s,
    'import com.sktpj.recorder24h.transcription.TranscriptionRepository;\n',
    'import com.sktpj.recorder24h.transcription.LiveTranscriptionSettings;\nimport com.sktpj.recorder24h.transcription.TranscriptionRepository;\n',
    "live settings import")
s = replace_once(s,
    'import com.sktpj.recorder24h.transcription.WhisperModelManager;\n',
    'import com.sktpj.recorder24h.transcription.WhisperModelManager;\nimport com.sktpj.recorder24h.ui.SegmentHistoryRepository;\nimport com.sktpj.recorder24h.ui.SegmentRecord;\n',
    "segment inventory imports")
old = '''        byte[] diagnostics = buildDiagnostics(app).toString(2).getBytes(StandardCharsets.UTF_8);
        uploadBytes(resolver, treeUri, "24hRecoder_diagnostics.json",
                "application/json", diagnostics);
'''
new = '''        byte[] diagnostics = buildDiagnostics(app).toString(2).getBytes(StandardCharsets.UTF_8);
        uploadBytes(resolver, treeUri, "24hRecoder_diagnostics.json",
                "application/json", diagnostics);

        byte[] inventory = buildSegmentInventory(app).toString(2).getBytes(StandardCharsets.UTF_8);
        uploadBytes(resolver, treeUri, "24hRecoder_segment_inventory.json",
                "application/json", inventory);
'''
s = replace_once(s, old, new, "inventory upload")
old = '''        transcription.put("pendingAudioCount", TranscriptionScheduler.pendingAudioCount(context));
        transcription.put("queuePaused", TranscriptionScheduler.isQueuePaused(context));
        transcription.put("transcriptCount", TranscriptionRepository.count(context));
        File audioDir = StoragePolicy.getAudioDir(context);
        transcription.put("audioFileCount", countFiles(audioDir, ".m4a"));
        transcription.put("audioBytes", sumFiles(audioDir, ".m4a"));
        root.put("transcription", transcription);

        JSONObject ai = new JSONObject();
        ai.put("apiKeyConfigured", OpenAiKeyStore.hasKey(context));
        ai.put("analysisFiles", latestAnalysisFiles(context, 20));
        root.put("ai", ai);
'''
new = '''        int automaticCandidates = TranscriptionScheduler.pendingAudioCount(context);
        List<SegmentRecord> segmentRecords = SegmentHistoryRepository.load(context);
        int audioWithoutTranscript = 0;
        int activeQueue = 0;
        int needsAttention = 0;
        int corrupt = 0;
        for (SegmentRecord record : segmentRecords) {
            if (record.getAudioAvailable() && !record.getHasTranscript()
                    && record.getFileName() != null && record.getFileName().endsWith(".m4a")) {
                audioWithoutTranscript++;
            }
            if (!"NONE".equals(record.getQueueState())) activeQueue++;
            if (record.getNeedsAttention()) needsAttention++;
            if ("CORRUPT".equals(record.getStatus())) corrupt++;
        }
        transcription.put("pendingAudioCount", automaticCandidates);
        transcription.put("pendingAudioCountMeaning", "automatic-transcription-candidates");
        transcription.put("automaticProcessingCandidateCount", automaticCandidates);
        transcription.put("activeQueueCount", activeQueue);
        transcription.put("audioWithoutTranscriptCount", audioWithoutTranscript);
        transcription.put("needsAttentionCount", needsAttention);
        transcription.put("corruptCount", corrupt);
        transcription.put("historySegmentCount", segmentRecords.size());
        transcription.put("queuePaused", TranscriptionScheduler.isQueuePaused(context));
        transcription.put("transcriptCount", TranscriptionRepository.count(context));
        File audioDir = StoragePolicy.getAudioDir(context);
        transcription.put("audioFileCount", countFiles(audioDir, ".m4a"));
        transcription.put("audioBytes", sumFiles(audioDir, ".m4a"));
        transcription.put("audioBytesMeaning", "all-retained-normal-m4a-bytes");
        transcription.put("segmentInventoryFile", "24hRecoder_segment_inventory.json");
        root.put("transcription", transcription);

        JSONObject ai = new JSONObject();
        ai.put("apiKeyConfigured", OpenAiKeyStore.hasKey(context));
        ai.put("analysisFiles", latestAnalysisFiles(context, 20));
        ai.put("queue", aiQueueSnapshot(context));
        root.put("ai", ai);
'''
s = replace_once(s, old, new, "diagnostic semantics")
marker = '''    private static JSONArray workInfos(Context context, String tag) {
'''
helper = '''    private static JSONObject buildSegmentInventory(Context context) throws Exception {
        JSONObject root = new JSONObject();
        long now = System.currentTimeMillis();
        root.put("schemaVersion", 1);
        root.put("generatedAtMs", now);
        root.put("generatedAtUtc", isoUtc(now));
        root.put("fiveMinuteFinalEnabled", LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context));
        root.put("queuePaused", TranscriptionScheduler.isQueuePaused(context));

        JSONArray rows = new JSONArray();
        int retainedAudio = 0;
        int transcriptAvailable = 0;
        int audioWithoutTranscript = 0;
        int activeQueue = 0;
        int needsAttention = 0;
        int corrupt = 0;
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            JSONObject row = new JSONObject();
            row.put("segmentId", record.getSegmentId());
            row.put("fileName", record.getFileName() == null ? JSONObject.NULL : record.getFileName());
            row.put("fileSizeBytes", record.getFileSizeBytes());
            row.put("startedAtMs", record.getStartedAtMs());
            row.put("endedAtMs", record.getEndedAtMs());
            row.put("status", record.getStatus());
            row.put("reason", record.getReason() == null ? JSONObject.NULL : record.getReason());
            row.put("stateChangedAtMs", record.getStateChangedAtMs());
            row.put("audioAvailable", record.getAudioAvailable());
            row.put("transcriptAvailable", record.getHasTranscript());
            row.put("transcriptModel", record.getTranscriptModel() == null ? JSONObject.NULL : record.getTranscriptModel());
            row.put("transcribedAtMs", record.getTranscribedAtMs());
            row.put("liveOwned", record.getLiveOwned());
            row.put("fiveMinuteFinalEnabled", record.getFiveMinuteFinalEnabled());
            row.put("liveModelId", record.getLiveModelId() == null ? JSONObject.NULL : record.getLiveModelId());
            row.put("queueState", record.getQueueState());
            row.put("dataState", record.getDataState());
            row.put("needsAttention", record.getNeedsAttention());
            rows.put(row);

            if (record.getAudioAvailable() && record.getFileName() != null
                    && record.getFileName().endsWith(".m4a")) retainedAudio++;
            if (record.getHasTranscript()) transcriptAvailable++;
            if (record.getAudioAvailable() && !record.getHasTranscript()
                    && record.getFileName() != null && record.getFileName().endsWith(".m4a")) {
                audioWithoutTranscript++;
            }
            if (!"NONE".equals(record.getQueueState())) activeQueue++;
            if (record.getNeedsAttention()) needsAttention++;
            if ("CORRUPT".equals(record.getStatus())) corrupt++;
        }
        JSONObject counts = new JSONObject();
        counts.put("historySegmentCount", rows.length());
        counts.put("retainedAudioCount", retainedAudio);
        counts.put("transcriptAvailableCount", transcriptAvailable);
        counts.put("audioWithoutTranscriptCount", audioWithoutTranscript);
        counts.put("activeQueueCount", activeQueue);
        counts.put("needsAttentionCount", needsAttention);
        counts.put("corruptCount", corrupt);
        counts.put("automaticProcessingCandidateCount", TranscriptionScheduler.pendingAudioCount(context));
        root.put("counts", counts);
        root.put("segments", rows);
        root.put("aiQueue", aiQueueSnapshot(context));
        return root;
    }

    private static JSONArray aiQueueSnapshot(Context context) {
        JSONArray rows = new JSONArray();
        try {
            for (AiQueueStore.Entry entry : AiQueueStore.load(context)) {
                JSONObject row = new JSONObject();
                row.put("id", entry.id);
                row.put("kind", entry.kind);
                row.put("periodStartMs", entry.periodStartMs);
                row.put("periodEndMs", entry.periodEndMs);
                row.put("requestType", entry.requestType);
                row.put("state", entry.state);
                row.put("attempt", entry.attempt);
                row.put("priority", entry.priority);
                row.put("updatedAtMs", entry.updatedAtMs);
                rows.put(row);
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

'''
if marker not in s:
    raise SystemExit("missing replacement target: drive helper insertion")
s = s.replace(marker, helper + marker, 1)
write(p, s)

print("0.7.29 state inventory migration applied")
