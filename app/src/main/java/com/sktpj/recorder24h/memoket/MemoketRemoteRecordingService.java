package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.sktpj.recorder24h.R;
import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;

public final class MemoketRemoteRecordingService extends Service {
    public static final String ACTION_START_RECORDING = "com.sktpj.recorder24h.memoket.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.sktpj.recorder24h.memoket.STOP_RECORDING";

    private static final String CHANNEL_ID = "memoket_remote_recording";
    private static final int NOTIFICATION_ID = 3401;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long DATA_QUIET_MS = 250;
    private static final long STOP_TRANSFER_IDLE_MS = 30_000;

    private final Deque<byte[]> commands = new ArrayDeque<>();
    private final MemoketSessionProtocol session = new MemoketSessionProtocol();
    private MemoketDebugTrace trace;
    private MemoketTransfer transfer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopTransferTimeout = () -> {
        if (stopPending && !stopCompleted) {
            finishWithError("今回の録音ファイルの転送応答が30秒以上ありません。Gem上のファイルは削除していません");
        }
    };
    private final Runnable metadataProbe = () -> {
        if (transfer != null && transfer.shouldRequestMetadata()) queue(MemoketTransfer.metadataCommand());
    };
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic data;
    private BluetoothGattCharacteristic control;
    private BluetoothGattCharacteristic response;
    private boolean commandBusy;
    private int notifySetupStep;
    private boolean recordingStartPending;
    private boolean recordingStartAwaitingAck;
    private boolean recordingActive;
    private boolean stopPending;
    private boolean stopListRequested;
    private long stopRequestedAtMs;
    private volatile boolean stopCompleted;
    private final MemoketStopProtocol stopProtocol = new MemoketStopProtocol();
    private int stopNotifyStep;
    private byte[] lastCommand;

    @Override
    public void onCreate() {
        super.onCreate();
        trace = new MemoketDebugTrace(this, "REMOTE_RECORDING");
        trace.phase("SERVICE_CREATED");
        createChannel();
        // No file transfer during recording. A single, session-scoped transfer
        // is created only after the user presses Stop.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_RECORDING.equals(action)) {
            if (stopPending || stopCompleted) return START_NOT_STICKY;
            trace.phase("STOP_REQUESTED", stateDetails());
            if (!recordingActive || gatt == null || data == null) {
                finishWithError("Gem録音中の接続がありません");
                return START_NOT_STICKY;
            }
            stopRequestedAtMs = System.currentTimeMillis();
            stopPending = true;
            updateState("停止処理中");
            MemoketSettings.saveResult(this, "今回の録音を確定しています");
            armStopTimeout();
            beginStopSequence();
            return START_NOT_STICKY;
        }
        if (!ACTION_START_RECORDING.equals(action)) return START_NOT_STICKY;

