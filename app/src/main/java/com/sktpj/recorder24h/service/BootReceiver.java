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
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.util.AppLogger;

public final class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "resume_recording";
    private static final int NOTIFICATION_ID = 2001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RecordingIntentStore.isRequested(context)) {
            return;
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
        channel.setDescription("端末再起動後の録音再開通知");
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
                .setContentText("端末再起動後は録音が停止しています。アプリを開いて再開してください。")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
