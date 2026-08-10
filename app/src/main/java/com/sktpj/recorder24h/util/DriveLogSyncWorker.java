package com.sktpj.recorder24h.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class DriveLogSyncWorker extends Worker {
    public DriveLogSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!DriveLogTarget.isConfigured(context)) {
            return Result.success();
        }
        try {
            DriveLogSync.sync(context);
            return Result.success();
        } catch (SecurityException permissionLost) {
            DriveLogTarget.recordError(context, DriveLogSync.shortError(permissionLost));
            DriveLogTarget.clearTreeUri(context);
            AppLogger.event(context, "DRIVE_LOG_SYNC_PERMISSION_LOST");
            return Result.success();
        } catch (Exception error) {
            DriveLogTarget.recordError(context, DriveLogSync.shortError(error));
            AppLogger.event(context, "DRIVE_LOG_SYNC_FAILED");
            return Result.retry();
        }
    }
}