        trace.phase("START_REQUESTED", stateDetails());
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            finishWithError("Bluetooth接続権限がありません");
            return START_NOT_STICKY;
        }
        String address = MemoketSettings.address(this);
        if (address.isEmpty()) {
            finishWithError("Memoket Gemが選択されていません");
            return START_NOT_STICKY;
        }

        recordingStartPending = true;
        trace.phase("CONNECT_PREFLIGHT_STARTED");
        startForegroundCompat(buildNotification("Gemへ接続中"));
        updateState("接続中");

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishWithError("Bluetoothが無効です");
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                JSONObject probe = MemoketConnectionProbe.observe(this, address, 3_000L);
                AppLogger.event(this, "MEMOKET_CONNECT_PREFLIGHT", probe);
                trace.phase("CONNECT_PREFLIGHT_COMPLETED",
                        new JSONObject().put("scanAvailable", probe.optBoolean("scanAvailable"))
                                .put("selectedSeen", probe.optBoolean("selectedSeen"))
                                .put("selectedConnectable", probe.optBoolean("selectedConnectable")));
                BluetoothDevice device = adapter.getRemoteDevice(address);
                trace.phase("GATT_CONNECT_REQUESTED");
                BluetoothGatt connection = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (connection == null) {
                    finishWithError("Gemへ接続できませんでした");
                    return;
                }
                gatt = connection;
            } catch (Exception error) {
                finishWithError(error.getMessage() == null ? "Gemへ接続できませんでした" : error.getMessage());
            }
        }, "memoket-connect").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(metadataProbe);
        handler.removeCallbacks(stopTransferTimeout);
        closeGatt();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            trace.gattConnection(status, newState);
            if (stopCompleted) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("GATT接続エラー: " + status);
                return;
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                trace.phase("GATT_CONNECTED");
                trace.phase("SERVICE_DISCOVERY_REQUESTED");
                if (!connection.discoverServices()) finishWithError("GATTサービス探索を開始できません");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                finishWithError(stopPending
                        ? "対象ファイル取得・削除確認前にGemとの接続が切れました"
                        : "Gemとの接続が切れました");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            trace.servicesDiscovered(status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("Memoketサービス探索失敗: " + status);
                return;
            }
            BluetoothGattService service = connection.getService(MemoketGattSync.SERVICE);
            if (service == null) {
                finishWithError("Memoket GATTサービスが見つかりません");
                return;
            }
            data = service.getCharacteristic(MemoketGattSync.DATA);
            control = service.getCharacteristic(MemoketGattSync.CONTROL);
            response = service.getCharacteristic(MemoketGattSync.RESPONSE);
            trace.characteristicInventory(data != null, control != null, response != null);
            if (data == null || control == null || response == null) {
                finishWithError("Memoket GATT characteristicが不足しています");
                return;
            }
            enableInitialNotifications();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            String uuid = descriptor == null || descriptor.getCharacteristic() == null
                    ? "" : descriptor.getCharacteristic().getUuid().toString();
            trace.descriptorResult(uuid, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("通知設定に失敗しました: " + status);
                return;
            }
            if (stopCompleted) return;
            if (stopPending && stopNotifyStep > 0) {
                try {
                    if (descriptor == null || descriptor.getCharacteristic() == null ||
                            !MemoketGattSync.DATA.equals(descriptor.getCharacteristic().getUuid())) {
                        throw new IllegalStateException("停止処理中に想定外のCCCD応答を受信しました");
                    }
                    boolean enabled = Arrays.equals(descriptor.getValue(),
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    MemoketStopProtocol.Next next = stopProtocol.onDataNotificationWriteSucceeded(enabled);
                    if (next == MemoketStopProtocol.Next.ENABLE_DATA) {
                        trace.phase("STOP_DATA_NOTIFY_OFF_DONE");
                        stopNotifyStep = 2;
                        trace.phase("STOP_DATA_NOTIFY_ON_REQUESTED");
                        setNotification(data, true);
                    } else {
                        trace.phase("STOP_DATA_NOTIFY_ON_DONE");
                        beginStoppedRecordingTransfer();
                    }
                } catch (Exception exception) {
                    finishWithError(exception.getMessage());
                }
            } else {
                enableInitialNotifications();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            trace.commandWriteResult(status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("Gemコマンド送信に失敗しました: status=" + status + " command=" + hex(lastCommand));
                return;
            }
            synchronized (MemoketRemoteRecordingService.this) {
                commandBusy = false;
                sendNext();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic.getUuid(), value);
        }
    };

    private void enableInitialNotifications() {
        BluetoothGattCharacteristic next = notifySetupStep++ == 0 ? data :
                notifySetupStep == 2 ? response : null;
        if (next == null) {
            trace.phase("HANDSHAKE_STARTED");
            queue(session.firstCommand());
            return;
        }
        setNotification(next, true);
    }

    private void handleNotification(UUID uuid, byte[] value) {
        if (stopCompleted) return;
        try {
            if (uuid.equals(MemoketGattSync.DATA)) {
                if (!stopListRequested || transfer == null) return;
                armStopTimeout();
                trace.data(value, transfer.debugState(), transfer.bufferedBytes());
                byte[] next = transfer.onData(value);
                trace.transferState(transfer, "DATA_RECEIVED");
                if (next != null) queue(next);
                if (transfer.shouldRequestMetadata()) scheduleMetadata(DATA_QUIET_MS);
                return;
            }
            if (!uuid.equals(MemoketGattSync.RESPONSE)) return;

            trace.response(value, session.debugStep(), transfer == null ? "INACTIVE" : transfer.debugState());
            if (!session.isReady()) {
                byte[] next = session.onResponse(value);
                trace.phase(session.debugStep());
                if (next != null) queue(next);
                if (session.isReady() && recordingStartPending) {
                    recordingStartPending = false;
                    recordingStartAwaitingAck = true;
                    trace.phase("RECORD_START_COMMAND_REQUESTED");
                    queue(new byte[]{0x03});
                }
                return;
            }

            if (recordingStartAwaitingAck && !stopPending && value != null && value.length == 2 &&
                    value[0] == 0x03 && (value[1] & 0xff) == 0xff) {
                recordingStartAwaitingAck = false;
                recordingActive = true;
                MemoketSettings.recordingStarted(this, System.currentTimeMillis(),
                        java.time.ZoneId.systemDefault().getId());
                trace.phase("RECORDING_ACTIVE");
                updateState("録音中");
                updateNotification("Memoket Gem 録音中");
                log("MEMOKET_REMOTE_RECORDING_STARTED", null);
                return;
            }

            if (!stopListRequested || transfer == null) return;
            armStopTimeout();
            byte[] next = transfer.onControl(value);
            trace.transferState(transfer, "RESPONSE_RECEIVED");
            if (isShortMetadataStatus(value) && transfer.shouldRequestMetadata()) {
                scheduleMetadata(300);
            }
            if (next != null) queue(next);
            if (transfer.isDone()) {
                if (transfer.completedCount() != 1) {
                    throw new IllegalStateException("今回の録音ファイルが見つかりません。Gem上のデータは保持しました");
                }
                completeStopSequence();
            }
        } catch (Exception error) {
            finishWithError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void beginStopSequence() {
        if (!recordingActive || data == null) {
            finishWithError("Gem録音中の接続がありません");
            return;
        }
        stopProtocol.begin();
        stopNotifyStep = 1;
        trace.phase("STOP_DATA_NOTIFY_OFF_REQUESTED");
        setNotification(data, false);
    }

    private void beginStoppedRecordingTransfer() throws Exception {
        long startedAt = MemoketSettings.recordingStartedAt(this);
        String zoneId = MemoketSettings.recordingStartedZone(this);
        MemoketRecordingWindow recording = new MemoketRecordingWindow(startedAt, stopRequestedAtMs, zoneId);
        stopNotifyStep = 0;
        // DATA notification OFF -> ON is only a BLE signal. Do not call it
        // recording/deletion complete before this recording is saved and ACKed.
        MemoketRecordingStore store = new MemoketRecordingStore(this);
        transfer = new MemoketTransfer((name, payload, crc) -> {
            store.persist(name, payload, crc);
            trace.filePersisted(name, payload.length, crc);
            AppLogger.event(this, "MEMOKET_STOP_TARGET_SAVED",
                    new JSONObject().put("fileName", name).put("bytes", payload.length));
        }, recording::matches, 1);
        stopListRequested = true;
        updateState("ファイル取得中");
        MemoketSettings.saveResult(this, "今回の録音1件を取得中。端末保存後にGemの対象ファイルを削除します");
        updateNotification("Memoket Gem 今回の録音を取得中");
        trace.phase("STOP_TARGET_LIST_REQUESTED", new JSONObject()
                .put("recordingStartMs", startedAt)
                .put("recordingStopMs", stopRequestedAtMs)
                .put("zoneId", zoneId));
        armStopTimeout();
        queue(MemoketTransfer.initialCommand());
    }

    private void armStopTimeout() {
        handler.removeCallbacks(stopTransferTimeout);
        handler.postDelayed(stopTransferTimeout, STOP_TRANSFER_IDLE_MS);
    }

    private void completeStopSequence() {
        // Called exclusively after durable save -> filename-specific 0x05 ACK
        // -> confirmed 0x05 01 response; no extra listing or file is touched.
        stopCompleted = true;
        stopPending = false;
        recordingActive = false;
        stopListRequested = false;
        stopNotifyStep = 0;
        handler.removeCallbacks(stopTransferTimeout);
        updateState("停止");
        String filename = transfer.debugFileName();
        try {
            JSONObject details = new JSONObject()
                    .put("fileName", filename)
                    .put("downloadedFiles", 1)
                    .put("fileAcknowledgedByGem", true);
            log("MEMOKET_REMOTE_RECORDING_STOPPED", details);
            trace.completed(details);
        } catch (Exception ignored) { }
        MemoketSettings.saveResult(this, "今回の録音「" + filename + "」を保存し、Gemが対象ファイルの完了通知を受理しました");
        closeGatt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void setNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (characteristic != null) {
            trace.descriptorRequest(characteristic == data ? "DATA" :
                    characteristic == response ? "RESPONSE" : "OTHER",
                    enabled, characteristic.getUuid().toString());
        }
        if (gatt == null || characteristic == null ||
                !gatt.setCharacteristicNotification(characteristic, enabled)) {
            finishWithError("Gem通知切替に失敗しました");
            return;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            finishWithError("Gem通知descriptorがありません");
            return;
        }
        descriptor.setValue(enabled
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) finishWithError("Gem通知descriptor書込に失敗しました");
    }

    private void scheduleMetadata(long delayMs) {
        trace.metadataProbeScheduled(delayMs, transfer.debugState(), transfer.bufferedBytes());
        handler.removeCallbacks(metadataProbe);
        handler.postDelayed(metadataProbe, delayMs);
    }

    private static boolean isShortMetadataStatus(byte[] value) {
        return value != null && value.length <= 4 && value.length > 0 && (value[0] & 0xff) == 2;
    }

    private synchronized void queue(byte[] command) {
        commands.add(Arrays.copyOf(command, command.length));
        trace.commandQueued(command, commands.size());
        sendNext();
    }

    private void sendNext() {
        if (commandBusy || commands.isEmpty() || gatt == null || control == null) return;
        byte[] command = commands.removeFirst();
        lastCommand = Arrays.copyOf(command, command.length);
        trace.commandWriteRequested(command, commands.size());
        commandBusy = true;
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(command);
        if (!gatt.writeCharacteristic(control)) {
            commandBusy = false;
            finishWithError("Gemコマンドを受理できませんでした command=" + hex(command));
        }
    }

    private void updateState(String state) {
        getSharedPreferences("memoket_sync", MODE_PRIVATE).edit()
                .putString("remote_recording_state", state)
                .apply();
    }

    private void finishWithError(String message) {
        handler.removeCallbacks(stopTransferTimeout);
        if (trace != null) {
            trace.transferState(transfer, "FAILURE");
            trace.failure(message, null);
        }
        MemoketSettings.saveResult(this, stopPending
                ? "Gem録音の停止・対象ファイル処理が未完了: " + message
                : "Gem録音操作失敗: " + message);
        updateState("エラー");
        try { log("MEMOKET_REMOTE_RECORDING_FAILED", new JSONObject().put("error", message)); }
        catch (Exception ignored) { }
        stopForeground(STOP_FOREGROUND_REMOVE);
        closeGatt();
        stopSelf();
    }

    private void closeGatt() {
        handler.removeCallbacks(metadataProbe);
        BluetoothGatt current = gatt;
        gatt = null;
        if (current != null) {
            try { current.disconnect(); } catch (Exception ignored) { }
            try { current.close(); } catch (Exception ignored) { }
        }
    }

    private JSONObject stateDetails() {
        JSONObject d = new JSONObject();
        try {
            d.put("recordingStartPending", recordingStartPending);
            d.put("recordingActive", recordingActive);
            d.put("stopPending", stopPending);
            d.put("stopListRequested", stopListRequested);
            d.put("stopNotifyStep", stopNotifyStep);
            d.put("commandBusy", commandBusy);
            d.put("queueDepth", commands.size());
            d.put("sessionStep", session.debugStep());
            d.put("transferState", transfer == null ? "" : transfer.debugState());
        } catch (Exception ignored) { }
        return d;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private void log(String event, JSONObject details) {
        JSONObject out = details == null ? new JSONObject() : details;
        try {
            if (trace != null) out.put("sessionId", trace.sessionId());
        } catch (Exception ignored) { }
        AppLogger.event(this, event, out);
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Memoket Gem 録音", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("24hRecoder")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
