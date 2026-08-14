package com.sktpj.recorder24h.transcription;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DriveLogSync;

import org.json.JSONObject;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Measures the same retained 30-second audio window once with every Whisper model. */
public final class Model30sBenchmarkController {
    public static final long BENCHMARK_DURATION_MS = 30_000L;

    private static final long START_TIMEOUT_MS = 20_000L;
    private static final long MODEL_TIMEOUT_MS = 5L * 60L * 1000L;
    private static final long POLL_MS = 500L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Model30sBenchmarkController() {}

    public static boolean start(Context context) {
        if (VulkanAutoProbeController.isRunning()) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> runSession(app));
        return true;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static boolean isWhisperBackendSupported(Context context) {
        String asr = TranscriptionPipelineSettings.snapshot(context).asrBackend;
        return TranscriptionPipelineSettings.ASR_WHISPER_CPU.equals(asr)
                || TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(asr);
    }

    public static String currentBackendLabel(Context context) {
        String asr = TranscriptionPipelineSettings.snapshot(context).asrBackend;
        if (TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(asr)) return "Vulkan（現在の実運用設定）";
        if (TranscriptionPipelineSettings.ASR_WHISPER_CPU.equals(asr)) return "CPU";
        return TranscriptionPipelineSettings.asrLabel(asr);
    }

    private static void runSession(Context context) {
        WhisperModelManager.ModelSpec[] models = WhisperModelManager.comparisonModels();
        TranscriptionPipelineSettings.Snapshot pipeline = TranscriptionPipelineSettings.snapshot(context);
        String profile = profileForBackend(pipeline.asrBackend);
        String backendLabel = currentBackendLabel(context);
        SegmentRecord source = selectBenchmarkSource(context);
        File audio = source == null || source.getAudioPath() == null
                ? null : new File(source.getAudioPath());

        Model30sBenchmarkStore.start(
                context,
                models.length,
                profile,
                backendLabel,
                audio == null ? null : audio.getName(),
                audio == null ? null : audio.getAbsolutePath());

        try {
            if (profile == null) throw new IllegalStateException("WHISPER_CPU_OR_VULKAN_BACKEND_REQUIRED");
            if (audio == null || !audio.isFile()) throw new IllegalStateException("RETAINED_30S_AUDIO_MISSING");

            AppLogger.event(context, "MODEL_30S_BENCHMARK_STARTED",
                    new JSONObject()
                            .put("modelCount", models.length)
                            .put("profile", profile)
                            .put("backendLabel", backendLabel)
                            .put("audioFile", audio.getName())
                            .put("sourceDurationMs", source == null ? 0L : source.getDurationMs())
                            .put("benchmarkDurationMs", BENCHMARK_DURATION_MS)
                            .put("fixedInput", true)
                            .put("vadApplied", false));

            for (int index = 0; index < models.length; index++) {
                WhisperModelManager.ModelSpec spec = models[index];
                Model30sBenchmarkStore.modelStarting(context, index, spec.id, spec.label);

                if (!WhisperModelManager.isModelReady(context, spec.id)) {
                    JSONObject skipped = new JSONObject()
                            .put("outcome", "MODEL_MISSING")
                            .put("benchmarkDurationMs", BENCHMARK_DURATION_MS);
                    Model30sBenchmarkStore.modelResult(context, index, spec.id, spec.label, skipped);
                    continue;
                }

                ensurePreviousProbeGone(context);
                String requestId = UUID.randomUUID().toString();
                VulkanProbeStore.prepareRequest(context, requestId, profile, spec.id, audio.getName());
                long requestedAtMs = System.currentTimeMillis();

                AppLogger.event(context, "MODEL_30S_BENCHMARK_MODEL_REQUESTED",
                        new JSONObject()
                                .put("index", index)
                                .put("modelId", spec.id)
                                .put("modelLabel", spec.label)
                                .put("profile", profile)
                                .put("requestId", requestId)
                                .put("benchmarkDurationMs", BENCHMARK_DURATION_MS));

                context.startService(new Intent(context, VulkanProbeService.class)
                        .setAction(VulkanProbeService.ACTION_RUN)
                        .putExtra(VulkanProbeService.EXTRA_PROFILE, profile)
                        .putExtra(VulkanProbeService.EXTRA_REQUEST_ID, requestId)
                        .putExtra(VulkanProbeService.EXTRA_MODEL_ID, spec.id)
                        .putExtra(VulkanProbeService.EXTRA_AUDIO_PATH, audio.getAbsolutePath())
                        .putExtra(VulkanProbeService.EXTRA_DURATION_MS, BENCHMARK_DURATION_MS));

                JSONObject result = waitForModel(context, profile, requestId, requestedAtMs);
                result.put("benchmarkDurationMs", BENCHMARK_DURATION_MS);
                Model30sBenchmarkStore.modelResult(context, index, spec.id, spec.label, result);
                AppLogger.event(context, "MODEL_30S_BENCHMARK_MODEL_RESULT",
                        new JSONObject(result.toString())
                                .put("index", index)
                                .put("modelId", spec.id)
                                .put("modelLabel", spec.label)
                                .put("profile", profile)
                                .put("requestId", requestId));
                ensurePreviousProbeGone(context);
                sleep(500L);
            }

            Model30sBenchmarkStore.complete(context);
            AppLogger.event(context, "MODEL_30S_BENCHMARK_COMPLETED",
                    new JSONObject()
                            .put("modelCount", models.length)
                            .put("profile", profile)
                            .put("audioFile", audio.getName())
                            .put("benchmarkDurationMs", BENCHMARK_DURATION_MS));
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            Model30sBenchmarkStore.fail(context, message);
            try {
                AppLogger.event(context, "MODEL_30S_BENCHMARK_FAILED",
                        new JSONObject().put("error", message));
            } catch (Exception ignored) {}
        } finally {
            RUNNING.set(false);
            DriveLogSync.enqueueNow(context);
            DriveLogSync.syncDirectAsync(context);
        }
    }

