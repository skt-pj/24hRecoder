package com.sktpj.recorder24h;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import com.sktpj.recorder24h.util.DriveLogSync;
import com.sktpj.recorder24h.util.DriveLogTarget;

public final class RecorderApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final long SETUP_PROMPT_COOLDOWN_MS = 60L * 60L * 1000L;

    @Override
    public void onCreate() {
        super.onCreate();
        String processName = Application.getProcessName();
        if (!getPackageName().equals(processName)) {
            return;
        }
        DriveLogSync.ensureScheduled(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) {
            return;
        }

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

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
