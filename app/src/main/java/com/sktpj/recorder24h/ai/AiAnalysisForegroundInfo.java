package com.sktpj.recorder24h.ai;

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

final class AiAnalysisForegroundInfo {
    private static final String CHANNEL_ID = "local_ai_analysis";
    private static final int NOTIFICATION_BASE = 7600;

    private AiAnalysisForegroundInfo() {
    }

    static ForegroundInfo localGemma(
            Context context,
            String kind,
            long periodStartMs,
            String text) {
        ensureChannel(context);
        int hash = (kind == null ? 0 : kind.hashCode());
        hash = 31 * hash + Long.hashCode(periodStartMs);
        int id = NOTIFICATION_BASE + Math.floorMod(hash, 200);

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                id,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("24hRecoder ローカルAI分析")
                .setContentText(text == null ? "Gemmaで会話ログを分析しています" : text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
        return new ForegroundInfo(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    }

    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ローカルAI分析",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("端末内Gemmaによる長時間の会話ログ分析を実行します");
        manager.createNotificationChannel(channel);
    }
}
