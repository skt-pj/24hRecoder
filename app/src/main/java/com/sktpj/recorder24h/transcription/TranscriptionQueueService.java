package com.sktpj.recorder24h.transcription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.work.WorkManager;

import com.sktpj.recorder24h.MainActivity;
import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-visible queue runner for explicit transcription requests.
 *
 * WorkManager remains the wake-up mechanism for fully automatic background work. When the user
 * explicitly requests transcription from a visible Activity, this mediaProcessing foreground
 * service drains the persisted QUEUED items directly so execution is not held behind
 * WorkManager/JobScheduler start latency or the battery-not-low constraint.
 *
 * The process-wide TranscriptionExecutionGate is shared with TranscriptionWorker so this service
 * can never become a second simultaneous queue runner.
 */
public final class TranscriptionQueueService extends Service {
    private static final String ACTION_DRAIN = "com.sktpj.recorder24h.action.DRAIN_TRANSCRIPTION_QUEUE";
    private static final String CHANNEL_ID = "24hrecoder-transcription-queue";
    private static final int NOTIFICATION_ID = 24013;
    private static final long MAX_SERVICE_RUNTIME_MS = 5L * 60L * 60L * 1000L + 20L * 60L * 1000L;
    private static final int MAX_ATTEMPTS = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile long serviceStartedAtMs;

