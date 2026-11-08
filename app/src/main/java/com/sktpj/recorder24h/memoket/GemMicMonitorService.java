package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.sktpj.recorder24h.R;
import com.sktpj.recorder24h.storage.RecorderStateStore;
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.transcription.StreamingVadStore;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

public final class GemMicMonitorService extends Service {
    public static final String ACTION_START = "com.sktpj.recorder24h.memoket.MIC_START";
    public static final String ACTION_STOP = "com.sktpj.recorder24h.memoket.MIC_STOP";
    public static final String ACTION_GEM_STARTED = "com.sktpj.recorder24h.memoket.GEM_STARTED";
    public static final String ACTION_GEM_STOPPING = "com.sktpj.recorder24h.memoket.GEM_STOPPING";
    public static final String ACTION_GEM_FETCHING = "com.sktpj.recorder24h.memoket.GEM_FETCHING";
    public static final String ACTION_GEM_FINISHED = "com.sktpj.recorder24h.memoket.GEM_FINISHED";
    public static final String ACTION_GEM_FAILED = "com.sktpj.recorder24h.memoket.GEM_FAILED";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_MIC_GATED = "mic_gated";

    private static final String CHANNEL = "gem_mic_monitor";
    private static final int NOTIFICATION_ID = 3402;
    private static final int RATE = 16_000;
    private final GemVoiceGate gate = new GemVoiceGate();
    private volatile boolean listening;
    private volatile AudioRecord microphone;
    private Thread captureThread;
    private long lastReadAtMs;
    private long lastHeartbeatAtMs;
    private long nextStartAllowedAtMs;
    private String publishedState = "";

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Gem音声監視", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action) || (action == null && RecordingIntentStore.isRequested(this))) {
            if (!listening && RecordingIntentStore.isRequested(this)) startListening();
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            gate.stopRequested();
            stopMicrophone();
            progress(System.currentTimeMillis());
            return START_NOT_STICKY;
        }
        if (!listening && !RecordingIntentStore.isRequested(this)
                && !gate.isStarting() && !gate.isRecording() && !gate.isFetching()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_GEM_STARTED.equals(action)) {
            gate.started();
            event("GEM_MIC_DEVICE_STARTED");
        } else if (ACTION_GEM_STOPPING.equals(action)) {
            gate.stopSent();
            event("GEM_MIC_DEVICE_STOP_REQUESTED");
        } else if (ACTION_GEM_FETCHING.equals(action)) {
            gate.fetchStarted();
            event("GEM_MIC_DEVICE_FILE_FETCHING");
        } else if (ACTION_GEM_FINISHED.equals(action)) {
            gate.acquired();
            nextStartAllowedAtMs = System.currentTimeMillis() + 1_000L;
            event("GEM_MIC_FILE_ACQUIRED");
        } else if (ACTION_GEM_FAILED.equals(action)) {
            String error = intent == null ? null : intent.getStringExtra(EXTRA_ERROR);
            fail(error == null ? "Gem録音または音声取得に失敗しました" : error);
            return START_NOT_STICKY;
        }
        progress(System.currentTimeMillis());
        return START_STICKY;
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("スマホマイクへのアクセスが許可されていません");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException error) {
            fail("スマホマイクの監視を開始できません: " + error.getMessage());
            return;
        }
        RecorderStateStore.setInputSource(this, "MEMOKET");
        listening = true;
        publishState("STARTING");
        captureThread = new Thread(this::capture, "gem-mic-vad");
        captureThread.start();
    }

    private void capture() {
        AudioRecord record = null;
        try {
            if (!StreamingVadStore.startGemMonitor(this))
                throw new IllegalStateException("音声判定モデルを利用できません");
            int minBuffer = AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IllegalStateException("マイクバッファを確保できません");
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, 4096));
            if (record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("スマホマイクを初期化できません");
            AudioManager manager = getSystemService(AudioManager.class);
            if (manager != null) {
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        record.setPreferredDevice(device);
                        break;
                    }
                }
            }
            microphone = record;
            record.startRecording();
            verifyPhoneMicrophone(record);
            byte[] buffer = new byte[3200];
            long samplesRead = 0L;
            while (listening && RecordingIntentStore.isRequested(this)) {
                int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (!listening) break;
                if (count < 0) throw new IllegalStateException("スマホマイクの読み取りエラー: " + count);
                if (count == 0) continue;
                long now = System.currentTimeMillis();
                boolean speech = StreamingVadStore.observeGemPcm16(buffer, count,
                        samplesRead * 1_000_000L / RATE);
                samplesRead += count / 2L;
                synchronized (this) {
                    lastReadAtMs = now;
                    if (speech) gate.heardSpeech(now);
                    progress(now);
                    if (now - lastHeartbeatAtMs >= 5_000L) {
                        verifyPhoneMicrophone(record);
                        RecorderStateStore.heartbeat(this, stateForGate(), null, 0L, now);
                        lastHeartbeatAtMs = now;
                    }
                }
            }
        } catch (Exception error) {
            if (listening && RecordingIntentStore.isRequested(this))
                fail("スマホマイクの音声判定に失敗しました: " + error.getMessage());
        } finally {
            listening = false;
            microphone = null;
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                record.release();
            }
            StreamingVadStore.stopGemMonitor();
        }
    }

    private static void verifyPhoneMicrophone(AudioRecord record) {
        AudioDeviceInfo route = record.getRoutedDevice();
        if (route == null || route.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC)
            throw new IllegalStateException("録音入力がスマホ内蔵マイクではありません");
    }

    private void progress(long nowMs) {
        if (nowMs >= nextStartAllowedAtMs && gate.shouldStart(nowMs)) {
            gate.startSent();
            publishState("STARTING");
            Intent intent = new Intent(this, MemoketRemoteRecordingService.class)
                    .setAction(MemoketRemoteRecordingService.ACTION_START_RECORDING)
                    .putExtra(EXTRA_MIC_GATED, true);
            try {
                startForegroundService(intent);
                event("GEM_MIC_SPEECH_START");
            } catch (RuntimeException error) {
                fail("Gemの録音開始要求に失敗しました: " + error.getMessage());
                return;
            }
        }
        if (gate.shouldStop(nowMs)) {
            gate.stopSent();
            publishState("STOPPING");
            try {
                startService(new Intent(this, MemoketRemoteRecordingService.class)
                        .setAction(MemoketRemoteRecordingService.ACTION_STOP_RECORDING));
                event("GEM_MIC_SILENCE_STOP");
            } catch (RuntimeException error) {
                fail("Gemの録音停止要求に失敗しました: " + error.getMessage());
                return;
            }
        }
        publishState(stateForGate());
        if (gate.canExit()) {
            publishState("STOPPED");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private String stateForGate() {
        if (gate.isFetching()) return "TRANSFERRING";
        if (gate.isStopping()) return "STOPPING";
        if (gate.isRecording()) return "RECORDING";
        if (gate.isStarting()) return "STARTING";
        return "MONITORING";
    }

    private void publishState(String state) {
        if (state.equals(publishedState)) return;
        RecorderStateStore.write(this, state, null, null);
        publishedState = state;
    }

    private void stopMicrophone() {
        listening = false;
        AudioRecord record = microphone;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
        }
    }

    private synchronized void fail(String error) {
        if (gate.isRecording() && !gate.isFetching()) {
            gate.stopSent();
            try {
                startService(new Intent(this, MemoketRemoteRecordingService.class)
                        .setAction(MemoketRemoteRecordingService.ACTION_STOP_RECORDING));
            } catch (RuntimeException ignored) {}
        }
        gate.stopRequested();
        stopMicrophone();
        RecordingIntentStore.setRequested(this, false);
        RecorderStateStore.write(this, "ERROR", null, error);
        event("GEM_MIC_MONITOR_ERROR");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void event(String name) {
        try {
            AppLogger.event(this, name, new JSONObject()
                    .put("recording", gate.isRecording())
                    .put("fetching", gate.isFetching())
                    .put("lastMicReadAtMs", lastReadAtMs));
        } catch (Exception ignored) {}
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("24hRecoder")
                .setContentText("スマホマイクで音声を監視中")
                .setOngoing(true)
                .build();
    }

    @Override
    public synchronized void onDestroy() {
        stopMicrophone();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
