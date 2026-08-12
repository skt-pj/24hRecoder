package com.sktpj.recorder24h.transcription;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.sktpj.recorder24h.MainActivity;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Visible foreground host for user-triggered queue draining.
 *
 * Actual selection and transcription are delegated to TranscriptionQueueRunner, the same FIFO
 * runner used by automatic WorkManager dispatch. Therefore visible/manual requests cannot create
 * a second ordering policy or race another queue for the Whisper slot.
 */
public final class TranscriptionQueueService extends Service {
    private static final String ACTION_DRAIN = "com.sktpj.recorder24h.action.DRAIN_TRANSCRIPTION_QUEUE";
    private static final String CHANNEL_ID = "24hrecoder-transcription-queue";
    private static final int NOTIFICATION_ID = 24013;
    private static final long MAX_SERVICE_RUNTIME_MS = 5L * 60L * 60L * 1000L + 20L * 60L * 1000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private volatile boolean destroyed;

    public static boolean kick(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, TranscriptionQueueService.class).setAction(ACTION_DRAIN);
        try {
            app.startForegroundService(intent);
            AppLogger.event(app, "TRANSCRIPTION_FIFO_SERVICE_REQUESTED");
            return true;
        } catch (RuntimeException error) {
            try {
                JSONObject details = new JSONObject();
                details.put("error", error.getClass().getSimpleName());
                details.put("message", safeMessage(error));
                AppLogger.event(app, "TRANSCRIPTION_FIFO_SERVICE_START_FAILED", details);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        promote("文字起こしキューを開始しています");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote("録音日時の古い順に文字起こししています");
        if (draining.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    TranscriptionQueueRunner.drain(
                            getApplicationContext(),
                            MAX_SERVICE_RUNTIME_MS,
                            new TranscriptionQueueRunner.Listener() {
                                @Override
                                public boolean isStopped() {
                                    return destroyed || Thread.currentThread().isInterrupted();
                                }

                                @Override
                                public void onStatus(String text) {
                                    promote(text);
                                }
                            });
                } finally {
                    draining.set(false);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "文字起こしキュー",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("録音日時の古い順にローカル文字起こしを処理します");
        manager.createNotificationChannel(channel);
    }

    private void promote(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("24hRecoder")
                .setContentText(text == null ? "文字起こしキューを処理中" : text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        AppLogger.event(getApplicationContext(), "TRANSCRIPTION_FIFO_SERVICE_TIMEOUT");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return "";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
