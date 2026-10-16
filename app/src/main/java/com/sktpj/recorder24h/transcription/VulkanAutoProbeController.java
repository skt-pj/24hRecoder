package com.sktpj.recorder24h.transcription;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import com.sktpj.recorder24h.ui.SegmentHistoryRepository;
import com.sktpj.recorder24h.ui.SegmentRecord;
import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DriveLogSync;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-button CPU vs current-production Vulkan benchmark using one fixed retained audio file. */
public final class VulkanAutoProbeController {
    // Vulkan runs first so an extremely slow CPU test cannot leave the app backgrounded before
    // the GPU comparison has even started. CPU is intentionally last and may end as TIMEOUT.
    private static final String[] PROFILES = new String[] {
            VulkanProbeStore.PROFILE_VULKAN_SAFE,
            VulkanProbeStore.PROFILE_CPU
    };
    private static final long START_TIMEOUT_MS = 20_000L;
    private static final long VULKAN_PROFILE_TIMEOUT_MS = 4L * 60L * 1000L;
    private static final long CPU_PROFILE_TIMEOUT_MS = 3L * 60L * 1000L;
    private static final long POLL_MS = 500L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private VulkanAutoProbeController() {}

    public static boolean start(Context context) {
        Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return false;
        EXECUTOR.execute(() -> runSession(app));
        return true;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void runSession(Context context) {
        String modelId = WhisperModelManager.selectedModelId(context);
        File model = WhisperModelManager.modelFile(context, modelId);
        SegmentRecord source = selectBenchmarkSource(context);
        File audio = source == null || source.getAudioPath() == null
                ? null : new File(source.getAudioPath());
        VulkanAutoProbeStore.start(
                context,
                PROFILES.length,
                modelId,
                audio == null ? null : audio.getName(),
                audio == null ? null : audio.getAbsolutePath(),
                source == null ? 0L : source.getDurationMs());
        try {
            if (!model.isFile()) throw new IllegalStateException("WHISPER_MODEL_MISSING");
            if (audio == null || !audio.isFile()) throw new IllegalStateException("RETAINED_AUDIO_MISSING");

            AppLogger.event(context, "CPU_VULKAN_BENCHMARK_STARTED",
                    new JSONObject()
                            .put("profileCount", PROFILES.length)
                            .put("profileOrder", "vulkan,cpu")
                            .put("modelId", modelId)
                            .put("audioFile", audio.getName())
                            .put("audioPathLocked", true)
                            .put("sourceDurationMs", source == null ? 0L : source.getDurationMs())
                            .put("durationsMs", "2000,10000")
                            .put("cpuTimeoutMs", CPU_PROFILE_TIMEOUT_MS)
                            .put("vulkanTimeoutMs", VULKAN_PROFILE_TIMEOUT_MS));

            for (int index = 0; index < PROFILES.length; index++) {
                String profile = PROFILES[index];
                ensurePreviousProbeGone(context);
                String requestId = UUID.randomUUID().toString();
                VulkanProbeStore.prepareRequest(context, requestId, profile, modelId, audio.getName());
                VulkanAutoProbeStore.profileStarting(context, index, profile);
                long requestedAtMs = System.currentTimeMillis();
                AppLogger.event(context, "CPU_VULKAN_BENCHMARK_PROFILE_REQUESTED",
                        new JSONObject()
                                .put("index", index)
                                .put("profile", profile)
                                .put("requestId", requestId)
                                .put("label", VulkanProbeStore.profileLabel(profile))
                                .put("modelId", modelId)
                                .put("audioFile", audio.getName()));

                context.startService(new Intent(context, VulkanProbeService.class)
                        .setAction(VulkanProbeService.ACTION_RUN)
                        .putExtra(VulkanProbeService.EXTRA_PROFILE, profile)
                        .putExtra(VulkanProbeService.EXTRA_REQUEST_ID, requestId)
                        .putExtra(VulkanProbeService.EXTRA_MODEL_ID, modelId)
                        .putExtra(VulkanProbeService.EXTRA_AUDIO_PATH, audio.getAbsolutePath()));

                JSONObject result = waitForProfile(context, profile, requestId, requestedAtMs);
                VulkanAutoProbeStore.profileResult(context, index, profile, result);
                AppLogger.event(context, "CPU_VULKAN_BENCHMARK_PROFILE_RESULT",
                        new JSONObject(result.toString())
                                .put("index", index)
                                .put("profile", profile)
                                .put("requestId", requestId)
                                .put("label", VulkanProbeStore.profileLabel(profile)));
                ensurePreviousProbeGone(context);
                sleep(1_000L);
            }
            VulkanAutoProbeStore.complete(context);
            AppLogger.event(context, "CPU_VULKAN_BENCHMARK_COMPLETED",
                    new JSONObject()
                            .put("profileCount", PROFILES.length)
                            .put("modelId", modelId)
                            .put("audioFile", audio.getName()));
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            VulkanAutoProbeStore.fail(context, message);
            try {
                AppLogger.event(context, "CPU_VULKAN_BENCHMARK_FAILED",
                        new JSONObject().put("error", message));
            } catch (Exception ignored) {}
        } finally {
            RUNNING.set(false);
            DriveLogSync.enqueueNow(context);
            DriveLogSync.syncDirectAsync(context);
        }
    }

    private static SegmentRecord selectBenchmarkSource(Context context) {
        List<SegmentRecord> records = SegmentHistoryRepository.load(context);
        Comparator<SegmentRecord> newestFirst = Comparator.comparingLong(SegmentRecord::getSortTimeMs).reversed();
        SegmentRecord fallback = records.stream()
                .filter(record -> record.getAudioAvailable() && record.getAudioPath() != null)
                .sorted(newestFirst)
                .findFirst()
                .orElse(null);
        return records.stream()
                .filter(record -> record.getAudioAvailable() && record.getAudioPath() != null)
                .filter(record -> record.getDurationMs() >= 10_000L)
                .sorted(newestFirst)
                .findFirst()
                .orElse(fallback);
    }

    private static JSONObject waitForProfile(Context context, String profile, String requestId,
                                             long requestedAtMs) throws Exception {
        boolean seenRunning = false;
        long profileTimeoutMs = VulkanProbeStore.PROFILE_CPU.equals(profile)
                ? CPU_PROFILE_TIMEOUT_MS : VULKAN_PROFILE_TIMEOUT_MS;
        long deadline = requestedAtMs + profileTimeoutMs;
        String lastPhase = "WAITING_FOR_PROCESS";
        VulkanAutoProbeStore.updatePhase(context, profile, lastPhase);

        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            JSONObject status = VulkanProbeStore.read(context);
            String currentRequestId = status.optString("requestId", "");
            String currentProfile = status.optString("profile", "");
            String state = status.optString("state", "IDLE");
            String phase = status.optString("phase", "-");
            boolean currentRequest = requestId.equals(currentRequestId) && profile.equals(currentProfile);

            if (currentRequest) {
                if (!phase.equals(lastPhase)) {
                    lastPhase = phase;
                    VulkanAutoProbeStore.updatePhase(context, profile, phase);
                }
                if ("RUNNING".equals(state)) {
                    seenRunning = true;
                } else if ("COMPLETED".equals(state)) {
                    return new JSONObject()
                            .put("outcome", "COMPLETED")
                            .put("probeState", state)
                            .put("lastPhase", phase)
                            .put("probeStatus", new JSONObject(status.toString()));
                } else if ("FAILED".equals(state)) {
                    return new JSONObject()
                            .put("outcome", "FAILED")
                            .put("probeState", state)
                            .put("lastPhase", phase)
                            .put("error", status.optString("error", ""))
                            .put("probeStatus", new JSONObject(status.toString()));
                }
            }

            int pid = probePid(context);
            if (currentRequest && seenRunning && pid <= 0 && "RUNNING".equals(state)) {
                JSONObject result = new JSONObject()
                        .put("outcome", "PROCESS_EXIT")
                        .put("probeState", state)
                        .put("lastPhase", phase)
                        .put("probeStatus", new JSONObject(status.toString()));
                JSONObject exit = latestProbeExit(context, requestedAtMs);
                if (exit != null) result.put("exit", exit);
                return result;
            }

            if (!seenRunning && now - requestedAtMs > START_TIMEOUT_MS) {
                JSONObject result = new JSONObject()
                        .put("outcome", "START_TIMEOUT")
                        .put("lastPhase", currentRequest ? phase : "WAITING_FOR_PROCESS");
                if (currentRequest) result.put("probeStatus", new JSONObject(status.toString()));
                JSONObject exit = latestProbeExit(context, requestedAtMs);
                if (exit != null) result.put("exit", exit);
                return result;
            }
            sleep(POLL_MS);
        }

        long timedOutAtMs = System.currentTimeMillis();
        JSONObject status = VulkanProbeStore.read(context);
        boolean currentRequest = requestId.equals(status.optString("requestId", ""))
                && profile.equals(status.optString("profile", ""));
        long phaseStartedAtMs = currentRequest ? status.optLong("phaseStartedAtMs", 0L) : 0L;
        int pid = probePid(context);
        if (pid > 0) {
            try { Process.killProcess(pid); } catch (Throwable ignored) {}
        }
        JSONObject result = new JSONObject()
                .put("outcome", "TIMEOUT")
                .put("lastPhase", currentRequest ? status.optString("phase", lastPhase) : lastPhase)
                .put("timeoutMs", profileTimeoutMs)
                .put("timedOutByBenchmark", true)
                .put("crashConfirmed", false);
        if (phaseStartedAtMs > 0L) {
            result.put("phaseElapsedMs", Math.max(0L, timedOutAtMs - phaseStartedAtMs));
        }
        if (currentRequest) result.put("probeStatus", new JSONObject(status.toString()));
        return result;
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

    private static JSONObject latestProbeExit(Context context, long sinceMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return null;
            String target = context.getPackageName() + ":vulkan_probe";
            ApplicationExitInfo best = null;
            for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 30)) {
                if (info == null || !target.equals(info.getProcessName())) continue;
                if (info.getTimestamp() + 3_000L < sinceMs) continue;
                if (best == null || info.getTimestamp() > best.getTimestamp()) best = info;
            }
            if (best == null) return null;
            JSONObject row = new JSONObject();
            row.put("processName", best.getProcessName());
            row.put("reason", best.getReason());
            row.put("reasonName", reasonName(best.getReason()));
            row.put("status", best.getStatus());
            row.put("importance", best.getImportance());
            row.put("pssKb", best.getPss());
            row.put("rssKb", best.getRss());
            row.put("timestampMs", best.getTimestamp());
            row.put("description", best.getDescription() == null ? "" : best.getDescription());
            byte[] summary = best.getProcessStateSummary();
            row.put("processStateSummary", summary == null ? ""
                    : new String(summary, StandardCharsets.UTF_8));
            return row;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String reasonName(int reason) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (reason == ApplicationExitInfo.REASON_CRASH) return "CRASH";
            if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) return "CRASH_NATIVE";
            if (reason == ApplicationExitInfo.REASON_LOW_MEMORY) return "LOW_MEMORY";
            if (reason == ApplicationExitInfo.REASON_ANR) return "ANR";
            if (reason == ApplicationExitInfo.REASON_USER_REQUESTED) return "USER_REQUESTED";
            if (reason == ApplicationExitInfo.REASON_EXIT_SELF) return "EXIT_SELF";
            if (reason == ApplicationExitInfo.REASON_SIGNALED) return "SIGNALED";
        }
        return "REASON_" + reason;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
