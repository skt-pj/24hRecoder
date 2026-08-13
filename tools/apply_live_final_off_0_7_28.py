from pathlib import Path

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
s = replace_once(s, "versionCode 1027", "versionCode 1028", "versionCode")
s = replace_once(s, "versionName '0.7.27'", "versionName '0.7.28'", "versionName")
s = replace_once(
    s,
    "// Live and five-minute/final Whisper models are independent; five-minute finalization is explicitly switchable.",
    "// Live and five-minute/final Whisper models are independent; five-minute finalization OFF applies immediately and cancels pending automatic live finals.",
    "build note",
)
write(p, s)

# The final-pass toggle is not a frozen model/pipeline choice. Read its latest value at the boundary.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/FullStreamingTranscriptionCoordinator.java"
s = read(p)
s = replace_once(
    s,
    "            boolean oldFiveMinuteFinalEnabled = activeFiveMinuteFinalEnabled;",
    "            // Unlike the live model/backend, the user-facing final-pass toggle is immediate.\n            // The current segment obeys the latest setting when it closes.\n            boolean oldFiveMinuteFinalEnabled =\n                    LiveTranscriptionSettings.isFiveMinuteFinalEnabled(appContext);",
    "boundary immediate final toggle",
)
write(p, s)

# Allow pending segment policy to be flipped OFF when the user disables the final pass.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/LiveSegmentPolicyStore.java"
s = read(p)
s = replace_once(
    s,
    "    public static boolean isFiveMinuteFinalEnabled(Context context, String segmentId) {\n        Policy policy = read(context, segmentId);\n        return policy != null && policy.fiveMinuteFinalEnabled;\n    }\n",
    "    public static boolean isFiveMinuteFinalEnabled(Context context, String segmentId) {\n        Policy policy = read(context, segmentId);\n        return policy != null && policy.fiveMinuteFinalEnabled;\n    }\n\n    public static void setFiveMinuteFinalEnabled(Context context, String segmentId, boolean enabled) {\n        Policy policy = read(context, segmentId);\n        if (policy == null) return;\n        mark(context, segmentId, policy.liveModelId, enabled, policy.startedAtMs, policy.endedAtMs);\n    }\n",
    "policy toggle mutator",
)
write(p, s)

# Global OFF suppresses automatic five-minute work even for an older frozen policy.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionRepository.java"
s = read(p)
s = replace_once(
    s,
    "        if (FullStreamingStateStore.isOwned(context, segmentId)\n                && !LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)) {\n            return true;\n        }",
    "        if (FullStreamingStateStore.isOwned(context, segmentId)\n                && (!LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, segmentId)\n                    || !LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context))) {\n            return true;\n        }",
    "current engine live final suppression",
)
write(p, s)

# Queue cleanup/cancellation and recovery behavior.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionScheduler.java"
s = read(p)
s = replace_once(
    s,
    "    public static void enqueue(Context context, String segmentId, File file) {\n        enqueueInternal(context, segmentId, file, false);\n    }",
    "    /** Immediately remove automatic five-minute final work owned by the live pipeline. */\n    public static int disableAutomaticLiveFinals(Context context) {\n        Context app = context.getApplicationContext();\n        int removed = 0;\n        boolean runningRemoved = false;\n        for (SegmentRecord record : SegmentHistoryRepository.load(app)) {\n            if (!isAutomaticLiveFinalDisabled(app, record)) continue;\n            String status = record.getStatus();\n            if (!(\"QUEUED\".equals(status) || \"RETRY_WAIT\".equals(status)\n                    || \"TRANSCRIBING\".equals(status))) {\n                continue;\n            }\n            if (\"TRANSCRIBING\".equals(status)) runningRemoved = true;\n            LiveSegmentPolicyStore.setFiveMinuteFinalEnabled(app, record.getSegmentId(), false);\n            settleAutomaticLiveFinalDisabled(app, record, \"FIVE_MINUTE_FINAL_DISABLED_BY_USER\");\n            removed++;\n        }\n        long cancellationGeneration = runningRemoved ? TranscriptionCancellation.cancelCurrent() : -1L;\n        try {\n            JSONObject details = new JSONObject();\n            details.put(\"removedCount\", removed);\n            details.put(\"runningCancelled\", runningRemoved);\n            details.put(\"cancellationGeneration\", cancellationGeneration);\n            AppLogger.event(app, \"LIVE_FIVE_MINUTE_FINAL_DISABLED_IMMEDIATELY\", details);\n        } catch (Exception ignored) {\n        }\n        ensureDrainScheduled(app);\n        return removed;\n    }\n\n    public static void enqueue(Context context, String segmentId, File file) {\n        enqueueInternal(context, segmentId, file, false);\n    }",
    "disable automatic live finals",
)

