package com.sktpj.recorder24h.transcription;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import com.sktpj.recorder24h.storage.SegmentRepository;
import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lives in the recorder process. It freezes the selected pipeline at recording-segment boundaries
 * and forwards small PCM/VAD messages to the dedicated :streaming_asr process. Heavy inference is
 * never executed on the AudioRecord thread. No alternate backend is selected on failure.
 */
public final class FullStreamingTranscriptionCoordinator {
    private static final Object LOCK = new Object();
    private static final int MAX_PENDING_MESSAGES = 300; // about 30 seconds of 100 ms PCM chunks
    private static final ExecutorService FAILURE_EXECUTOR = Executors.newSingleThreadExecutor();

    private static Context appContext;
    private static TranscriptionPipelineSettings.Snapshot activePipeline;
    private static String activeModelId;
    private static Messenger remote;
    private static boolean binding;
    private static boolean bound;
    private static boolean currentFailed;
    private static String currentFailure;
    private static final ArrayDeque<Message> pending = new ArrayDeque<>();

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                remote = new Messenger(service);
                binding = false;
                bound = true;
                while (!pending.isEmpty() && remote != null) {
                    Message message = pending.removeFirst();
                    try {
                        remote.send(message);
                    } catch (Exception error) {
                        failLocked("STREAMING_ASR_BINDER_SEND_FAILED", error);
                        break;
                    }
                }
                logLocked("FULL_STREAMING_SERVICE_CONNECTED", null);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                remote = null;
                binding = false;
                bound = false;
                if (isLiveLocked()) {
                    failLocked("STREAMING_ASR_SERVICE_DISCONNECTED", null);
                }
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }
    };

    private FullStreamingTranscriptionCoordinator() {
    }

    public static void resetStream(Context context) {
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            activePipeline = TranscriptionPipelineSettings.snapshot(appContext);
            activeModelId = WhisperModelManager.selectedModelId(appContext);
            currentFailed = false;
            currentFailure = null;
            pending.clear();
            if (!TranscriptionPipelineSettings.isLiveStreaming(activePipeline)) {
                FullStreamingStateStore.writeLiveState(appContext, "OFF", activePipeline.asrBackend,
                        "", "", "", null, 0, null);
                return;
            }
            String reason = TranscriptionPipelineSettings.unavailableReason(appContext, activePipeline, activeModelId);
            if (reason != null) {
                failLocked(reason, null);
                return;
            }
            ensureBoundLocked();
            sendOrQueueLocked(configMessage(StreamingTranscriptionService.MSG_RESET, 0L,
                    activePipeline, activeModelId));
            logLocked("FULL_STREAMING_RECORDER_SESSION_STARTED", null);
        }
    }

    /** VAD selection frozen for the current five-minute recording segment. */
    public static String currentVadBackend(Context context) {
        synchronized (LOCK) {
            if (activePipeline == null) {
                appContext = context.getApplicationContext();
                activePipeline = TranscriptionPipelineSettings.snapshot(appContext);
                activeModelId = WhisperModelManager.selectedModelId(appContext);
            }
            return activePipeline.vadBackend;
        }
    }

    public static void observePcm(Context context, byte[] pcm, int length, long startPtsUs,
                                  StreamingVadStore.LiveObservation observation) {
        if (pcm == null || length <= 0) return;
        synchronized (LOCK) {
            if (appContext == null) appContext = context.getApplicationContext();
            if (!isLiveLocked() || currentFailed) return;
            if (observation == null || !observation.available) {
                failLocked("LIVE_STREAMING_VAD_UNAVAILABLE", null);
                return;
            }
            ensureBoundLocked();
            Bundle data = new Bundle();
            byte[] copy = new byte[length];
            System.arraycopy(pcm, 0, copy, 0, length);
            data.putByteArray("pcm", copy);
            data.putLong("startPtsUs", startPtsUs);
            data.putLong("currentEndUs", observation.currentEndUs);
            data.putLong("activeSpeechStartUs", observation.activeSpeechStartUs);
            data.putLongArray("closedStartsUs", observation.closedStartsUs);
            data.putLongArray("closedEndsUs", observation.closedEndsUs);
            Message message = Message.obtain(null, StreamingTranscriptionService.MSG_PCM);
            message.setData(data);
            sendOrQueueLocked(message);
        }
    }

    /**
     * Called from StreamingVadStore.persistSegment before SegmentRepository publishes READY.
     * Ownership is therefore durable before the normal transcription receiver can see the audio.
     */
    public static void onSegmentBoundary(Context context, String segmentId,
                                         long segmentBasePtsUs, long segmentEndPtsUs,
                                         long startedAtMs, long endedAtMs) {
        synchronized (LOCK) {
            if (appContext == null) appContext = context.getApplicationContext();
            TranscriptionPipelineSettings.Snapshot oldPipeline = activePipeline == null
                    ? TranscriptionPipelineSettings.snapshot(appContext) : activePipeline;
            String oldModelId = activeModelId == null
                    ? WhisperModelManager.selectedModelId(appContext) : activeModelId;
            boolean oldLive = TranscriptionPipelineSettings.isLiveStreaming(oldPipeline);

            TranscriptionPipelineSettings.Snapshot nextPipeline = TranscriptionPipelineSettings.snapshot(appContext);
            String nextModelId = WhisperModelManager.selectedModelId(appContext);

            if (oldLive) {
                FullStreamingStateStore.markOwned(appContext, segmentId, oldPipeline, oldModelId,
                        startedAtMs, endedAtMs);
                if (currentFailed) {
                    String reason = currentFailure == null ? "FULL_STREAMING_RUNTIME_FAILED" : currentFailure;
                    FullStreamingStateStore.markFailed(appContext, segmentId,
                            LocalWhisperEngine.engineId(appContext, oldModelId, oldPipeline), reason);
                    markFailedWhenPublished(appContext, segmentId, reason);
                } else {
                    ensureBoundLocked();
                    Message boundary = Message.obtain(null, StreamingTranscriptionService.MSG_BOUNDARY);
                    Bundle data = new Bundle();
                    data.putString("segmentId", segmentId);
                    data.putLong("segmentBasePtsUs", segmentBasePtsUs);
                    data.putLong("segmentEndPtsUs", segmentEndPtsUs);
                    data.putLong("startedAtMs", startedAtMs);
                    data.putLong("endedAtMs", endedAtMs);
                    putPipeline(data, "old", oldPipeline, oldModelId);
                    putPipeline(data, "next", nextPipeline, nextModelId);
                    boundary.setData(data);
                    sendOrQueueLocked(boundary);
                }
            } else if (TranscriptionPipelineSettings.isLiveStreaming(nextPipeline)) {
                String nextReason = TranscriptionPipelineSettings.unavailableReason(appContext, nextPipeline, nextModelId);
                if (nextReason == null) {
                    ensureBoundLocked();
                    sendOrQueueLocked(configMessage(StreamingTranscriptionService.MSG_RESET,
                            segmentEndPtsUs, nextPipeline, nextModelId));
                }
            }

            activePipeline = nextPipeline;
            activeModelId = nextModelId;
            currentFailed = false;
            currentFailure = null;
            if (TranscriptionPipelineSettings.isLiveStreaming(nextPipeline)) {
                String reason = TranscriptionPipelineSettings.unavailableReason(appContext, nextPipeline, nextModelId);
                if (reason != null) failLocked(reason, null);
            }
            try {
                JSONObject details = new JSONObject()
                        .put("segmentId", segmentId)
                        .put("oldPipeline", oldPipeline.toJson())
                        .put("nextPipeline", nextPipeline.toJson())
                        .put("automaticFallback", false);
                AppLogger.event(appContext, "FULL_STREAMING_SEGMENT_BOUNDARY", details);
            } catch (Exception ignored) {
            }
        }
    }

    private static Message configMessage(int what, long basePtsUs,
                                         TranscriptionPipelineSettings.Snapshot pipeline,
                                         String modelId) {
        Message message = Message.obtain(null, what);
        Bundle data = new Bundle();
        data.putLong("basePtsUs", basePtsUs);
        putPipeline(data, "pipeline", pipeline, modelId);
        message.setData(data);
        return message;
    }

    private static void putPipeline(Bundle data, String prefix,
                                    TranscriptionPipelineSettings.Snapshot pipeline,
                                    String modelId) {
        data.putString(prefix + "Mode", pipeline.executionMode);
        data.putString(prefix + "Asr", pipeline.asrBackend);
        data.putString(prefix + "Vad", pipeline.vadBackend);
        data.putString(prefix + "Denoise", pipeline.denoiseBackend);
        data.putString(prefix + "Speaker", pipeline.speakerBackend);
        data.putString(prefix + "ModelId", modelId);
    }

    private static boolean isLiveLocked() {
        return TranscriptionPipelineSettings.isLiveStreaming(activePipeline);
    }

    private static void ensureBoundLocked() {
        if (appContext == null || bound || binding) return;
        binding = true;
        boolean ok;
        try {
            ok = appContext.bindService(
                    new Intent(appContext, StreamingTranscriptionService.class),
                    CONNECTION,
                    Context.BIND_AUTO_CREATE);
        } catch (RuntimeException error) {
            binding = false;
            failLocked("STREAMING_ASR_BIND_FAILED", error);
            return;
        }
        if (!ok) {
            binding = false;
            failLocked("STREAMING_ASR_BIND_REJECTED", null);
        } else {
            logLocked("FULL_STREAMING_SERVICE_BINDING", null);
        }
    }

    private static void sendOrQueueLocked(Message message) {
        if (remote != null) {
            try {
                remote.send(message);
                return;
            } catch (Exception error) {
                remote = null;
                bound = false;
                failLocked("STREAMING_ASR_BINDER_SEND_FAILED", error);
                return;
            }
        }
        if (pending.size() >= MAX_PENDING_MESSAGES) {
            pending.clear();
            failLocked("STREAMING_ASR_BIND_BACKPRESSURE_OVERFLOW", null);
            return;
        }
        pending.addLast(message);
    }

    private static void failLocked(String reason, Throwable error) {
        currentFailed = true;
        currentFailure = reason + (error == null ? "" : ":" + error.getClass().getSimpleName());
        if (appContext != null) {
            FullStreamingStateStore.writeLiveState(appContext, "ERROR",
                    activePipeline == null ? null : activePipeline.asrBackend,
                    "", "", "", null, pending.size(), currentFailure);
            logLocked("FULL_STREAMING_RUNTIME_FAILED", currentFailure);
        }
    }

    private static void logLocked(String event, String message) {
        if (appContext == null) return;
        try {
            JSONObject details = new JSONObject();
            details.put("message", message == null ? JSONObject.NULL : message);
            details.put("automaticFallback", false);
            details.put("pipeline", activePipeline == null ? JSONObject.NULL : activePipeline.toJson());
            details.put("pendingTransportMessages", pending.size());
            AppLogger.event(appContext, event, details);
        } catch (Exception ignored) {
        }
    }

    private static void markFailedWhenPublished(Context context, String segmentId, String reason) {
        Context app = context.getApplicationContext();
        FAILURE_EXECUTOR.execute(() -> {
            File audio = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                List<SegmentRecord> records = SegmentHistoryRepository.load(app);
                for (SegmentRecord record : records) {
                    if (!segmentId.equals(record.getSegmentId())) continue;
                    if (record.getAudioAvailable() && record.getAudioPath() != null) {
                        audio = new File(record.getAudioPath());
                    }
                    if ("READY".equals(record.getStatus()) && audio != null) {
                        SegmentRepository.appendWithoutNotify(app, segmentId, audio,
                                record.getStartedAtMs(), System.currentTimeMillis(),
                                "FAILED", "FULL_STREAMING_ASR_FAILED:" + reason);
                        return;
                    }
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }
}
