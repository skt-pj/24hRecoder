from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path, old, new, label):
    text = read(path)
    if new in text:
        print(f"already applied: {label}")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    write(path, text.replace(old, new, 1))
    print(f"applied: {label}")


def insert_once(path, anchor, insertion, label):
    text = read(path)
    if insertion.strip() in text:
        print(f"already applied: {label}")
        return
    count = text.count(anchor)
    if count != 1:
        raise RuntimeError(f"{label}: expected one anchor, found {count}")
    write(path, text.replace(anchor, anchor + insertion, 1))
    print(f"applied: {label}")


LIVE_SETTINGS = r'''package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Cross-process settings that are intentionally independent from the normal five-minute model. */
public final class LiveTranscriptionSettings {
    private static final Object LOCK = new Object();
    private static final String FILE_NAME = "live_transcription_settings.json";

    private LiveTranscriptionSettings() {
    }

    public static Snapshot snapshot(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            File file = file(app);
            if (!file.isFile()) {
                // Preserve an upgrade user's current model on first migration. After this point the
                // live model and normal/final model are independent settings.
                Snapshot migrated = new Snapshot(
                        WhisperModelManager.selectedModelId(app), true);
                writeLocked(app, migrated);
                return migrated;
            }
            try {
                JSONObject row = new JSONObject(new String(
                        Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                return new Snapshot(
                        normalizeModel(row.optString("liveModelId", WhisperModelManager.MODEL_DEFAULT)),
                        row.optBoolean("fiveMinuteFinalEnabled", true));
            } catch (Exception ignored) {
                Snapshot recovered = new Snapshot(
                        WhisperModelManager.selectedModelId(app), true);
                writeLocked(app, recovered);
                return recovered;
            }
        }
    }

    public static String selectedLiveModelId(Context context) {
        return snapshot(context).liveModelId;
    }

    public static boolean isFiveMinuteFinalEnabled(Context context) {
        return snapshot(context).fiveMinuteFinalEnabled;
    }

    public static void setLiveModelId(Context context, String modelId) {
        String normalized = normalizeModel(modelId);
        if (WhisperModelManager.modelSpec(modelId) == null) {
            throw new IllegalArgumentException("Unknown live Whisper model: " + modelId);
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Snapshot current = snapshot(app);
            writeLocked(app, new Snapshot(normalized, current.fiveMinuteFinalEnabled));
        }
    }

    public static void setFiveMinuteFinalEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            Snapshot current = snapshot(app);
            writeLocked(app, new Snapshot(current.liveModelId, enabled));
        }
    }

    public static boolean isLiveModelReady(Context context) {
        String modelId = selectedLiveModelId(context);
        return WhisperModelManager.isComparisonReady(context, modelId);
    }

    private static String normalizeModel(String modelId) {
        return WhisperModelManager.modelSpec(modelId) == null
                ? WhisperModelManager.MODEL_DEFAULT : modelId;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static void writeLocked(Context app, Snapshot snapshot) {
        JSONObject row = new JSONObject();
        try {
            row.put("schemaVersion", 1);
            row.put("liveModelId", snapshot.liveModelId);
            row.put("fiveMinuteFinalEnabled", snapshot.fiveMinuteFinalEnabled);
            row.put("updatedAtMs", System.currentTimeMillis());
        } catch (Exception error) {
            throw new IllegalStateException("LIVE_TRANSCRIPTION_SETTINGS_JSON_FAILED", error);
        }
        File target = file(app);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName()
                + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(row.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            throw new IllegalStateException("LIVE_TRANSCRIPTION_SETTINGS_WRITE_FAILED", error);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    public static final class Snapshot {
        public final String liveModelId;
        public final boolean fiveMinuteFinalEnabled;

        Snapshot(String liveModelId, boolean fiveMinuteFinalEnabled) {
            this.liveModelId = normalizeModel(liveModelId);
            this.fiveMinuteFinalEnabled = fiveMinuteFinalEnabled;
        }
    }
}
'''