s = replace_once(
    s,
    "                int recovered = 0;\n                List<SegmentRecord> records = SegmentHistoryRepository.load(app);\n                for (SegmentRecord record : records) {\n                    if (!\"TRANSCRIBING\".equals(record.getStatus()) || !record.getAudioAvailable()) {\n                        continue;\n                    }",
    "                int recovered = 0;\n                int disabledLiveFinals = 0;\n                List<SegmentRecord> records = SegmentHistoryRepository.load(app);\n                for (SegmentRecord record : records) {\n                    if (isAutomaticLiveFinalDisabled(app, record)\n                            && (\"QUEUED\".equals(record.getStatus())\n                                || \"RETRY_WAIT\".equals(record.getStatus())\n                                || \"TRANSCRIBING\".equals(record.getStatus()))) {\n                        LiveSegmentPolicyStore.setFiveMinuteFinalEnabled(app, record.getSegmentId(), false);\n                        settleAutomaticLiveFinalDisabled(app, record,\n                                \"FIVE_MINUTE_FINAL_DISABLED_RECOVERY\");\n                        disabledLiveFinals++;\n                        continue;\n                    }\n                    if (!\"TRANSCRIBING\".equals(record.getStatus()) || !record.getAudioAvailable()) {\n                        continue;\n                    }",
    "recovery cleanup disabled live finals",
)

s = replace_once(
    s,
    "                details.put(\"recoveredTranscribingCount\", recovered);\n                details.put(\"queuePaused\", isQueuePaused(app));",
    "                details.put(\"recoveredTranscribingCount\", recovered);\n                details.put(\"disabledLiveFinalCount\", disabledLiveFinals);\n                details.put(\"queuePaused\", isQueuePaused(app));",
    "recovery log disabled count",
)

s = replace_once(
    s,
    "    private static boolean hasQueuedWork(Context context) {\n        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {\n            if ((\"QUEUED\".equals(record.getStatus()) || \"RETRY_WAIT\".equals(record.getStatus()))\n                    && record.getAudioAvailable()) {\n                return true;\n            }\n        }\n        return false;\n    }",
    "    static boolean isAutomaticLiveFinalDisabled(Context context, SegmentRecord record) {\n        if (record == null || record.getSegmentId() == null) return false;\n        String reason = record.getReason();\n        if (reason != null && reason.startsWith(\"MANUAL_\")) return false;\n        if (!FullStreamingStateStore.isOwned(context, record.getSegmentId())) return false;\n        return !LiveSegmentPolicyStore.isFiveMinuteFinalEnabled(context, record.getSegmentId())\n                || !LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context);\n    }\n\n    static void settleAutomaticLiveFinalDisabled(Context context, SegmentRecord record, String reason) {\n        if (record == null) return;\n        String audioPath = record.getAudioPath();\n        File audioFile = audioPath == null || audioPath.isEmpty() ? null : new File(audioPath);\n        boolean hasTranscript = TranscriptionRepository.exists(context, record.getSegmentId());\n        SegmentRepository.appendWithoutNotify(\n                context,\n                record.getSegmentId(),\n                audioFile,\n                audioFile != null && audioFile.isFile() ? audioFile.lastModified() : 0L,\n                System.currentTimeMillis(),\n                hasTranscript ? \"TRANSCRIBED\" : \"READY\",\n                reason);\n    }\n\n    private static boolean hasQueuedWork(Context context) {\n        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {\n            if ((\"QUEUED\".equals(record.getStatus()) || \"RETRY_WAIT\".equals(record.getStatus()))\n                    && record.getAudioAvailable()\n                    && !isAutomaticLiveFinalDisabled(context, record)) {\n                return true;\n            }\n        }\n        return false;\n    }",
    "queue helper methods",
)
write(p, s)

# Worker must not resume or requeue an automatic live final after OFF.
p = "app/src/main/java/com/sktpj/recorder24h/transcription/TranscriptionWorker.java"
s = read(p)
s = replace_once(
    s,
    "        if (audioFile == null || !audioFile.isFile()) {\n            boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);",
    "        if (TranscriptionScheduler.isAutomaticLiveFinalDisabled(context, record)) {\n            TranscriptionScheduler.settleAutomaticLiveFinalDisabled(\n                    context, record, \"FIVE_MINUTE_FINAL_DISABLED_BEFORE_START\");\n            log(context, \"TRANSCRIPTION_LIVE_FINAL_SKIPPED_DISABLED\", segmentId, audioFile,\n                    null, forceRetranscribe, 1, null);\n            return;\n        }\n\n        if (audioFile == null || !audioFile.isFile()) {\n            boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);",
    "worker pre-start disabled check",
)

