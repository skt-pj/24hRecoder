package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.sktpj.recorder24h.util.AppLogger;
import org.json.JSONObject;

public final class MemoketSyncWorker extends Worker {
    public MemoketSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean manual = getInputData().getBoolean("manual", false);
        if (!manual && !MemoketSettings.enabled(context)) return Result.success();
        String address = MemoketSettings.address(context);
        if (address.isEmpty()) {
            MemoketSettings.saveResult(context, "Gemが選択されていません");
            return Result.failure();
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            MemoketSettings.saveResult(context, "Bluetooth接続権限がありません");
            return Result.failure();
        }
        try {
            int files = new MemoketGattSync(context, address).sync();
            String message = files + "件の録音を取得しました";
            MemoketSettings.saveResult(context, message);
            JSONObject details = new JSONObject().put("fileCount", files);
            AppLogger.event(context, "MEMOKET_SYNC_COMPLETED", details);
            return Result.success();
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            MemoketSettings.saveResult(context, "同期失敗: " + message);
            try {
                AppLogger.event(context, "MEMOKET_SYNC_FAILED",
                        new JSONObject().put("error", message).put("attempt", getRunAttemptCount()));
            } catch (Exception ignored) { }
            return manual ? Result.failure() : Result.retry();
        }
    }
}
