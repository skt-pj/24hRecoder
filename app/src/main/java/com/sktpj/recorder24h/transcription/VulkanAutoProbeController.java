package com.sktpj.recorder24h.transcription;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DriveLogSync;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs every Vulkan diagnostic profile after a single user action. */
public final class VulkanAutoProbeController {
    private static final String[] PROFILES = new String[] {
            VulkanProbeStore.PROFILE_CPU,
            VulkanProbeStore.PROFILE_VULKAN_DEFAULT,
            VulkanProbeStore.PROFILE_VULKAN_COOPMAT_OFF,
            VulkanProbeStore.PROFILE_VULKAN_GRAPH_OFF,
            VulkanProbeStore.PROFILE_VULKAN_SAFE
    };
    private static final long START_TIMEOUT_MS = 20_000L;
    private static final long PROFILE_TIMEOUT_MS = 8L * 60L * 1000L;
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
        VulkanAutoProbeStore.start(context, PROFILES.length);
        try {
            AppLogger.event(context, "VULKAN_AUTO_PROBE_STARTED",
                    new JSONObject().put("profileCount", PROFILES.length));
            for (int index = 0; index < PROFILES.length; index++) {
                String profile = PROFILES[index];
                ensurePreviousProbeGone(context);
                VulkanAutoProbeStore.profileStarting(context, index, profile);
                long requestedAtMs = System.currentTimeMillis();
                AppLogger.event(context, "VULKAN_AUTO_PROBE_PROFILE_REQUESTED",
                        new JSONObject()
                                .put("index", index)
                                .put("profile", profile)
                                .put("label", VulkanProbeStore.profileLabel(profile)));

                context.startService(new Intent(context, VulkanProbeService.class)
                        .setAction(VulkanProbeService.ACTION_RUN)
                        .putExtra(VulkanProbeService.EXTRA_PROFILE, profile));

                JSONObject result = waitForProfile(context, profile, requestedAtMs);
                VulkanAutoProbeStore.profileResult(context, index, profile, result);
                AppLogger.event(context, "VULKAN_AUTO_PROBE_PROFILE_RESULT",
                        new JSONObject(result.toString())
                                .put("index", index)
                                .put("profile", profile)
                                .put("label", VulkanProbeStore.profileLabel(profile)));
                ensurePreviousProbeGone(context);
                sleep(1_000L);
            }
            VulkanAutoProbeStore.complete(context);
            AppLogger.event(context, "VULKAN_AUTO_PROBE_COMPLETED",
                    new JSONObject().put("profileCount", PROFILES.length));
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            VulkanAutoProbeStore.fail(context, message);
            try {
                AppLogger.event(context, "VULKAN_AUTO_PROBE_FAILED",
                        new JSONObject().put("error", message));
            } catch (Exception ignored) {}
        } finally {
            RUNNING.set(false);
            DriveLogSync.enqueueNow(context);
            DriveLogSync.syncDirectAsync(context);
        }
    }

    private static JSONObject waitForProfile(Context context, String profile,
                                             long requestedAtMs) throws Exception {
        boolean seenRunning = false;
        long deadline = requestedAtMs + PROFILE_TIMEOUT_MS;
        String lastPhase = "WAITING_FOR_PROCESS";
        VulkanAutoProbeStore.updatePhase(context, profile, lastPhase);

        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();
            JSONObject status = VulkanProbeStore.read(context);
            String currentProfile = status.optString("profile", "");
            String state = status.optString("state", "IDLE");
            String phase = status.optString("phase", "-");

            if (profile.equals(currentProfile)) {
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
            if (seenRunning && pid <= 0 && profile.equals(currentProfile)
                    && "RUNNING".equals(state)) {
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
                        .put("lastPhase", phase)
                        .put("probeStatus", new JSONObject(status.toString()));
                JSONObject exit = latestProbeExit(context, requestedAtMs);
                if (exit != null) result.put("exit", exit);
                return result;
            }
            sleep(POLL_MS);
        }

        int pid = probePid(context);
        if (pid > 0) {
            try { Process.killProcess(pid); } catch (Throwable ignored) {}
        }
        JSONObject result = new JSONObject()
                .put("outcome", "TIMEOUT")
                .put("lastPhase", lastPhase)
                .put("timeoutMs", PROFILE_TIMEOUT_MS);
        JSONObject exit = latestProbeExit(context, requestedAtMs);
        if (exit != null) result.put("exit", exit);
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
