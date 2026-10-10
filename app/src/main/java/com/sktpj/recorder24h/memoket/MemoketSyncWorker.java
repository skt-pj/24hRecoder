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
        boolean afterStop = getInputData().getBoolean("afterStop", false);
        if (!manual && !MemoketSettings.enabled(context)) return Result.success();
        String gemRecordingState = MemoketSettings.remoteRecordingState(context);
        if ("録音中".equals(gemRecordingState) || "接続中".equals(gemRecordingState)
                || "停止処理中".equals(gemRecordingState)
                || "Gem本体停止待ち".equals(gemRecordingState) && !afterStop
                || "停止操作済・取得待ち".equals(gemRecordingState) && !afterStop
                || "ファイル取得中".equals(gemRecordingState)) {
            MemoketSettings.saveResult(context, "録音操作中は別のGem同期を実行しません");
            return manual ? Result.failure() : Result.retry();
        }
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
        MemoketGattSync sync = null;
        try {
            MemoketRecordingWindow window = afterStop
                    ? new MemoketRecordingWindow(
                            getInputData().getLong("startedAtMs", 0L),
                            getInputData().getLong("stoppedAtMs", 0L),
                            getInputData().getString("recordingZone"))
                    : null;
            if (afterStop) MemoketSettings.setRemoteRecordingState(context, "ファイル取得中");
            sync = new MemoketGattSync(context, address, window);
            AppLogger.diagnostic(context, "MEMOKET_SYNC_WORKER_STARTED",
                    new JSONObject()
                            .put("sessionId", sync.sessionId())
                            .put("manual", manual)
                            .put("afterStop", afterStop)
                            .put("onlyCurrentRecording", window != null)
                            .put("attempt", getRunAttemptCount())
                            .put("workId", getId().toString()));
            int files = sync.sync();
            if (afterStop && files != 1) throw new IllegalStateException("今回の録音ファイルを1件取得できませんでした");
            if (afterStop) MemoketSettings.setRemoteRecordingState(context, "停止");
            String message = afterStop
                    ? (files == 0 ? "今回の録音ファイルはGem内で見つかりませんでした" : "今回の録音を" + files + "件取得しました")
                    : files + "件の録音を取得しました";
            MemoketSettings.saveResult(context, message);
            JSONObject details = new JSONObject()
                    .put("fileCount", files)
                    .put("sessionId", sync.sessionId())
                    .put("manual", manual)
                    .put("attempt", getRunAttemptCount())
                    .put("workId", getId().toString());
            AppLogger.event(context, "MEMOKET_SYNC_COMPLETED", details);
            return Result.success();
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (afterStop) MemoketSettings.setRemoteRecordingState(context, "エラー");
            MemoketSettings.saveResult(context,
                    afterStop ? "今回の録音を取得できません: " + message : "同期失敗: " + message);
            try {
                JSONObject failed = new JSONObject()
                        .put("error", message)
                        .put("attempt", getRunAttemptCount())
                        .put("manual", manual)
                        .put("workId", getId().toString());
                if (sync != null) failed.put("sessionId", sync.sessionId());
                AppLogger.event(context, "MEMOKET_SYNC_FAILED", failed);
            } catch (Exception ignored) { }
            // Old recordings are never acknowledged or deleted just to get to the new one.
            if (afterStop || manual) return Result.failure();
            return Result.retry();
        }
    }
}
