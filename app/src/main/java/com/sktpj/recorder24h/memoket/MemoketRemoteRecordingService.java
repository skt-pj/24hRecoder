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

    private final Deque<byte[]> commands = new ArrayDeque<>();
    private final MemoketSessionProtocol session = new MemoketSessionProtocol();
    private MemoketDebugTrace trace;
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
        // Recording control and file transfer intentionally use separate BLE connections.
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
                    if (next == MemoketStopProtocol.Next.DISCONNECT_BEFORE_TRANSFER) {
                        trace.phase("STOP_DATA_NOTIFY_OFF_DONE");
                        finishStopControlAndScheduleTransfer();
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
        if (stopCompleted || !uuid.equals(MemoketGattSync.RESPONSE)) return;
        try {
            trace.response(value, session.debugStep(), "RECORDING_CONTROL");
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
            if (recordingStartAwaitingAck && !stopPending && value != null
                    && value.length == 2 && value[0] == 0x03 && (value[1] & 0xff) == 0xff) {
                recordingStartAwaitingAck = false;
                recordingActive = true;
                MemoketSettings.recordingStarted(this, System.currentTimeMillis(),
                        java.time.ZoneId.systemDefault().getId());
                trace.phase("RECORDING_ACTIVE");
                updateState("録音中");
                updateNotification("Memoket Gem 録音中");
                log("MEMOKET_REMOTE_RECORDING_STARTED", null);
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

    private void finishStopControlAndScheduleTransfer() {
        // The observed OFF->ON sequence can restart recording. Close this BLE
        // connection with DATA disabled. An independent connection retrieves
        // only the stopped session's file. Do not report physical stop verified.
        stopCompleted = true;
        stopPending = false;
        recordingActive = false;
        stopNotifyStep = 0;
        trace.phase("STOP_OFF_DISCONNECTING");
        updateState("停止操作済・取得待ち");
        MemoketSettings.saveResult(this, "Gemに停止側BLE操作を送信しました。録音停止の実機確認前です。再開を避けるため通信を切って対象ファイルを取得します");
        closeGatt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        try {
            long startedAt = MemoketSettings.recordingStartedAt(this);
            String zoneId = MemoketSettings.recordingStartedZone(this);
            new MemoketRecordingWindow(startedAt, stopRequestedAtMs, zoneId);
            MemoketSyncScheduler.syncAfterStop(this, startedAt, stopRequestedAtMs, zoneId);
            log("MEMOKET_STOP_TRANSFER_SEPARATED", new JSONObject()
                    .put("recordingStartMs", startedAt)
                    .put("recordingStopMs", stopRequestedAtMs)
                    .put("sameSessionDataReenabled", false));
        } catch (Exception error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            updateState("エラー");
            MemoketSettings.saveResult(this, "停止操作後のファイル取得を予約できません: " + reason);
            trace.failure(reason, error);
        }
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
        MemoketSettings.setRemoteRecordingState(this, state);
    }

    private void finishWithError(String message) {
        if (trace != null) {
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
            d.put("stopNotifyStep", stopNotifyStep);
            d.put("commandBusy", commandBusy);
            d.put("queueDepth", commands.size());
            d.put("sessionStep", session.debugStep());
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