    private static String profileForBackend(String asrBackend) {
        if (TranscriptionPipelineSettings.ASR_WHISPER_VULKAN.equals(asrBackend)) {
            return VulkanProbeStore.PROFILE_VULKAN_SAFE;
        }
        if (TranscriptionPipelineSettings.ASR_WHISPER_CPU.equals(asrBackend)) {
            return VulkanProbeStore.PROFILE_CPU;
        }
        return null;
    }

    private static SegmentRecord selectBenchmarkSource(Context context) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        return records.stream()
                .filter(record -> record.getAudioAvailable() && record.getAudioPath() != null)
                .filter(record -> record.getDurationMs() >= BENCHMARK_DURATION_MS)
                .sorted(Comparator.comparingLong(SegmentRecord::getSortTimeMs).reversed())
                .findFirst()
                .orElse(null);
    }

    private static JSONObject waitForModel(Context context, String profile, String requestId,
                                           long requestedAtMs) throws Exception {
        boolean seenRunning = false;
        long deadline = requestedAtMs + MODEL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            JSONObject status = VulkanProbeStore.read(context);
            boolean currentRequest = requestId.equals(status.optString("requestId", ""))
                    && profile.equals(status.optString("profile", ""));
            String state = status.optString("state", "IDLE");
            if (currentRequest) {
                if ("RUNNING".equals(state)) {
                    seenRunning = true;
                } else if ("COMPLETED".equals(state)) {
                    return new JSONObject()
                            .put("outcome", "COMPLETED")
                            .put("probeStatus", new JSONObject(status.toString()));
                } else if ("FAILED".equals(state)) {
                    return new JSONObject()
                            .put("outcome", "FAILED")
                            .put("error", status.optString("error", ""))
                            .put("probeStatus", new JSONObject(status.toString()));
                }
            }

            if (currentRequest && seenRunning && probePid(context) <= 0 && "RUNNING".equals(state)) {
                return new JSONObject()
                        .put("outcome", "PROCESS_EXIT")
                        .put("phase", status.optString("phase", ""))
                        .put("probeStatus", new JSONObject(status.toString()));
            }

            if (!seenRunning && now - requestedAtMs > START_TIMEOUT_MS) {
                return new JSONObject()
                        .put("outcome", "START_TIMEOUT")
                        .put("probeStatus", currentRequest ? new JSONObject(status.toString()) : JSONObject.NULL);
            }
            sleep(POLL_MS);
        }

        JSONObject status = VulkanProbeStore.read(context);
        int pid = probePid(context);
        if (pid > 0) {
            try { Process.killProcess(pid); } catch (Throwable ignored) {}
        }
        return new JSONObject()
                .put("outcome", "TIMEOUT")
                .put("timeoutMs", MODEL_TIMEOUT_MS)
                .put("phase", status.optString("phase", ""))
                .put("probeStatus", new JSONObject(status.toString()));
    }

    private static void ensurePreviousProbeGone(Context context) {
        for (int i = 0; i < 12; i++) {
            int pid = probePid(context);
            if (pid <= 0) return;
            if (i == 8) {
                try { Process.killProcess(pid); } catch (Throwable ignored) {}
            }
            sleep(500L);
        }
    }

    private static int probePid(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return -1;
            List<ActivityManager.RunningAppProcessInfo> rows = manager.getRunningAppProcesses();
            if (rows == null) return -1;
            String target = context.getPackageName() + ":vulkan_probe";
            for (ActivityManager.RunningAppProcessInfo row : rows) {
                if (row != null && target.equals(row.processName)) return row.pid;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
