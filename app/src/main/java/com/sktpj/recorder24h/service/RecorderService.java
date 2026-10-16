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
    private static final long RECOVERY_STOP_TIMEOUT_MS = 10_000L;
    private static final long AUTO_RECOVERY_BURST_WINDOW_MS = 60_000L;
    private static final int MAX_AUTO_RECOVERY_ATTEMPTS_PER_WINDOW = 3;

    private final Object lock = new Object();
    private Thread recorderThread;
    private AacSegmentRecorder recorder;
    private volatile String activeSegmentId;
    private volatile boolean recoveryInProgress;
    private volatile long recoveryStartedAtMs;
    private volatile String pendingRecoveryReason;
    private volatile boolean serviceDestroyed;
    private long autoRecoveryWindowStartedAtMs;
    private int autoRecoveryAttempts;
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
            resetAutomaticRecoveryWindow();
        }

        if (!RecordingIntentStore.isRequested(this)) {
            RecorderStateStore.write(this, "STOPPED", null, null);
            stopSelf();
            return START_NOT_STICKY;
        }

        String blocker = currentBlockingReason(null);
        if (blocker != null) {
            failPermanently(blocker, null);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForegroundCompat(buildRecordingNotification("録音を開始しています"));
        } catch (RuntimeException e) {
            failPermanently("Foreground serviceを開始できません: " + messageOrType(e), e);
            stopSelf();
            return START_NOT_STICKY;
        }

        startRecorderIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
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
        if (!RecordingIntentStore.isRequested(this) || serviceDestroyed) {
            return;
        }

        String blocker = currentBlockingReason(null);
        if (blocker != null) {
            failPermanently(blocker, null);
            return;
        }

        synchronized (lock) {
            if (recorderThread != null && recorderThread.isAlive()) {
                return;
            }

            recoveryInProgress = false;
            recoveryStartedAtMs = 0L;
            RecorderStateStore.write(this, "STARTING", null, null);
            recorder = new AacSegmentRecorder(this, new AacSegmentRecorder.Listener() {
                @Override
                public void onSegmentChanged(String segmentId, File file) {
                    activeSegmentId = segmentId;
                    RecorderStateStore.write(RecorderService.this, "RECORDING", segmentId, null);
                    String recoveredFrom = pendingRecoveryReason;
                    if (recoveredFrom != null) {
                        pendingRecoveryReason = null;
                        try {
                            JSONObject d = new JSONObject();
                            d.put("segmentId", segmentId);
                            d.put("reason", recoveredFrom);
                            AppLogger.event(RecorderService.this, "RECORDER_AUTO_RECOVERY_SUCCEEDED", d);
                        } catch (Exception ignored) {
                        }
                    }
                    updateRecordingHealthNotification();
                }

                @Override
                public void onFatalError(String message, Throwable error) {
                    String blocker = currentBlockingReason(error);
                    if (blocker != null) {
                        failPermanently(blocker, error);
                        return;
                    }

                    String reason = "録音処理エラー: " + (message == null || message.trim().isEmpty()
                            ? messageOrType(error) : message);
                    if (!registerAutomaticRecoveryAttempt(reason)) {
                        return;
                    }
                    recoveryInProgress = true;
                    recoveryStartedAtMs = System.currentTimeMillis();
                    pendingRecoveryReason = reason;
                    RecorderStateStore.write(RecorderService.this, "RECOVERING", activeSegmentId, reason);
                    try {
                        JSONObject d = errorJson(error);
                        d.put("reason", reason);
                        AppLogger.event(RecorderService.this, "RECORDER_FATAL_AUTO_RETRY", d);
                    } catch (Exception ignored) {
                    }
                    updateNotification("録音エラーを検知。自動再試行します");
                }
            });

            recorderThread = new Thread(() -> {
                try {
                    recorder.run();
                } finally {
                    boolean requested = RecordingIntentStore.isRequested(RecorderService.this);
                    synchronized (lock) {
                        recorderThread = null;
                        recorder = null;
                        activeSegmentId = null;
                    }

                    if (requested && !serviceDestroyed) {
                        recoveryInProgress = false;
                        recoveryStartedAtMs = 0L;
                        if (pendingRecoveryReason == null) {
                            pendingRecoveryReason = "録音処理が終了したため自動再開します";
                        }
                        RecorderStateStore.write(
                                RecorderService.this,
                                "RECOVERING",
                                null,
                                pendingRecoveryReason);
                        try {
                            JSONObject d = new JSONObject();
                            d.put("reason", pendingRecoveryReason);
                            AppLogger.event(RecorderService.this, "RECORDER_AUTO_RESTART_QUEUED", d);
                        } catch (Exception ignored) {
                        }
                        watchdogHandler.post(RecorderService.this::startRecorderIfNeeded);
                        return;
                    }

                    if (!requested) {
                        JSONObject state = RecorderStateStore.read(RecorderService.this);
                        if (!"ERROR".equals(state.optString("state", "STOPPED"))) {
                            RecorderStateStore.write(RecorderService.this, "STOPPED", null, null);
                        }
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
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
                JSONObject state = RecorderStateStore.read(this);
                if (!"ERROR".equals(state.optString("state", "STOPPED"))) {
                    RecorderStateStore.write(this, "STOPPED", null, null);
                }
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    private void requestAutomaticRecovery(RecorderHealth.Snapshot health) {
        String blocker = currentBlockingReason(null);
        if (blocker != null) {
            failPermanently(blocker, null);
            return;
        }

        AacSegmentRecorder recorderToStop;
        boolean startWithoutExistingThread;
        String reason = health.code + ": " + health.detail;
        if (!registerAutomaticRecoveryAttempt(reason)) {
            return;
        }
        synchronized (lock) {
            if (recoveryInProgress) {
                return;
            }
            recoveryInProgress = true;
            recoveryStartedAtMs = System.currentTimeMillis();
            pendingRecoveryReason = reason;
            RecorderStateStore.write(this, "RECOVERING", activeSegmentId, reason);
            recorderToStop = recorder;
            startWithoutExistingThread = recorderThread == null || !recorderThread.isAlive();
        }

        try {
            JSONObject d = new JSONObject();
            d.put("healthCode", health.code);
            d.put("healthDetail", health.detail);
            d.put("segmentId", activeSegmentId == null ? JSONObject.NULL : activeSegmentId);
            AppLogger.event(this, "RECORDER_AUTO_RECOVERY_TRIGGERED", d);
        } catch (Exception ignored) {
        }
        updateNotification("録音異常を検知。自動再試行します");

        if (startWithoutExistingThread) {
            recoveryInProgress = false;
            recoveryStartedAtMs = 0L;
            watchdogHandler.post(this::startRecorderIfNeeded);
        } else if (recorderToStop != null) {
            recorderToStop.requestStop();
        }
    }

    private boolean registerAutomaticRecoveryAttempt(String reason) {
        long now = System.currentTimeMillis();
        int attempts;
        synchronized (lock) {
            if (autoRecoveryWindowStartedAtMs <= 0L
                    || now - autoRecoveryWindowStartedAtMs > AUTO_RECOVERY_BURST_WINDOW_MS) {
                autoRecoveryWindowStartedAtMs = now;
                autoRecoveryAttempts = 0;
            }
            autoRecoveryAttempts++;
            attempts = autoRecoveryAttempts;
        }
        if (attempts <= MAX_AUTO_RECOVERY_ATTEMPTS_PER_WINDOW) {
            return true;
        }

        failPermanently(
                "短時間に録音異常が繰り返されたため自動再試行を停止しました。最終理由: " + reason,
                null);
        return false;
    }

    private void resetAutomaticRecoveryWindow() {
        synchronized (lock) {
            autoRecoveryWindowStartedAtMs = 0L;
            autoRecoveryAttempts = 0;
        }
    }

    private void failPermanently(String reason, Throwable error) {
        RecordingIntentStore.setRequested(this, false);
        recoveryInProgress = false;
        recoveryStartedAtMs = 0L;
        pendingRecoveryReason = null;
        RecorderStateStore.write(this, "ERROR", activeSegmentId, reason);
        try {
            JSONObject d = error == null ? new JSONObject() : errorJson(error);
            d.put("reason", reason);
            AppLogger.event(this, "RECORDER_AUTO_RECOVERY_BLOCKED", d);
        } catch (Exception ignored) {
        }
        updateNotification("録音を再開できません: " + reason);

        AacSegmentRecorder current;
        synchronized (lock) {
            current = recorder;
        }
        if (current != null) {
            current.requestStop();
        }
    }

    private String currentBlockingReason(Throwable error) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "マイク権限がありません。Androidのアプリ設定でマイク権限を許可してください。";
        }
        if (error instanceof SecurityException) {
            return "Androidの権限制約により録音を開始できません: " + messageOrType(error);
        }

        long usable = getFilesDir().getUsableSpace();
        if (usable < StoragePolicy.EMERGENCY_RESERVE_BYTES) {
            try {
                StoragePolicy.enforce(this);
            } catch (RuntimeException ignored) {
            }
            usable = getFilesDir().getUsableSpace();
            if (usable < StoragePolicy.EMERGENCY_RESERVE_BYTES) {
                return "端末の空き容量が不足しています。空き容量: "
                        + (usable / (1024L * 1024L)) + " MB";
            }
        }
        return null;
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

        long now = System.currentTimeMillis();
        if (recoveryInProgress && recoveryStartedAtMs > 0L
                && now - recoveryStartedAtMs > RECOVERY_STOP_TIMEOUT_MS) {
            Thread thread;
            synchronized (lock) {
                thread = recorderThread;
            }
            if (thread != null && thread.isAlive()) {
                failPermanently(
                        "旧録音処理を停止できないため自動再試行を開始できません。アプリを再起動してください。",
                        null);
                return;
            }
        }

        JSONObject state = RecorderStateStore.read(this);
        RecorderHealth.Snapshot health = RecorderHealth.evaluate(state, true, now);
        if (health.problem) {
            requestAutomaticRecovery(health);
            state = RecorderStateStore.read(this);
            health = RecorderHealth.evaluate(state, RecordingIntentStore.isRequested(this), System.currentTimeMillis());
        }

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

    private static String messageOrType(Throwable t) {
        if (t == null) {
            return "原因不明";
        }
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static JSONObject errorJson(Throwable t) {
        JSONObject d = new JSONObject();
        try {
            if (t == null) {
                d.put("type", JSONObject.NULL);
                d.put("message", JSONObject.NULL);
            } else {
                d.put("type", t.getClass().getName());
                d.put("message", t.getMessage() == null ? JSONObject.NULL : t.getMessage());
            }
        } catch (Exception ignored) {
        }
        return d;
    }
}