LIVE_POLICY = r'''package com.sktpj.recorder24h.transcription;

import android.content.Context;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Frozen per-five-minute policy for segments that were captured by the live pipeline. */
public final class LiveSegmentPolicyStore {
    private static final Object LOCK = new Object();
    private static final String DIR = "metadata/full-streaming-policy";
    private static final long RETENTION_MS = 14L * 24L * 60L * 60L * 1000L;

    private LiveSegmentPolicyStore() {
    }

    public static void mark(Context context, String segmentId, String liveModelId,
                            boolean fiveMinuteFinalEnabled,
                            long startedAtMs, long endedAtMs) {
        if (segmentId == null || segmentId.isEmpty()) return;
        synchronized (LOCK) {
            try {
                JSONObject row = new JSONObject();
                row.put("schemaVersion", 1);
                row.put("segmentId", segmentId);
                row.put("liveModelId", liveModelId == null ? JSONObject.NULL : liveModelId);
                row.put("fiveMinuteFinalEnabled", fiveMinuteFinalEnabled);
                row.put("startedAtMs", startedAtMs);
                row.put("endedAtMs", endedAtMs);
                row.put("updatedAtMs", System.currentTimeMillis());
                writeAtomic(file(context, segmentId), row.toString());
                cleanupOld(context);
            } catch (Exception ignored) {
            }
        }
    }

    public static Policy read(Context context, String segmentId) {
        if (segmentId == null || segmentId.isEmpty()) return null;
        File file = file(context, segmentId);
        if (!file.isFile()) return null;
        try {
            JSONObject row = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            String liveModelId = row.isNull("liveModelId")
                    ? null : row.optString("liveModelId", null);
            return new Policy(
                    liveModelId,
                    row.optBoolean("fiveMinuteFinalEnabled", false),
                    row.optLong("startedAtMs", 0L),
                    row.optLong("endedAtMs", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isFiveMinuteFinalEnabled(Context context, String segmentId) {
        Policy policy = read(context, segmentId);
        return policy != null && policy.fiveMinuteFinalEnabled;
    }

    private static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File file(Context context, String segmentId) {
        return new File(dir(context), safe(segmentId) + ".json");
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent, target.getName()
                + ".tmp." + Process.myPid() + "." + Thread.currentThread().getId());
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static void cleanupOld(Context context) {
        File[] files = dir(context).listFiles((parent, name) -> name.endsWith(".json"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        for (File file : files) {
            if (file.lastModified() > 0L && file.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static final class Policy {
        public final String liveModelId;
        public final boolean fiveMinuteFinalEnabled;
        public final long startedAtMs;
        public final long endedAtMs;

        Policy(String liveModelId, boolean fiveMinuteFinalEnabled,
               long startedAtMs, long endedAtMs) {
            this.liveModelId = liveModelId;
            this.fiveMinuteFinalEnabled = fiveMinuteFinalEnabled;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }
    }
}
'''


