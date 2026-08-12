package com.sktpj.recorder24h.transcription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import androidx.work.ForegroundInfo;

import com.sktpj.recorder24h.MainActivity;
import com.sktpj.recorder24h.R;

final class TranscriptionForegroundInfo {
    private static final String COMPARISON_CHANNEL = "model_comparison";
    private static final String DOWNLOAD_CHANNEL = "model_download";
    private static final String QUEUE_CHANNEL = "transcription_fifo_queue";
    private static final int COMPARISON_NOTIFICATION_BASE = 7300;
    private static final int DOWNLOAD_NOTIFICATION_BASE = 7400;
    private static final int QUEUE_NOTIFICATION_ID = 7501;

    private TranscriptionForegroundInfo() {
    }

    static ForegroundInfo comparison(Context context, String segmentId, String text) {
        ensureChannel(context, COMPARISON_CHANNEL, "モデル比較", "指定した録音を複数の音声認識モデルで比較します");
        int id = COMPARISON_NOTIFICATION_BASE + Math.floorMod(segmentId == null ? 0 : segmentId.hashCode(), 80);
        Notification notification = buildNotification(
                context,
                COMPARISON_CHANNEL,
                "24hRecoder モデル比較",
                text == null ? "音声認識モデルを比較しています" : text,
                id);
        return new ForegroundInfo(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    }

    static ForegroundInfo modelDownload(Context context, WhisperModelManager.ModelSpec spec) {
        ensureChannel(context, DOWNLOAD_CHANNEL, "比較モデル取得", "比較用の音声認識モデルを取得します");
        String modelId = spec == null ? "model" : spec.id;
        String label = spec == null ? "比較モデル" : spec.label;
        int id = DOWNLOAD_NOTIFICATION_BASE + Math.floorMod(modelId.hashCode(), 80);
        Notification notification = buildNotification(
                context,
                DOWNLOAD_CHANNEL,
                "24hRecoder モデル取得",
                label + " をダウンロードしています",
                id);
        return new ForegroundInfo(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    static ForegroundInfo queue(Context context, String text) {
        ensureChannel(context, QUEUE_CHANNEL, "文字起こしキュー", "録音日時の古い順にローカル文字起こしを処理します");
        Notification notification = buildNotification(
                context,
                QUEUE_CHANNEL,
                "24hRecoder 文字起こし",
                text == null ? "文字起こしキューを処理しています" : text,
                QUEUE_NOTIFICATION_ID);
        return new ForegroundInfo(
                QUEUE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    }

    private static Notification buildNotification(Context context, String channelId,
                                                  String title, String text, int requestCode) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                requestCode,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
    }

    private static void ensureChannel(Context context, String id, String name, String description) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(description);
        manager.createNotificationChannel(channel);
    }
}
