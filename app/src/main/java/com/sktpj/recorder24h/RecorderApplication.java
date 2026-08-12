package com.sktpj.recorder24h;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.sktpj.recorder24h.service.RecorderService;
import com.sktpj.recorder24h.storage.RecorderHealth;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.transcription.TranscriptionScheduler;
import com.sktpj.recorder24h.util.AppLogger;
import com.sktpj.recorder24h.util.DriveLogSync;
import com.sktpj.recorder24h.util.DriveLogTarget;

import org.json.JSONObject;

public final class RecorderApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final long SETUP_PROMPT_COOLDOWN_MS = 60L * 60L * 1000L;
    private static final long FOREGROUND_RECORDER_CHECK_MS = 5_000L;
    private static final long FOREGROUND_RECOVERY_REQUEST_COOLDOWN_MS = 15_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Activity resumedMainActivity;
    private long lastForegroundRecoveryRequestAtMs;

    private final Runnable foregroundRecorderCheck = new Runnable() {
        @Override
        public void run() {
            Activity activity = resumedMainActivity;
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            recoverRequestedRecordingIfNeeded(activity);
            mainHandler.postDelayed(this, FOREGROUND_RECORDER_CHECK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        String processName = Application.getProcessName();
        if (!getPackageName().equals(processName)) {
            return;
        }
        DriveLogSync.ensureScheduled(this);
        TranscriptionScheduler.recoverInterruptedAndEnsureDrainAsync(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) {
            return;
        }

        resumedMainActivity = activity;
        mainHandler.removeCallbacks(foregroundRecorderCheck);
        recoverRequestedRecordingIfNeeded(activity);
        mainHandler.postDelayed(foregroundRecorderCheck, FOREGROUND_RECORDER_CHECK_MS);

        if (DriveLogTarget.isConfigured(this)) {
            DriveLogSync.syncDirectAsync(this);
            return;
        }

        long now = System.currentTimeMillis();
        long lastPrompt = DriveLogTarget.getLastPromptAtMs(this);
        if (now - lastPrompt < SETUP_PROMPT_COOLDOWN_MS) {
            return;
        }
        DriveLogTarget.markPrompted(this, now);
        activity.startActivity(new Intent(activity, DriveLogSetupActivity.class));
    }

    private void recoverRequestedRecordingIfNeeded(Activity activity) {
        if (!RecordingIntentStore.isRequested(this)) {
            lastForegroundRecoveryRequestAtMs = 0L;
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        long now = System.currentTimeMillis();
        JSONObject state = RecorderStateStore.read(this);
        RecorderHealth.Snapshot health = RecorderHealth.evaluate(state, true, now);
        if (!health.problem) {
            lastForegroundRecoveryRequestAtMs = 0L;
            return;
        }
        if (lastForegroundRecoveryRequestAtMs > 0L
                && now - lastForegroundRecoveryRequestAtMs < FOREGROUND_RECOVERY_REQUEST_COOLDOWN_MS) {
            return;
        }
        lastForegroundRecoveryRequestAtMs = now;

        try {
            JSONObject details = new JSONObject();
            details.put("healthCode", health.code);
            details.put("healthDetail", health.detail);
            details.put("state", state.optString("state", ""));
            details.put("segmentId", state.optString("segmentId", ""));
            details.put("heartbeatMs", state.optLong("heartbeatMs", 0L));
            details.put("lastAudioReadMs", state.optLong("lastAudioReadMs", 0L));
            AppLogger.event(this, "FOREGROUND_RECORDER_RECOVERY_REQUESTED", details);
        } catch (Exception ignored) {
        }

        try {
            activity.startForegroundService(
                    new Intent(activity, RecorderService.class).setAction(RecorderService.ACTION_START));
        } catch (RuntimeException error) {
            try {
                JSONObject details = new JSONObject();
                details.put("type", error.getClass().getName());
                details.put("message", error.getMessage() == null ? JSONObject.NULL : error.getMessage());
                details.put("healthCode", health.code);
                AppLogger.event(this, "FOREGROUND_RECORDER_RECOVERY_START_FAILED", details);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (activity == resumedMainActivity) {
            resumedMainActivity = null;
            mainHandler.removeCallbacks(foregroundRecorderCheck);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity == resumedMainActivity) {
            resumedMainActivity = null;
            mainHandler.removeCallbacks(foregroundRecorderCheck);
        }
    }
}
