package com.sktpj.recorder24h.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sktpj.recorder24h.MainActivity;
import com.sktpj.recorder24h.R;
import com.sktpj.recorder24h.memoket.GemStateRecoveryPolicy;
import com.sktpj.recorder24h.memoket.MemoketSettings;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.transcription.WhisperModelManager;
import com.sktpj.recorder24h.util.AppLogger;

public final class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "resume_recording";
    private static final int NOTIFICATION_ID = 2001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        // If the selected ASR model is already present but the shared Silero VAD asset is
        // missing after an app update, restore the VAD asset and then re-enqueue retained audio.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && WhisperModelManager.isAsrReady(context)
                && !WhisperModelManager.isReady(context)) {
            WhisperModelManager.enqueueDownload(context);
            AppLogger.event(context, "PACKAGE_REPLACED_VAD_ASSET_REPAIR_QUEUED");
        }

        final boolean wasRequested = RecordingIntentStore.isRequested(context);
        if (!wasRequested) return;

        // App upgrade / reboot terminates the prior process and its BLE session.
        // Do not report its persisted STOPPING or TRANSFERRING as a live operation.
        // Leave Gem-side files untouched; physical record/stop remains unknown.
        String oldState = RecorderStateStore.read(context).optString("state", "STOPPED");
        if (GemStateRecoveryPolicy.interruptedByProcessReplacement(
                MemoketSettings.source(context), wasRequested, oldState)) {
            String reason = "アプリ更新・端末再起動で前回のGem操作が中断されました。"
                    + "Gem本体の録音状態と未取得ファイルは未確認です。";
            RecordingIntentStore.setRequested(context, false);
            RecorderStateStore.write(context, "ERROR", null, reason);
            MemoketSettings.setRemoteRecordingState(context, "操作中断・要確認");
            MemoketSettings.saveResult(context, reason);
            AppLogger.event(context, "GEM_INTERRUPTED_STATE_RECOVERED_AFTER_RESTART");
        }

        AppLogger.event(context, "BOOT_OR_PACKAGE_REPLACED_RECORDING_RESTART_REQUIRED");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "録音再開",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("端末再起動・アプリ更新後の録音再開通知");
        manager.createNotificationChannel(channel);

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                20,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("24hRecoder")
                .setContentText("録音を再開するにはアプリを開いて確認してください。")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
