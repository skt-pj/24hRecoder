package com.sktpj.recorder24h.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.sktpj.recorder24h.MainActivity;
import com.sktpj.recorder24h.R;
import com.sktpj.recorder24h.audio.AacSegmentRecorder;
import com.sktpj.recorder24h.storage.RecorderHealth;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.storage.StoragePolicy;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.io.File;

public final class RecorderService extends Service {
    public static final String ACTION_START = "com.sktpj.recorder24h.action.START";
    public static final String ACTION_STOP = "com.sktpj.recorder24h.action.STOP";

    private static final String CHANNEL_ID = "recording";
    private static final int NOTIFICATION_ID = 1001;

    private final Object lock = new Object();
    private Thread recorderThread;
    private AacSegmentRecorder recorder;
    private volatile String activeSegmentId;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            updateRecordingHealthNotification();
            watchdogHandler.postDelayed(this, 5_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        StoragePolicy.recoverOrphanParts(this);
        AppLogger.event(this, "RECORDER_SERVICE_CREATED");
        watchdogHandler.postDelayed(watchdog, 5_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            JSONObject d = new JSONObject();
            d.put("action", action == null ? JSONObject.NULL : action);
            d.put("flags", flags);
            d.put("startId", startId);
            d.put("requested", RecordingIntentStore.isRequested(this));
            AppLogger.event(this, "RECORDER_SERVICE_START_COMMAND", d);
        } catch (Exception ignored) {
        }

        if (ACTION_STOP.equals(action)) {
            RecordingIntentStore.setRequested(this, false);
            stopRecorderAndSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            RecordingIntentStore.setRequested(this, true);
        }

        if (!RecordingIntentStore.isRequested(this)) {
            RecorderStateStore.write(this, "STOPPED", null, null);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingIntentStore.setRequested(this, false);
            RecorderStateStore.write(this, "ERROR", null, "RECORD_AUDIO permission is not granted");
            AppLogger.event(this, "RECORD_AUDIO_PERMISSION_MISSING");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForegroundCompat(buildRecordingNotification("録音を開始しています"));
        } catch (RuntimeException e) {
            RecordingIntentStore.setRequested(this, false);
            RecorderStateStore.write(this, "ERROR", null, "Foreground service start failed: " + e.getMessage());
            AppLogger.event(this, "FOREGROUND_START_FAILED", errorJson(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        startRecorderIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        watchdogHandler.removeCallbacks(watchdog);
        AppLogger.event(this, "RECORDER_SERVICE_DESTROYED");
        stopRecorder(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startRecorderIfNeeded() {
        synchronized (lock) {
            if (recorderThread != null && recorderThread.isAlive()) {
                return;
            }

            RecorderStateStore.write(this, "STARTING", null, null);
            recorder = new AacSegmentRecorder(this, new AacSegmentRecorder.Listener() {
                @Override
                public void onSegmentChanged(String segmentId, File file) {
                    activeSegmentId = segmentId;
                    RecorderStateStore.write(RecorderService.this, "RECORDING", segmentId, null);
                    updateRecordingHealthNotification();
                }

                @Override
                public void onFatalError(String message, Throwable error) {
                    RecordingIntentStore.setRequested(RecorderService.this, false);
                    RecorderStateStore.write(RecorderService.this, "ERROR", activeSegmentId, message);
                    updateNotification("録音エラー");
                }
            });

            recorderThread = new Thread(() -> {
                try {
                    recorder.run();
                } finally {
                    boolean requested = RecordingIntentStore.isRequested(RecorderService.this);
                    if (!requested) {
                        RecorderStateStore.write(RecorderService.this, "STOPPED", null, null);
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                    synchronized (lock) {
                        recorderThread = null;
                        recorder = null;
                        activeSegmentId = null;
                    }
                }
            }, "aac-recorder");
            recorderThread.setPriority(Thread.MAX_PRIORITY);
            recorderThread.start();
        }
    }

    private void stopRecorderAndSelf() {
        stopRecorder(true);
        RecorderStateStore.write(this, "STOPPING", activeSegmentId, null);
    }

    private void stopRecorder(boolean userStop) {
        synchronized (lock) {
            if (userStop) {
                AppLogger.event(this, "RECORDER_STOP_REQUESTED");
            }
            if (recorder != null) {
                recorder.requestStop();
            } else if (!RecordingIntentStore.isRequested(this)) {
                RecorderStateStore.write(this, "STOPPED", null, null);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void startForegroundCompat(Notification notification) {
        startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    private void updateRecordingHealthNotification() {
        if (!RecordingIntentStore.isRequested(this)) {
            return;
        }
        JSONObject state = RecorderStateStore.read(this);
        RecorderHealth.Snapshot health = RecorderHealth.evaluate(
                state, true, System.currentTimeMillis());
        String text = health.label;
        if (health.healthy && activeSegmentId != null) {
            text += " • " + shortSegment(activeSegmentId);
        }
        updateNotification(text);
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildRecordingNotification(text));
        }
    }

    private Notification buildRecordingNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                10,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                11,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("24hRecoder")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止", stopPending).build())
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "録音中",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("24時間録音の実行状態");
        manager.createNotificationChannel(channel);
    }

    private static String shortSegment(String segmentId) {
        if (segmentId == null) {
            return "準備中";
        }
        return segmentId.length() <= 8 ? segmentId : segmentId.substring(0, 8);
    }

    private static JSONObject errorJson(Throwable t) {
        JSONObject d = new JSONObject();
        try {
            d.put("type", t.getClass().getName());
            d.put("message", t.getMessage() == null ? JSONObject.NULL : t.getMessage());
        } catch (Exception ignored) {
        }
        return d;
    }
}