    public static boolean kick(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, TranscriptionQueueService.class).setAction(ACTION_DRAIN);
        try {
            app.startForegroundService(intent);
            AppLogger.event(app, "TRANSCRIPTION_DIRECT_QUEUE_SERVICE_REQUESTED");
            return true;
        } catch (RuntimeException error) {
            try {
                JSONObject details = new JSONObject();
                details.put("error", error.getClass().getSimpleName());
                details.put("message", safeMessage(error));
                AppLogger.event(app, "TRANSCRIPTION_DIRECT_QUEUE_SERVICE_START_FAILED", details);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serviceStartedAtMs = System.currentTimeMillis();
        createChannel();
        promote("文字起こしキューを開始しています");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote("文字起こしキューを処理中");
        if (draining.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    drainQueue();
                } finally {
                    draining.set(false);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
        }
        return START_NOT_STICKY;
    }

    private void drainQueue() {
        Context context = getApplicationContext();
        if (!TranscriptionExecutionGate.tryAcquire()) {
            AppLogger.event(context, "TRANSCRIPTION_DIRECT_QUEUE_DEFERRED_RUNNER_BUSY");
            TranscriptionScheduler.ensureDrainScheduled(context);
            return;
        }

        AppLogger.event(context, "TRANSCRIPTION_DIRECT_QUEUE_DRAIN_STARTED");
        try {
            while (System.currentTimeMillis() - serviceStartedAtMs < MAX_SERVICE_RUNTIME_MS) {
                SegmentRecord next = nextQueuedRecord(context);
                if (next == null) {
                    AppLogger.event(context, "TRANSCRIPTION_DIRECT_QUEUE_DRAIN_EMPTY");
                    return;
                }

                String segmentId = next.getSegmentId();
                String audioPath = next.getAudioPath();
                if (audioPath == null || audioPath.isEmpty()) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, null, 0L,
                            System.currentTimeMillis(), "FAILED", "SOURCE_AUDIO_MISSING");
                    continue;
                }

                // Prevent a pre-0.7.11 legacy per-segment job from later running the same segment.
                WorkManager.getInstance(context).cancelUniqueWork(TranscriptionScheduler.uniqueWorkName(segmentId));

                boolean manual = next.getReason() != null && next.getReason().startsWith("MANUAL_");
                processOne(context, segmentId, new File(audioPath), manual);
            }

            AppLogger.event(context, "TRANSCRIPTION_DIRECT_QUEUE_RUNTIME_LIMIT_REACHED");
        } finally {
            TranscriptionExecutionGate.release();
            // If the FGS reaches its runtime limit or another path queued work while it was
            // active, hand the remaining persisted queue back to the single background Worker.
            TranscriptionScheduler.ensureDrainScheduled(context);
        }
    }

    private void processOne(Context context, String segmentId, File audioFile, boolean forceRetranscribe) {
        int generation = TranscriptionResetManager.currentGeneration(context);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String selectedModelId = WhisperModelManager.selectedModelId(context);
            TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
            String selectedEngineId = LocalWhisperEngine.engineId(context, selectedModelId, pipeline);

            if (!isStillQueued(context, segmentId)) {
                log(context, "TRANSCRIPTION_DIRECT_QUEUE_ITEM_SKIPPED", segmentId, audioFile,
                        "ITEM_NO_LONGER_QUEUED", forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            }
            if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                log(context, "TRANSCRIPTION_DIRECT_QUEUE_ITEM_SKIPPED", segmentId, audioFile,
                        "RESET_GENERATION_CHANGED", forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            }
            if (!audioFile.isFile()) {
                if (TranscriptionRepository.exists(context, segmentId)) {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "TRANSCRIBED", "SOURCE_AUDIO_MISSING_OLD_TRANSCRIPT_RETAINED");
                } else {
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                            System.currentTimeMillis(), "FAILED", "SOURCE_AUDIO_MISSING");
                }
                log(context, "TRANSCRIPTION_DIRECT_SOURCE_MISSING", segmentId, audioFile,
                        null, forceRetranscribe, attempt, null, selectedModelId, selectedEngineId);
                return;
            }
            String pipelineReason = TranscriptionPipelineSettings.unavailableReason(
                    context, pipeline, selectedModelId);
            if (pipelineReason != null) {
                boolean modelWait = "SILERO_VAD_MODEL_MISSING".equals(pipelineReason)
                        || "LOCAL_WHISPER_MODEL_MISSING".equals(pipelineReason);
                if (modelWait) WhisperModelManager.enqueueModelDownload(context, selectedModelId);
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        modelWait ? "READY" : "FAILED", pipelineReason);
                log(context, "TRANSCRIPTION_SELECTED_PIPELINE_NOT_READY", segmentId, audioFile,
                        pipelineReason, forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            }
            if (!forceRetranscribe &&
                    TranscriptionRepository.isCurrentEngine(context, segmentId, selectedEngineId)) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED", null);
                return;
            }

            String slotReason = forceRetranscribe
                    ? "MANUAL_DIRECT_SLOT_WAIT" : "LOCAL_DIRECT_SLOT_WAIT";
            SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                    audioFile.lastModified(), System.currentTimeMillis(), "QUEUED", slotReason);
            log(context, "TRANSCRIPTION_DIRECT_SLOT_WAIT", segmentId, audioFile,
                    null, forceRetranscribe, attempt, null,
                    selectedModelId, selectedEngineId);

            try {
                synchronized (LocalWhisperEngine.class) {
                    if (!isStillQueued(context, segmentId)) {
                        log(context, "TRANSCRIPTION_DIRECT_QUEUE_ITEM_SKIPPED", segmentId, audioFile,
                                "REMOVED_WHILE_WAITING_FOR_SLOT", forceRetranscribe, attempt, null,
                                selectedModelId, selectedEngineId);
                        return;
                    }

                    long startedAt = System.currentTimeMillis();
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), startedAt, "TRANSCRIBING",
                            forceRetranscribe ? "MANUAL_DIRECT_TRANSCRIBING" : "LOCAL_DIRECT_TRANSCRIBING");
                    promote("文字起こし中: " + TranscriptionPipelineSettings.asrLabel(pipeline.asrBackend));
                    log(context, "TRANSCRIPTION_DIRECT_STARTED", segmentId, audioFile,
                            null, forceRetranscribe, attempt, null,
                            selectedModelId, selectedEngineId);

                    LocalWhisperEngine.Response response =
                            LocalWhisperEngine.transcribe(context, audioFile, selectedModelId, pipeline);
                    org.json.JSONArray savedSegments = response.skippedNoSpeech
                            ? new org.json.JSONArray()
                            : TranscriptionPipelineSettings.SPEAKER_SHERPA_CPU.equals(pipeline.speakerBackend)
                                ? SpeakerIdentifier.annotate(context, audioFile, response.segments)
                                : new org.json.JSONArray(response.segments.toString());
                    if (!TranscriptionResetManager.isCurrentGeneration(context, generation)) {
                        SegmentRepository.appendWithoutNotify(context, segmentId, audioFile, 0L,
                                System.currentTimeMillis(), "READY", null);
                        log(context, "TRANSCRIPTION_DIRECT_RESULT_DISCARDED_AFTER_RESET", segmentId,
                                audioFile, null, forceRetranscribe, attempt, null,
                                selectedModelId, selectedEngineId);
                        return;
                    }

                    TranscriptionRepository.save(context, segmentId, audioFile,
                            selectedEngineId, response.text, savedSegments);
                    SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                            audioFile.lastModified(), System.currentTimeMillis(), "TRANSCRIBED", null);

                    JSONObject metrics = new JSONObject();
                    metrics.put("decodeMs", response.decodeMs);
                    metrics.put("preprocessMs", response.preprocessMs);
                    metrics.put("inferenceMs", response.inferenceMs);
                    metrics.put("modelLoadMs", response.modelLoadMs);
                    metrics.put("whisperFullMs", response.whisperFullMs);
                    metrics.put("modelId", response.modelId);
                    metrics.put("modelLabel", response.modelLabel);
                    metrics.put("modelBytes", response.modelBytes);
                    metrics.put("textChars", response.text.length());
                    metrics.put("segmentCount", response.segmentCount);
                    metrics.put("snrProxyDb", response.snrProxyDb);
                    metrics.put("appliedGainDb", response.appliedGainDb);
                    metrics.put("runner", "foreground-single-drain");
                    metrics.put("asrBackend", pipeline.asrBackend);
                    metrics.put("vadBackend", pipeline.vadBackend);
                    metrics.put("denoiseBackend", pipeline.denoiseBackend);
                    metrics.put("speakerBackend", pipeline.speakerBackend);
                    metrics.put("automaticFallback", false);
                    log(context, "TRANSCRIPTION_DIRECT_SAVED", segmentId, audioFile,
                            null, forceRetranscribe, attempt, metrics,
                            selectedModelId, selectedEngineId);
                    promote("文字起こしキューを処理中");
                    return;
                }
            } catch (OutOfMemoryError oom) {
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(), "FAILED", "LOCAL_TRANSCRIPTION_OOM");
                log(context, "TRANSCRIPTION_DIRECT_OOM", segmentId, audioFile,
                        oom.getClass().getSimpleName(), forceRetranscribe, attempt, null,
                        selectedModelId, selectedEngineId);
                return;
            } catch (Exception error) {
                boolean retry = attempt < MAX_ATTEMPTS;
                String reason = forceRetranscribe
                        ? (retry ? "MANUAL_DIRECT_RETRY" : "MANUAL_DIRECT_FAILED")
                        : (retry ? "LOCAL_DIRECT_RETRY" : "LOCAL_DIRECT_FAILED");
                SegmentRepository.appendWithoutNotify(context, segmentId, audioFile,
                        audioFile.lastModified(), System.currentTimeMillis(),
                        retry ? "RETRY_WAIT" : "FAILED", reason);
                log(context, retry ? "TRANSCRIPTION_DIRECT_RETRY" : "TRANSCRIPTION_DIRECT_FAILED",
                        segmentId, audioFile,
                        error.getClass().getSimpleName() + ": " + safeMessage(error),
                        forceRetranscribe, attempt, null, selectedModelId, selectedEngineId);
                if (!retry) {
                    return;
                }
                if (!sleepBackoff(attempt)) {
                    return;
                }
            }
        }
    }

    private SegmentRecord nextQueuedRecord(Context context) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        List<SegmentRecord> queued = new ArrayList<>();
        for (SegmentRecord record : records) {
            if (("QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus()))
                    && record.getAudioAvailable()) {
                queued.add(record);
            }
        }
        queued.sort(Comparator.comparingLong(record -> {
            long queuedAt = record.getQueueEnqueuedAtMs();
            return queuedAt > 0L ? queuedAt : record.getStateChangedAtMs();
        }));
        return queued.isEmpty() ? null : queued.get(0);
    }

    private boolean isStillQueued(Context context, String segmentId) {
        for (SegmentRecord record : SegmentHistoryRepository.load(context)) {
            if (segmentId.equals(record.getSegmentId())) {
                return "QUEUED".equals(record.getStatus()) || "RETRY_WAIT".equals(record.getStatus());
            }
        }
        return false;
    }

    private boolean sleepBackoff(int attempt) {
        long delayMs = attempt == 1 ? 30_000L : 60_000L;
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "文字起こしキュー",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("ユーザーが開始したローカル文字起こし処理");
        manager.createNotificationChannel(channel);
    }

    private void promote(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("24hRecoder")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        AppLogger.event(getApplicationContext(), "TRANSCRIPTION_DIRECT_QUEUE_FGS_TIMEOUT");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void log(Context context, String event, String segmentId, File file,
                            String message, boolean forceRetranscribe, int attempt, JSONObject extra,
                            String modelId, String engineId) {
        try {
            JSONObject details = extra == null ? new JSONObject() : extra;
            details.put("segmentId", segmentId);
            details.put("file", file == null ? JSONObject.NULL : file.getName());
            details.put("engine", engineId);
            details.put("modelId", modelId);
            details.put("manualRetranscription", forceRetranscribe);
            details.put("attempt", attempt);
            details.put("asrReady", WhisperModelManager.isModelReady(context, modelId));
            details.put("vadReady", WhisperModelManager.isVadReady(context));
            if (message != null) {
                details.put("message", message);
            }
            AppLogger.event(context, event, details);
        } catch (Exception ignored) {
        }
    }

    private static String modelLabel(String modelId) {
        WhisperModelManager.ModelSpec spec = WhisperModelManager.modelSpec(modelId);
        return spec == null ? modelId : spec.label;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
