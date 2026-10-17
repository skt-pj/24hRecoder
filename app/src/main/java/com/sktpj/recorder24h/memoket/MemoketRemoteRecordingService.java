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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MemoketRemoteRecordingService extends Service {
    public static final String ACTION_START_RECORDING = "com.sktpj.recorder24h.memoket.START_RECORDING";
    public static final String ACTION_RECORD_FIVE_SECONDS = "com.sktpj.recorder24h.memoket.RECORD_FIVE_SECONDS";
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
    private BluetoothGattCharacteristic extraNotify5;
    private BluetoothGattCharacteristic extraNotify6;
    private boolean commandBusy;
    private int notifySetupStep;
    private boolean recordingStartPending;
    private boolean fiveSecondRecording;
    private boolean recordingStartAwaitingAck;
    private boolean recordingActive;
    private boolean stopPending;
    private long stopRequestedAtMs;
    private volatile boolean stopCompleted;
    private byte[] lastCommand;
    private MemoketTransfer stopTransfer;
    private int stopNotifyStep;
    private final Deque<byte[]> earlyAudio = new ArrayDeque<>();
    private final Handler stopHandler = new Handler(Looper.getMainLooper());
    private final Runnable fiveSecondStop = () -> {
        if (fiveSecondRecording && recordingActive && !stopPending && !stopCompleted) {
            trace.phase("FIVE_SECOND_RECORDING_ENDED");
            beginOfficialStop();
        }
    };
    private final Runnable stopWatchdog = new Runnable() {
        @Override public void run() {
            checkStopProgress();
        }
    };
    private long lastStopActivityAtMs;
    private long lastStopDataAtMs;
    private long lastMetadataRequestAtMs;
    private int stopSavedFiles;
    private boolean inFlightMetadata;
    private int midTransferStatusStep;
        private long lastDeleteStatusProbeAtMs;
    private long deleteStatusStartedAtMs;

    @Override
    public void onCreate() {
        super.onCreate();
        trace = new MemoketDebugTrace(this, "REMOTE_RECORDING");
        trace.phase("SERVICE_CREATED");
        createChannel();
        // Keep one BLE connection for recording control, file acquisition and deletion.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        boolean stopRequest = ACTION_STOP_RECORDING.equals(action);
        boolean fiveSecondRequest = ACTION_RECORD_FIVE_SECONDS.equals(action);
        if (!stopRequest && !ACTION_START_RECORDING.equals(action)
                && !fiveSecondRequest) return START_NOT_STICKY;
        if (fiveSecondRequest && (recordingStartPending || recordingActive || stopPending))
            return START_NOT_STICKY;
        if (stopRequest) {
            // A duplicate user STOP must not abort an ongoing verified download.
            if (stopPending || stopCompleted) return START_NOT_STICKY;
            trace.phase("STOP_REQUESTED", stateDetails());
            if (!MemoketStopSafety.canStop(gatt != null && data != null,
                    session.isReady(), recordingActive, stopPending || stopCompleted)) {
                finishWithError("アプリがこの接続で開始した録音を確認できません。取得・削除を送信しません");
                return START_NOT_STICKY;
            }
            beginOfficialStop();
            return START_NOT_STICKY;
        }
        if (!stopRequest && !MemoketFlowPolicy.remoteStartAllowed()) {
            MemoketSettings.saveResult(this, "Gem遠隔録音開始を利用できません");
            if (!recordingActive) stopSelf(startId);
            return START_NOT_STICKY;
        }
        fiveSecondRecording = fiveSecondRequest;
        trace.phase(fiveSecondRecording ? "FIVE_SECOND_RECORDING_REQUESTED"
                : "START_REQUESTED", stateDetails());
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

    private synchronized void beginOfficialStop() {
        if (stopPending || stopCompleted) return;
        // The observed official STOP transition follows the acknowledged
        // START on the same GATT connection without another 0x03.
        if (!MemoketStopSafety.canStop(gatt != null && data != null,
                session.isReady(), recordingActive, stopPending || stopCompleted)) {
            finishWithError("同一GATTのSTART 03ff記録がないため、停止・取得を実行しません");
            return;
        }
        stopRequestedAtMs = System.currentTimeMillis();
        stopPending = true;
        recordingStartAwaitingAck = false;
        stopSavedFiles = 0;
        earlyAudio.clear();
        String activeName = session.recordingFileHint();
        long startedAt = MemoketSettings.recordingStartedAt(this);
        if (startedAt <= 0 || startedAt > stopRequestedAtMs
                || MemoketSettings.recordingStartedZone(this).isEmpty()) {
            finishWithError("現在の録音開始時刻を特定できません。過去ファイルを誤って削除しないため中断します");
            return;
        }
        // 01 announces a single name AND starts its DATA stream. The official
        // capture has no non-destructive all-file listing or filename selection.
        // Accept only the recording identified by the app's own start window;
        // an unsolicited filename hint cannot override that ownership check.
        MemoketRecordingWindow window = new MemoketRecordingWindow(
                startedAt, stopRequestedAtMs, MemoketSettings.recordingStartedZone(this));
        if (!activeName.isEmpty() && !window.matches(activeName)) {
            finishWithError("Gem通知のファイル名が今回の録音日時と異なります。過去ファイルを削除しません");
            return;
        }
        MemoketTransfer.FileSelector latest = filename ->
                window.matches(filename) && (activeName.isEmpty() || activeName.equals(filename));
        trace.phase("LATEST_RECORDING_IDENTIFIED");
        stopTransfer = new MemoketTransfer((name, payload, crc) -> {
            new MemoketRecordingStore(this).persist(name, payload, crc);
            stopSavedFiles++;
            log("MEMOKET_FILE_DURABLY_SAVED_BEFORE_GEM_ACK",
                    new JSONObject().put("filename", name)
                            .put("bytes", payload.length).put("crc32", Long.toHexString(crc)));
        }, latest, 1, true);
        lastStopActivityAtMs = System.currentTimeMillis();
        lastStopDataAtMs = lastStopActivityAtMs;
        lastMetadataRequestAtMs = 0;
        lastDeleteStatusProbeAtMs = 0;
        deleteStatusStartedAtMs = 0;
        inFlightMetadata = false;
        midTransferStatusStep = 0;
        updateState("停止03候補を確認中（実機停止は未検証）");
        MemoketSettings.recordingStopRequested(this, stopRequestedAtMs);
        trace.phase("STOP_03_HISTORICAL_CANDIDATE_REQUESTED_UNVERIFIED");
        stopNotifyStep = 0;
        queue(new byte[]{0x03});
        stopHandler.removeCallbacks(stopWatchdog);
        stopHandler.postDelayed(stopWatchdog, 500);
    }

    @Override
    public void onDestroy() {
        stopHandler.removeCallbacks(fiveSecondStop);
        stopHandler.removeCallbacks(stopWatchdog);
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
            extraNotify5 = service.getCharacteristic(MemoketTestEngine.EXTRA5);
            extraNotify6 = service.getCharacteristic(MemoketTestEngine.EXTRA6);
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
            if (stopPending && (stopNotifyStep == 1 || stopNotifyStep == 2)) {
                byte[] expected = stopNotifyStep == 1
                        ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if (descriptor == null || !CCCD.equals(descriptor.getUuid())
                        || descriptor.getCharacteristic() == null
                        || !MemoketGattSync.DATA.equals(descriptor.getCharacteristic().getUuid())
                        || !Arrays.equals(descriptor.getValue(), expected)) {
                    finishWithError("STOPのCCCD書込完了が期待したDATA通知設定と一致しません: " + uuid);
                    return;
                }
            }
            if (stopPending && stopNotifyStep == 1) {
                stopNotifyStep = 2;
                trace.phase("OFFICIAL_DATA_CCCD_ON_REQUESTED");
                setNotification(data, true);
                return;
            }
            if (stopPending && stopNotifyStep == 2) {
                stopNotifyStep = 3;
                trace.phase("OFFICIAL_FILE_LIST_REQUESTED");
                lastStopActivityAtMs = System.currentTimeMillis();
                queue(MemoketTransfer.initialCommand());
                return;
            }
            // During STOP, a late unrelated descriptor callback must never
            // restart the setup handshake or enqueue another 00 command.
            if (stopPending) return;
            enableInitialNotifications();
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
        BluetoothGattCharacteristic next = null;
        while (notifySetupStep < 4 && next == null) {
            switch (notifySetupStep++) {
                // Captured official HCI enables RESPONSE CCCD (0x0031)
                // before DATA CCCD (0x002c).
                case 0: next = response; break;
                case 1: next = data; break;
                case 2: next = extraNotify5; break;
                case 3: next = extraNotify6; break;
                default: break;
            }
            if (next != null && next.getDescriptor(CCCD) == null) next = null;
        }
        if (next == null) {
            trace.phase("HANDSHAKE_STARTED");
            queue(session.firstCommand());
        } else {
            setNotification(next, true);
        }
    }

    private synchronized void handleNotification(UUID uuid, byte[] value) {
        if (stopCompleted) return;
        try {
            if (stopPending) {
                if (stopNotifyStep == 0) {
                    if (!uuid.equals(MemoketGattSync.RESPONSE)
                            || value == null || value.length == 0
                            || (value[0] & 0xff) != 0x03) return;
                    trace.response(value, "STOP_03_CANDIDATE", "AWAIT_03FF");
                    if (value.length == 2 && (value[1] & 0xff) == 0xff) {
                        trace.phase("STOP_03FF_RECEIVED_NOT_DEVICE_STOP_PROOF");
                        stopNotifyStep = 1;
                        setNotification(data, false);
                    }
                    return;
                }
                onOfficialStopNotification(uuid, value);
                return;
            }
            if (!uuid.equals(MemoketGattSync.RESPONSE)) return;
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
                if (fiveSecondRecording) {
                    trace.phase("FIVE_SECOND_RECORDING_TIMER_STARTED");
                    stopHandler.postDelayed(fiveSecondStop, 5_000L);
                }
            }
        } catch (Exception error) {
            finishWithError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }


    /**
     * Replays the official HCI recording-control to file-transfer transition
     * on the SAME GATT connection. A CCCD transition is not proof of stop.
     * A real recording stop remains unverified until the user checks the Gem.
     */
    private void onOfficialStopNotification(UUID uuid, byte[] value) throws Exception {
        if (value == null) return;
        long now = System.currentTimeMillis();
        if (uuid.equals(MemoketGattSync.DATA)) {
            // Ignore pre-list audio until both CCCD writes and the 01 request finish.
            if (stopNotifyStep != 3) return;
            lastStopActivityAtMs = now;
            lastStopDataAtMs = now;
            if (stopTransfer == null) return;
            if ("WAIT_LIST".equals(stopTransfer.debugState())) {
                // DATA can overtake the 01 list response; keep the exact order.
                if (earlyAudio.size() >= 256) {
                    throw new IllegalStateException("一覧応答前のDATAが256件を超えました");
                }
                earlyAudio.addLast(Arrays.copyOf(value, value.length));
                return;
            }
            onOfficialData(value);
            return;
        }
        if (!uuid.equals(MemoketGattSync.RESPONSE) || value.length == 0) return;
        lastStopActivityAtMs = now;
        trace.response(value, "OFFICIAL_STOP_TRANSFER", "ONE_GATT");
        int opcode = value[0] & 0xff;
        if (midTransferStatusStep == 1 && opcode == 0) {
            MemoketTransferStatus.verifyIntermediate00(value,
                    stopTransfer == null ? "" : stopTransfer.debugFileName());
            midTransferStatusStep = 2;
            trace.phase("OFFICIAL_E1_STATUS_REQUESTED_AFTER_00_RESPONSE");
            queue(new byte[]{(byte) 0xe1});
        } else if (midTransferStatusStep == 2 && opcode == 0xe1) {
            MemoketTransferStatus.verifyIntermediateE1(value);
            midTransferStatusStep = 3;
            trace.phase("OFFICIAL_E1_STATUS_RECEIVED");
        }
        if (opcode == 2) inFlightMetadata = false;
        byte[] next = stopTransfer == null ? null : stopTransfer.onControl(value);
        if (opcode == 1 && stopTransfer != null
                && !"WAIT_LIST".equals(stopTransfer.debugState())) {
            // Once the filename exists, feed DATA that arrived before 01.
            while (!earlyAudio.isEmpty()) onOfficialData(earlyAudio.removeFirst());
        }
        if (next != null) {
            if (next.length > 0 && next[0] == 5) {
                // MemoketTransfer emits 05 only AFTER CRC and durable .raw/.opus save.
                trace.phase("OFFICIAL_DURABLE_SAVE_FINISHED_DELETE_REQUESTED");
            } else if (next.length == 1 && next[0] == 0
                    && stopTransfer != null && stopTransfer.waitingForIdleStatus()) {
                deleteStatusStartedAtMs = now;
                lastDeleteStatusProbeAtMs = now;
                trace.phase("OFFICIAL_POST_05_IDLE_00_REQUESTED");
            }
            queue(next);
        }
        if (stopTransfer != null && stopTransfer.isDone()) {
            if (stopSavedFiles != 1 || stopTransfer.deleteAcks() != 1
                    || !stopTransfer.postDeleteIdleConfirmed()) {
                throw new IllegalStateException("対象1件の永続保存／05 ACK／00待機確認が未成立: saved="
                        + stopSavedFiles + ", ack=" + stopTransfer.deleteAcks());
            }
            finishOfficialTransfer();
        }
    }

    private void onOfficialData(byte[] value) {
        byte[] next = stopTransfer.onData(value);
        if (next != null) queue(next);
        if (midTransferStatusStep == 0 && stopTransfer.dataBlockCount() >= 19) {
            // Official HCI interleaving: wait for the 00 response BEFORE e1.
            midTransferStatusStep = 1;
            trace.phase("OFFICIAL_INTERLEAVED_STATUS_00_REQUESTED");
            queue(new byte[]{0x00});
        }
    }

    private synchronized void checkStopProgress() {
        if (!stopPending || stopCompleted || stopTransfer == null) return;
        long now = System.currentTimeMillis();
        try {
            if (stopNotifyStep == 0 && now - stopRequestedAtMs > 10_000L) {
                throw new IllegalStateException("03再送に対する03ffが10秒以内に確認できません。Gemの録音停止は未確認です");
            }
            // No absolute download time cap: only missing BLE progress is fatal.
            if (now - lastStopActivityAtMs > 60_000L) {
                throw new IllegalStateException("Gemから60秒間DATA/RESPONSEがなく、処理を終了しました");
            }
            if (inFlightMetadata && now - lastMetadataRequestAtMs > 5_000L) {
                // A lost 02 response must not permanently block metadata queries.
                inFlightMetadata = false;
                trace.phase("METADATA_RESPONSE_TIMEOUT_CONTINUE");
            }
            // The official capture reads final size/CRC after the DATA stream
            // has ended. Do not finalize a still-growing recording mid-stream.
            boolean intermediateStatusReady = midTransferStatusStep == 3
                    || (midTransferStatusStep == 0 && stopTransfer.dataBlockCount() < 19);
            if (stopNotifyStep == 3 && !commandBusy && commands.isEmpty()
                    && intermediateStatusReady
                    && MemoketMetadataGate.mayRequest(now, lastStopDataAtMs,
                            lastMetadataRequestAtMs, stopTransfer.shouldRequestMetadata(),
                            inFlightMetadata)) {
                inFlightMetadata = true;
                lastMetadataRequestAtMs = now;
                trace.phase("OFFICIAL_METADATA_AFTER_DATA");
                queue(MemoketTransfer.metadataCommand());
            }
            if (stopTransfer.waitingForIdleStatus()) {
                if (deleteStatusStartedAtMs > 0
                        && now - deleteStatusStartedAtMs > 60_000L) {
                    throw new IllegalStateException("05 01受信・端末保存済みですが、Gemの00 00待機応答を確認できません");
                }
                if (!commandBusy && commands.isEmpty()
                        && now - lastDeleteStatusProbeAtMs >= 5_000L) {
                    lastDeleteStatusProbeAtMs = now;
                    trace.phase("OFFICIAL_POST_05_IDLE_STATUS_RETRY");
                    queue(new byte[]{0x00});
                }
            }
            stopHandler.postDelayed(stopWatchdog, 500);
        } catch (Exception error) {
            finishWithError(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void finishOfficialTransfer() {
        stopHandler.removeCallbacks(fiveSecondStop);
        stopCompleted = true;
        stopPending = false;
        recordingActive = false;
        stopHandler.removeCallbacks(stopWatchdog);
        trace.phase("FILE_SAVED_05_ACK_IDLE_STOP_UNVERIFIED", stateDetails());
        updateState("録音候補1件保存・Gemの05 ACK受信（本体停止未確認）");
        MemoketSettings.saveResult(this,
                "対象1件をサイズ・CRC照合後に端末へ永続保存し、Gemの完了通知05 ACK(0501)"
                + "と00待機応答を確認。Gem本体の録音停止と実ファイル削除は未検証");
        log("MEMOKET_FILE_SAVED_05_ACK_IDLE_STOP_UNVERIFIED", stateDetails());
        updateNotification("今回1件保存・Gem 05 ACK受信");
        stopForeground(STOP_FOREGROUND_REMOVE);
        closeGatt();
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
        stopHandler.removeCallbacks(fiveSecondStop);
        stopHandler.removeCallbacks(stopWatchdog);
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
            d.put("fiveSecondRecording", fiveSecondRecording);
            d.put("stopPending", stopPending);
            d.put("commandBusy", commandBusy);
            d.put("queueDepth", commands.size());
            d.put("sessionStep", session.debugStep());
            d.put("stopCccdStep", stopNotifyStep);
            d.put("savedFiles", stopSavedFiles);
            d.put("gemDeleteAcks", stopTransfer == null ? 0 : stopTransfer.deleteAcks());
            d.put("gemIdleAfterAck", stopTransfer != null && stopTransfer.postDeleteIdleConfirmed());
            d.put("midTransferStatusStep", midTransferStatusStep);
            d.put("transferState", stopTransfer == null ? "NONE" : stopTransfer.debugState());
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