def main():
    # New independent live/final settings and frozen segment policy.
    write("app/src/main/java/com/sktpj/recorder24h/transcription/LiveTranscriptionSettings.java", LIVE_SETTINGS)
    write("app/src/main/java/com/sktpj/recorder24h/transcription/LiveSegmentPolicyStore.java", LIVE_POLICY)

    # Version bump.
    replace_once("app/build.gradle", "versionCode 1026", "versionCode 1027", "versionCode 1027")
    replace_once("app/build.gradle", "versionName '0.7.26'", "versionName '0.7.27'", "versionName 0.7.27")
    insert_once(
        "app/build.gradle",
        "        // Realtime manual SELF corrections enroll the same canonical speaker-profile keys used by history edits.\n",
        "        // Live and five-minute/final Whisper models are independent; five-minute finalization is explicitly switchable.\n",
        "0.7.27 build note",
    )

    # The recorder process freezes live model + finalization policy at each five-minute boundary.
    path = "app/src/main/java/com/sktpj/recorder24h/transcription/FullStreamingTranscriptionCoordinator.java"
    replace_once(path,
        "    private static String activeModelId;\n    private static Messenger remote;",
        "    private static String activeModelId;\n    private static boolean activeFiveMinuteFinalEnabled;\n    private static Messenger remote;",
        "coordinator active finalization flag")
    replace_once(path,
        "            activeModelId = WhisperModelManager.selectedModelId(appContext);\n            currentFailed = false;",
        "            activeModelId = LiveTranscriptionSettings.selectedLiveModelId(appContext);\n            activeFiveMinuteFinalEnabled = LiveTranscriptionSettings.isFiveMinuteFinalEnabled(appContext);\n            currentFailed = false;",
        "coordinator live model on reset")
    replace_once(path,
        "                activeModelId = WhisperModelManager.selectedModelId(appContext);\n            }\n            return activePipeline.vadBackend;",
        "                activeModelId = LiveTranscriptionSettings.selectedLiveModelId(appContext);\n                activeFiveMinuteFinalEnabled = LiveTranscriptionSettings.isFiveMinuteFinalEnabled(appContext);\n            }\n            return activePipeline.vadBackend;",
        "coordinator lazy live settings")
    replace_once(path,
        "            String oldModelId = activeModelId == null\n                    ? WhisperModelManager.selectedModelId(appContext) : activeModelId;\n            boolean oldLive = TranscriptionPipelineSettings.isLiveStreaming(oldPipeline);\n            boolean oldFailed = currentFailed;\n\n            TranscriptionPipelineSettings.Snapshot nextPipeline = TranscriptionPipelineSettings.snapshot(appContext);\n            String nextModelId = WhisperModelManager.selectedModelId(appContext);\n            boolean nextLive = TranscriptionPipelineSettings.isLiveStreaming(nextPipeline);",
        "            String oldModelId = activeModelId == null\n                    ? LiveTranscriptionSettings.selectedLiveModelId(appContext) : activeModelId;\n            boolean oldFiveMinuteFinalEnabled = activeFiveMinuteFinalEnabled;\n            boolean oldLive = TranscriptionPipelineSettings.isLiveStreaming(oldPipeline);\n            boolean oldFailed = currentFailed;\n\n            TranscriptionPipelineSettings.Snapshot nextPipeline = TranscriptionPipelineSettings.snapshot(appContext);\n            String nextModelId = LiveTranscriptionSettings.selectedLiveModelId(appContext);\n            boolean nextFiveMinuteFinalEnabled = LiveTranscriptionSettings.isFiveMinuteFinalEnabled(appContext);\n            boolean nextLive = TranscriptionPipelineSettings.isLiveStreaming(nextPipeline);",
        "coordinator boundary live settings")
    replace_once(path,
        "                FullStreamingStateStore.markOwned(appContext, segmentId, oldPipeline, oldModelId,\n                        startedAtMs, endedAtMs);\n                if (oldFailed) {",
        "                FullStreamingStateStore.markOwned(appContext, segmentId, oldPipeline, oldModelId,\n                        startedAtMs, endedAtMs);\n                LiveSegmentPolicyStore.mark(appContext, segmentId, oldModelId,\n                        oldFiveMinuteFinalEnabled, startedAtMs, endedAtMs);\n                if (oldFailed) {",
        "coordinator persist segment policy")
    replace_once(path,
        "                    markFailedWhenPublished(appContext, segmentId, reason);\n                    // A failed segment does not poison the next segment forever.",
        "                    if (!oldFiveMinuteFinalEnabled) {\n                        markFailedWhenPublished(appContext, segmentId, reason);\n                    }\n                    // A failed live preview does not block the explicitly enabled five-minute\n                    // final pass. A failed segment does not poison the next live segment forever.",
        "coordinator live failure final-pass behavior")
    replace_once(path,
        "                    data.putLong(\"endedAtMs\", endedAtMs);\n                    putPipeline(data, \"old\", oldPipeline, oldModelId);",
        "                    data.putLong(\"endedAtMs\", endedAtMs);\n                    data.putBoolean(\"fiveMinuteFinalEnabled\", oldFiveMinuteFinalEnabled);\n                    putPipeline(data, \"old\", oldPipeline, oldModelId);",
        "coordinator boundary finalization flag")
    replace_once(path,
        "            activePipeline = nextPipeline;\n            activeModelId = nextModelId;\n            currentFailed = false;",
        "            activePipeline = nextPipeline;\n            activeModelId = nextModelId;\n            activeFiveMinuteFinalEnabled = nextFiveMinuteFinalEnabled;\n            currentFailed = false;",
        "coordinator advance finalization setting")
    replace_once(path,
        "                        .put(\"oldFailed\", oldFailed)\n                        .put(\"nextRunnable\", nextReason == null)",
        "                        .put(\"oldFailed\", oldFailed)\n                        .put(\"oldLiveModelId\", oldModelId)\n                        .put(\"oldFiveMinuteFinalEnabled\", oldFiveMinuteFinalEnabled)\n                        .put(\"nextLiveModelId\", nextModelId)\n                        .put(\"nextFiveMinuteFinalEnabled\", nextFiveMinuteFinalEnabled)\n                        .put(\"nextRunnable\", nextReason == null)",
        "coordinator boundary diagnostics")

    # When five-minute finalization is ON, the live process hands canonical persistence to the
    # normal queue instead of placing a second canonical-save task behind live inference backlog.
    path = "app/src/main/java/com/sktpj/recorder24h/transcription/StreamingTranscriptionService.java"
    replace_once(path,
        "            submit(\"segment-finalize\", () -> finalizeSegment(old));\n        }\n\n        pcmBuffer.trimBefore(endPtsUs);",
        "            if (data.getBoolean(\"fiveMinuteFinalEnabled\", false)) {\n                log(\"FULL_STREAMING_SEGMENT_HANDOFF_TO_POSTPROCESS\", details(\n                        \"segmentId\", old.segmentId,\n                        \"liveModelId\", old.config.modelId,\n                        \"fiveMinuteFinalEnabled\", true,\n                        \"liveFinalsRemainInRealtimeHistory\", true));\n            } else {\n                submit(\"segment-finalize\", () -> finalizeSegment(old));\n            }\n        }\n\n        pcmBuffer.trimBefore(endPtsUs);",
        "streaming final handoff")

    # A live-owned segment is only excluded from the normal queue when five-minute finalization
    # was disabled for that exact segment. This fixes READY/\"queue outside\" ownership deadlock.
    path = "app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionRepository.java"
    replace_once(path,
        "        // A full-streaming-owned segment must never be silently re-routed into the normal\n        // post-segment queue, including after a live-ASR failure. Explicit force-retranscription\n        // bypasses this check at the scheduler/runner layer and remains available to the user.\n        if (FullStreamingStateStore.isOwned(context, segmentId)) {\n            return true;\n        }",
        "        // A live-owned segment bypasses the normal queue only when the user explicitly\n        // disabled the five-minute final pass for that frozen segment. If finalization is ON, the\n        // normal/final model is intentionally independent from the live model and must run.\n        if (FullStreamingStateStore.isOwned(context, segmentId)\n                && !LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)) {\n            return true;\n        }",
        "allow enabled live segments into final queue")
    replace_once(path,
        "        applyPendingLiveEdits(context, segmentId);\n\n        // AI inference is never run here.",
        "        applyPendingLiveEdits(context, segmentId);\n        if (FullStreamingStateStore.isOwned(context, segmentId)\n                && LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)) {\n            FullStreamingStateStore.markFinal(context, segmentId, model);\n        }\n\n        // AI inference is never run here.",
        "mark postprocess final ownership")

    # Record model carries live ownership/policy so UI labels no longer expose raw queue semantics.
    path = "app/src/main/java/com/sktpj/recorder24h/ui/SegmentHistoryRepository.kt"
    replace_once(path,
        "import com.sktpj.recorder24h.transcription.SpeakerIdentifier\n",
        "import com.sktpj.recorder24h.transcription.FullStreamingStateStore\nimport com.sktpj.recorder24h.transcription.LiveSegmentPolicyStore\nimport com.sktpj.recorder24h.transcription.SpeakerIdentifier\n",
        "history live policy imports")
    replace_once(path,
        "    val transcriptModel: String?,\n    val transcribedAtMs: Long\n) {",
        "    val transcriptModel: String?,\n    val transcribedAtMs: Long,\n    val liveOwned: Boolean,\n    val fiveMinuteFinalEnabled: Boolean,\n    val liveModelId: String?\n) {",
        "history record live fields")
    replace_once(path,
        "            val sortTime = if (started > 0L) {\n                started\n            } else {",
        "            val liveOwned = FullStreamingStateStore.isOwned(context, builder.segmentId)\n            val livePolicy = if (liveOwned) LiveSegmentPolicyStore.read(context, builder.segmentId) else null\n            val sortTime = if (started > 0L) {\n                started\n            } else {",
        "history read live policy")
    replace_once(path,
        "                transcriptModel = builder.transcriptModel,\n                transcribedAtMs = builder.transcribedAtMs\n            )",
        "                transcriptModel = builder.transcriptModel,\n                transcribedAtMs = builder.transcribedAtMs,\n                liveOwned = liveOwned,\n                fiveMinuteFinalEnabled = livePolicy?.fiveMinuteFinalEnabled ?: false,\n                liveModelId = livePolicy?.liveModelId\n            )",
        "history attach live policy")

    # Settings UI: separate live model from normal/final model.
    path = "app/src/main/java/com/sktpj/recorder24h/WhisperModelSettingsCard.kt"
    replace_once(path,
        "import com.sktpj.recorder24h.transcription.TranscriptionScheduler\n",
        "import com.sktpj.recorder24h.transcription.LiveTranscriptionSettings\nimport com.sktpj.recorder24h.transcription.TranscriptionScheduler\n",
        "model settings live import")
    replace_once(path,
        "    var selectedId by remember { mutableStateOf(WhisperModelManager.selectedModelId(context)) }\n    var refreshTick by remember { mutableIntStateOf(0) }",
        "    var selectedId by remember { mutableStateOf(WhisperModelManager.selectedModelId(context)) }\n    var liveSelectedId by remember { mutableStateOf(LiveTranscriptionSettings.selectedLiveModelId(context)) }\n    var refreshTick by remember { mutableIntStateOf(0) }",
        "model settings live state")
    replace_once(path,
        "            selectedId = WhisperModelManager.selectedModelId(context)\n            refreshTick++",
        "            selectedId = WhisperModelManager.selectedModelId(context)\n            liveSelectedId = LiveTranscriptionSettings.selectedLiveModelId(context)\n            refreshTick++",
        "model settings live refresh")
    replace_once(path,
        "    val selected = specs.firstOrNull { it.id == selectedId }\n        ?: WhisperModelManager.selectedModelSpec(context)\n    val ready = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, selectedId)",
        "    val selected = specs.firstOrNull { it.id == selectedId }\n        ?: WhisperModelManager.selectedModelSpec(context)\n    val liveSelected = specs.firstOrNull { it.id == liveSelectedId }\n        ?: WhisperModelManager.modelSpec(liveSelectedId)\n    val liveReady = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, liveSelectedId)\n    val liveAsrBytes = WhisperModelManager.downloadedBytesForModel(context, liveSelectedId)\n    val ready = refreshKey >= 0 && WhisperModelManager.isComparisonReady(context, selectedId)",
        "model settings live computed state")
    live_card = r'''
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ライブ文字起こしモデル", style = MaterialTheme.typography.titleLarge)
                Text(
                    "リアルタイムの発話ごとの認識に使うWhisperモデルです。5分後の確定・通常文字起こしモデルとは独立して選択します。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(specs, key = { "live-${it.id}" }) { spec ->
                        FilterChip(
                            selected = liveSelectedId == spec.id,
                            onClick = {
                                val before = LiveTranscriptionSettings.selectedLiveModelId(context)
                                LiveTranscriptionSettings.setLiveModelId(context, spec.id)
                                liveSelectedId = spec.id
                                refreshTick++
                                logLiveSelection(context, before, spec.id)
                                Toast.makeText(context, "ライブを${spec.label}に変更しました", Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(spec.label) }
                        )
                    }
                }
                liveSelected?.let { spec ->
                    Text(spec.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${spec.description} • ${formatModelBytes(spec.expectedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (liveReady) "ライブモデル: 準備済み" else if (liveAsrBytes > 0L) "ライブモデル: 取得中 / 未完了" else "ライブモデル: 未取得",
                    color = if (liveReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                if (!liveReady) {
                    Button(
                        onClick = {
                            WhisperModelManager.enqueueModelDownload(context, liveSelectedId)
                            Toast.makeText(
                                context,
                                "${liveSelected?.label ?: "ライブモデル"}のダウンロードを開始します",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ライブモデルをダウンロード") }
                }
                Text(
                    "録音中のモデル変更は次の5分セグメント境界からライブ処理へ反映します。モデルを分けても自動フォールバックは行いません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
'''
    insert_once(path,
        "        TranscriptionBackendSettingsCard()\n",
        live_card,
        "insert live model card")
    replace_once(path,
        "                Text(\"通常文字起こしモデル\", style = MaterialTheme.typography.titleLarge)",
        "                Text(\"5分後の確定・通常文字起こしモデル\", style = MaterialTheme.typography.titleLarge)",
        "normal model title")
    replace_once(path,
        "                    \"自動文字起こしと「この音声を再文字起こし」で使うWhisperモデルを選択します。モデル比較の選択とは別です。\",",
        "                    \"5分後の確定文字起こし、自動文字起こしと「この音声を再文字起こし」で使うモデルです。ライブモデル・モデル比較とは別です。\",",
        "normal model description")
    insert_once(path,
        "private fun logSelection(context: android.content.Context, before: String, after: String) {\n",
        r'''    // kept below; live selection has a separate event so diagnostics can distinguish it.
''',
        "model log marker")
    # Insert a separate function immediately before the existing normal selection logger.
    text = read(path)
    marker = "private fun logSelection(context: android.content.Context, before: String, after: String) {"
    if "private fun logLiveSelection(" not in text:
        live_log = r'''private fun logLiveSelection(context: android.content.Context, before: String, after: String) {
    try {
        val details = JSONObject()
        details.put("previousLiveModelId", before)
        details.put("selectedLiveModelId", after)
        details.put("selectedLiveModelLabel", WhisperModelManager.modelSpec(after)?.label ?: JSONObject.NULL)
        details.put("selectedLiveModelReady", WhisperModelManager.isComparisonReady(context, after))
        details.put("fiveMinuteFinalEnabled", LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context))
        AppLogger.event(context, "UI_WHISPER_LIVE_MODEL_SELECTED", details)
    } catch (_: Exception) {
    }
}

'''
        if marker not in text:
            raise RuntimeError("live model logger anchor missing")
        write(path, text.replace(marker, live_log + marker, 1))

    # Backend UI: five-minute final pass is an explicit live-mode switch.
    path = "app/src/main/java/com/sktpj/recorder24h/TranscriptionBackendSettingsCard.kt"
    replace_once(path,
        "import com.sktpj.recorder24h.transcription.FullStreamingStateStore\n",
        "import com.sktpj.recorder24h.transcription.FullStreamingStateStore\nimport com.sktpj.recorder24h.transcription.LiveTranscriptionSettings\n",
        "backend settings live import")
    replace_once(path,
        "    var pipeline by remember { mutableStateOf(TranscriptionPipelineSettings.snapshot(context)) }\n    var liveState by remember { mutableStateOf(FullStreamingStateStore.readLiveState(context)) }",
        "    var pipeline by remember { mutableStateOf(TranscriptionPipelineSettings.snapshot(context)) }\n    var fiveMinuteFinalEnabled by remember { mutableStateOf(LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context)) }\n    var liveState by remember { mutableStateOf(FullStreamingStateStore.readLiveState(context)) }",
        "backend finalization state")
    replace_once(path,
        "    fun refresh() {\n        pipeline = TranscriptionPipelineSettings.snapshot(context)\n    }",
        "    fun refresh() {\n        pipeline = TranscriptionPipelineSettings.snapshot(context)\n        fiveMinuteFinalEnabled = LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context)\n    }",
        "backend refresh finalization")
    final_ui = r'''

            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING) {
                PipelineSection("5分後の確定文字起こし") {
                    BackendChip(
                        selected = fiveMinuteFinalEnabled,
                        enabled = true,
                        text = "ON"
                    ) {
                        val before = fiveMinuteFinalEnabled.toString()
                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, true)
                        refresh(); logChange("fiveMinuteFinalEnabled", before, fiveMinuteFinalEnabled.toString())
                    }
                    BackendChip(
                        selected = !fiveMinuteFinalEnabled,
                        enabled = true,
                        text = "OFF"
                    ) {
                        val before = fiveMinuteFinalEnabled.toString()
                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, false)
                        refresh(); logChange("fiveMinuteFinalEnabled", before, fiveMinuteFinalEnabled.toString())
                    }
                }
                Text(
                    if (fiveMinuteFinalEnabled)
                        "ON: ライブ表示とは別に、5分音声を通常/確定モデルで再処理して履歴の確定結果にします。"
                    else
                        "OFF: 5分後の通常モデル再処理は行わず、ライブで確定した発話を5分履歴へ保存します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
'''
    insert_once(path,
        "            if (pipeline.executionMode == TranscriptionPipelineSettings.MODE_LIVE_STREAMING) {\n                Text(\n                    \"通常の文字起こしキューは自動停止しません。ライブ中に止める場合は、キュー画面の「一時停止」を使ってください。\",\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            }\n",
        final_ui,
        "backend five-minute final toggle")
    replace_once(path,
        "                \"完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡し、発話中は暫定認識、発話終了時に確定認識します。Whisperはライブ専用常駐または通常JNI（発話ごとロード）を明示選択できます。5分音声保存は従来どおり継続し、録音中の設定変更は次の5分セグメント境界から反映されます。\",",
        "                \"完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡します。ライブモデルと5分確定モデルは独立し、5分後の通常モデル確定はON/OFFできます。5分音声保存は常に継続し、録音中の設定変更は次の5分セグメント境界から反映されます。\",",
        "backend footer description")

    # Main record UI: show live/final semantics rather than raw READY/QUEUED labels.
    path = "app/src/main/java/com/sktpj/recorder24h/MainActivity.kt"
    replace_once(path,
        "import com.sktpj.recorder24h.transcription.LocalWhisperEngine\n",
        "import com.sktpj.recorder24h.transcription.LiveTranscriptionSettings\nimport com.sktpj.recorder24h.transcription.LocalWhisperEngine\n",
        "main live settings import")
    replace_once(path,
        "                    record.hasTranscript && !record.transcriptText.isNullOrBlank() -> record.transcriptText!!\n                    record.hasTranscript -> \"文字起こし結果は空です（無音区間の可能性があります）\"\n                    record.status == \"TRANSCRIBING\" -> \"端末内で文字起こし中です\"\n                    record.audioAvailable -> \"音声は保存済みです。文字起こしを待っています\"\n                    else -> \"文字起こし結果はまだありません\"",
        "                    record.hasTranscript && !record.transcriptText.isNullOrBlank() -> record.transcriptText!!\n                    record.hasTranscript -> \"文字起こし結果は空です（無音区間の可能性があります）\"\n                    record.liveOwned && record.fiveMinuteFinalEnabled && record.status == \"TRANSCRIBING\" -> \"ライブ表示とは別に、5分音声を確定モデルで処理中です\"\n                    record.liveOwned && record.fiveMinuteFinalEnabled && record.status in setOf(\"QUEUED\", \"RETRY_WAIT\", \"READY\") -> \"ライブ発話はリアルタイム表示済みです。5分後の確定処理を待っています\"\n                    record.liveOwned && !record.fiveMinuteFinalEnabled -> \"ライブ発話を5分記録へ確定しています\"\n                    record.status == \"TRANSCRIBING\" -> \"端末内で文字起こし中です\"\n                    record.audioAvailable -> \"音声は保存済みです。文字起こしを待っています\"\n                    else -> \"文字起こし結果はまだありません\"",
        "history live status body")
    replace_once(path,
        "                    InfoRow(\"状態\", record.status)",
        "                    InfoRow(\"状態\", recordStatusLabel(record))",
        "detail user-facing status")
    old_status = r'''private fun recordStatusLabel(record: SegmentRecord): String {
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
}
'''
    new_status = r'''private fun recordStatusLabel(record: SegmentRecord): String {
    if (record.liveOwned) {
        return when {
            record.hasTranscript && record.fiveMinuteFinalEnabled -> "5分確定済み"
            record.hasTranscript -> "ライブ確定済み"
            record.fiveMinuteFinalEnabled && record.status == "TRANSCRIBING" -> "5分確定処理中"
            record.fiveMinuteFinalEnabled && record.status == "RETRY_WAIT" -> "5分確定再試行待ち"
            record.fiveMinuteFinalEnabled && record.status == "QUEUED" -> "5分確定待ち"
            record.fiveMinuteFinalEnabled && record.status == "READY" -> "5分確定登録中"
            record.status == "FAILED" && record.fiveMinuteFinalEnabled -> "5分確定失敗"
            record.status == "FAILED" -> "ライブ失敗"
            record.status == "READY" -> "ライブ確定処理中"
            else -> "ライブ処理中"
        }
    }
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
}
'''
    replace_once(path, old_status, new_status, "history live status labels")
    replace_once(path,
        "        modelReady = TranscriptionPipelineSettings.isSelectedPipelineReady(context, WhisperModelManager.selectedModelId(context)),\n        modelBytes = WhisperModelManager.downloadedBytes(context),",
        "        modelReady = run {\n            val pipeline = TranscriptionPipelineSettings.snapshot(context)\n            val modelId = if (TranscriptionPipelineSettings.isLiveStreaming(pipeline))\n                LiveTranscriptionSettings.selectedLiveModelId(context) else WhisperModelManager.selectedModelId(context)\n            TranscriptionPipelineSettings.unavailableReason(context, pipeline, modelId) == null\n        },\n        modelBytes = run {\n            val pipeline = TranscriptionPipelineSettings.snapshot(context)\n            val modelId = if (TranscriptionPipelineSettings.isLiveStreaming(pipeline))\n                LiveTranscriptionSettings.selectedLiveModelId(context) else WhisperModelManager.selectedModelId(context)\n            WhisperModelManager.downloadedBytesForModel(context, modelId) +\n                WhisperModelManager.vadModelFile(context).let { if (it.isFile) it.length() else 0L }\n        },",
        "dashboard selected live model readiness")
    replace_once(path,
        "    val backendLabel = TranscriptionPipelineSettings.asrLabel(backendId)\n    val vadLabel = TranscriptionPipelineSettings.vadLabel(pipeline.vadBackend)",
        "    val backendLabel = TranscriptionPipelineSettings.asrLabel(backendId)\n    val vadLabel = TranscriptionPipelineSettings.vadLabel(pipeline.vadBackend)\n    val liveModelId = LiveTranscriptionSettings.selectedLiveModelId(context)\n    val liveModelLabel = WhisperModelManager.modelSpec(liveModelId)?.label ?: liveModelId",
        "realtime live model label state")
    replace_once(path,
        "                Text(\"$backendLabel / $vadLabel\", style = MaterialTheme.typography.bodyMedium)",
        "                Text(\"$backendLabel / $vadLabel / $liveModelLabel\", style = MaterialTheme.typography.bodyMedium)",
        "realtime show live model")

    print("0.7.27 migration complete")


if __name__ == "__main__":
    main()