s = replace_once(
    s,
    "            if (!forceRetranscribe &&\n                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {\n                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                        audioFile.lastModified(), System.currentTimeMillis(), \"TRANSCRIBED\", null);\n                log(context, \"TRANSCRIPT_CURRENT_ENGINE_AFTER_QUEUE\", segmentId, audioFile,\n                        null, false, attempt, null);\n                return;\n            }",
    "            if (!forceRetranscribe &&\n                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {\n                boolean hasTranscript = TranscriptionRepository.exists(context, segmentId);\n                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                        audioFile.lastModified(), System.currentTimeMillis(),\n                        hasTranscript ? \"TRANSCRIBED\" : \"READY\",\n                        hasTranscript ? null : \"FIVE_MINUTE_FINAL_DISABLED\");\n                log(context, \"TRANSCRIPT_CURRENT_ENGINE_AFTER_QUEUE\", segmentId, audioFile,\n                        hasTranscript ? null : \"FIVE_MINUTE_FINAL_DISABLED\", false, attempt, null);\n                return;\n            }",
    "worker current engine state",
)

s = replace_once(
    s,
    "                if (TranscriptionCancellation.isCancellation(error)) {\n                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                            audioFile.lastModified(), System.currentTimeMillis(), \"QUEUED\",\n                            \"USER_PAUSED_RUNNING_TRANSCRIPTION\");\n                    log(context, \"TRANSCRIPTION_RUNNING_ITEM_CANCELLED_BY_USER\", segmentId, audioFile,\n                            TranscriptionCancellation.CANCELLED, forceRetranscribe, attempt, null);\n                    return;\n                }",
    "                if (TranscriptionCancellation.isCancellation(error)) {\n                    if (!forceRetranscribe\n                            && FullStreamingStateStore.isOwned(context, segmentId)\n                            && !LiveTranscriptionSettings.isFiveMinuteFinalEnabled(context)) {\n                        LiveSegmentPolicyStore.setFiveMinuteFinalEnabled(context, segmentId, false);\n                        TranscriptionScheduler.settleAutomaticLiveFinalDisabled(\n                                context, record, \"FIVE_MINUTE_FINAL_DISABLED_DURING_INFERENCE\");\n                        log(context, \"TRANSCRIPTION_RUNNING_LIVE_FINAL_CANCELLED\",\n                                segmentId, audioFile, TranscriptionCancellation.CANCELLED,\n                                false, attempt, null);\n                    } else {\n                        SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,\n                                audioFile.lastModified(), System.currentTimeMillis(), \"QUEUED\",\n                                \"USER_PAUSED_RUNNING_TRANSCRIPTION\");\n                        log(context, \"TRANSCRIPTION_RUNNING_ITEM_CANCELLED_BY_USER\", segmentId, audioFile,\n                                TranscriptionCancellation.CANCELLED, forceRetranscribe, attempt, null);\n                    }\n                    return;\n                }",
    "worker cancellation disabled final",
)
write(p, s)

# UI: OFF is immediate and performs queue cleanup.
p = "app/src/main/java/com/sktpj/recorder24h/TranscriptionBackendSettingsCard.kt"
s = read(p)
s = replace_once(
    s,
    "import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings\n",
    "import com.sktpj.recorder24h.transcription.TranscriptionPipelineSettings\nimport com.sktpj.recorder24h.transcription.TranscriptionScheduler\n",
    "scheduler import",
)
s = replace_once(
    s,
    "                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, false)\n                        refresh(); logChange(\"fiveMinuteFinalEnabled\", before, fiveMinuteFinalEnabled.toString())",
    "                        LiveTranscriptionSettings.setFiveMinuteFinalEnabled(context, false)\n                        TranscriptionScheduler.disableAutomaticLiveFinals(context)\n                        refresh(); logChange(\"fiveMinuteFinalEnabled\", before, fiveMinuteFinalEnabled.toString())",
    "off click immediate cleanup",
)
s = replace_once(
    s,
    "                    else\n                        \"OFF: 5分後の通常モデル再処理は行わず、ライブで確定した発話を5分履歴へ保存します。\",",
    "                    else\n                        \"OFF: 押した時点から5分後の通常モデル再処理を止め、待機中のライブ所有5分確定も外します。ライブで確定した発話だけを5分履歴へ保存します。\",",
    "off description",
)
s = replace_once(
    s,
    "                \"完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡します。ライブモデルと5分確定モデルは独立し、5分後の通常モデル確定はON/OFFできます。5分音声保存は常に継続し、録音中の設定変更は次の5分セグメント境界から反映されます。\",",
    "                \"完全ストリーミングでは録音中PCMを専用ASRプロセスへ渡します。ライブモデルと5分確定モデルは独立します。5分確定ON/OFFは即時反映し、ライブモデル・backend変更は次の5分セグメント境界から反映します。5分音声保存自体は常に継続します。\",",
    "settings timing description",
)
write(p, s)

print("0.7.28 migration applied")
