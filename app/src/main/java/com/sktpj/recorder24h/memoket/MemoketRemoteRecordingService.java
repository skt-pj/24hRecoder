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
import com.sktpj.recorder24h.storage.RecordingIntentStore;
import com.sktpj.recorder24h.storage.RecorderStateStore;
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
    public static final String ACTION_START_CONTINUOUS = "com.sktpj.recorder24h.memoket.START_CONTINUOUS";
    public static final String ACTION_STOP_CONTINUOUS = "com.sktpj.recorder24h.memoket.STOP_CONTINUOUS";
    private static final long CONTINUOUS_SEGMENT_MS = 5L * 60_000L;
    public static final String ACTION_RECORD_FIVE_SECONDS = "com.sktpj.recorder24h.memoket.RECORD_FIVE_SECONDS";
    public static final String ACTION_STOP_RECORDING = "com.sktpj.recorder24h.memoket.STOP_RECORDING";
    public static final String ACTION_MANUAL_TEST_START = "com.sktpj.recorder24h.memoket.MANUAL_TEST_START";
    public static final String ACTION_MANUAL_TEST_STOP = "com.sktpj.recorder24h.memoket.MANUAL_TEST_STOP";
    public static final String ACTION_CANDIDATE_OFF = "com.sktpj.recorder24h.memoket.CANDIDATE_OFF";
    public static final String ACTION_CANDIDATE_TOGGLE = "com.sktpj.recorder24h.memoket.CANDIDATE_TOGGLE";
    public static final String ACTION_CANDIDATE_STATUS = "com.sktpj.recorder24h.memoket.CANDIDATE_STATUS";
    public static final String ACTION_CANDIDATE_TRANSFER = "com.sktpj.recorder24h.memoket.CANDIDATE_TRANSFER";
    public static final String ACTION_DIAG_START_ONLY = "com.sktpj.recorder24h.memoket.DIAG_START_ONLY";
    public static final String ACTION_DIAG_STOP_OFF = "com.sktpj.recorder24h.memoket.DIAG_STOP_OFF";
    public static final String ACTION_DIAG_STOP_TOGGLE = "com.sktpj.recorder24h.memoket.DIAG_STOP_TOGGLE";
    public static final String ACTION_DIAG_STOP_STATUS = "com.sktpj.recorder24h.memoket.DIAG_STOP_STATUS";
    public static final String ACTION_DIAG_STOP_TRANSFER = "com.sktpj.recorder24h.memoket.DIAG_STOP_TRANSFER";
    public static final String ACTION_DIAG_FETCH_ONLY = "com.sktpj.recorder24h.memoket.DIAG_FETCH_ONLY";

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
    private boolean streamCaptureMode;
    private boolean continuousMode;
    private boolean continuousStopRequested;
    private long continuousStartedAtMs;
    private String continuousSegmentId;
    private long continuousLastDataAtMs;
    private int continuousRetries;
    private volatile boolean manualTestMode;
    private volatile boolean manualStopPending;
    private int candidateId;
    private int candidateStep;
    private boolean diagStartOnly;
    private boolean diagStopOnly;
    private boolean diagFileOnly;
    private boolean diagMetadataInFlight;
    private long diagLastDataAtMs;
    private MemoketTransfer diagTransfer;
    private final Deque<byte[]> diagEarlyAudio = new ArrayDeque<>();
    private volatile boolean stopCompleted;
    private final Runnable diagMetadataProbe = () -> {
        if (!diagFileOnly || stopCompleted || diagTransfer == null || diagMetadataInFlight) return;
        if (diagTransfer.shouldRequestMetadata()) {
            diagMetadataInFlight = true;
            trace.phase("DIAG_FILE_METADATA_REQUESTED_NO_DELETE", stateDetails());
            queue(MemoketTransfer.metadataCommand());
        }
    };
    private final Runnable diagStartDeadline = () -> {
        if (diagStartOnly && !stopCompleted) finishWithError("開始候補03の応答が20秒以内に届きません");
    };
    private final Runnable diagFetchDeadline = () -> {
        if (diagFileOnly && !stopCompleted) finishWithError("診断取得が120秒を超えました。05は送っていません");
    };
    private final Runnable manualStopTimeout = () -> {
        if (manualStopPending) finishWithError("Gemへの停止操作が完了しませんでした");
    };
    private boolean recordingStartAwaitingAck;
    private volatile boolean recordingActive;
    private boolean stopPending;
    private long stopRequestedAtMs;
    private byte[] lastCommand;
    private MemoketTransfer stopTransfer;
    private MemoketOfficialStopSequence stopSequence;
    private int stopNotifyStep;
    private final Deque<byte[]> earlyAudio = new ArrayDeque<>();
    private final Handler stopHandler = new Handler(Looper.getMainLooper());
    private final Runnable fiveSecondStop = () -> {
        if (candidateId != 0 && recordingActive && !manualStopPending && !stopCompleted) {
            trace.phase("CANDIDATE_FIVE_SECONDS_ELAPSED", stateDetails());
            beginManualTestStop();
        } else if (fiveSecondRecording && recordingActive && !stopPending && !stopCompleted) {
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
    private final Runnable continuousHeartbeat = new Runnable() {
        @Override public void run() {
            if (!continuousMode || continuousStopRequested || stopCompleted) return;
            long lastData = continuousLastDataAtMs > 0 ? continuousLastDataAtMs : continuousStartedAtMs;
            RecorderStateStore.heartbeat(MemoketRemoteRecordingService.this,
                    streamStopRequested ? "ROTATING" : "RECORDING",
                    continuousSegmentId, continuousStartedAtMs, lastData);
            stopHandler.postDelayed(this, 5_000L);
        }
    };
    private final Runnable continuousRetry = () -> {
        if (!continuousMode || continuousStopRequested
                || !RecordingIntentStore.isRequested(this)
                || !"MEMOKET".equals(MemoketSettings.source(this))) return;
        recordingStartPending = false;
        recordingActive = false;
        stopPending = false;
        stopCompleted = false;
        manualStopPending = false;
        commandBusy = false;
        notifySetupStep = 0;
        commands.clear();
        data = null;
        response = null;
        control = null;
        extraNotify5 = null;
        extraNotify6 = null;
        gatt = null;
        trace.phase("CONTINUOUS_GEM_RETRY");
        onStartCommand(new Intent(this, MemoketRemoteRecordingService.class)
                .setAction(ACTION_START_CONTINUOUS), 0, 0);
    };
    private boolean streamCaptureTimerStarted;
    private boolean streamStopRequested;
    private boolean streamMetadataReceived;
    private final Runnable streamStopAtFiveSeconds = () -> {
        if (!streamCaptureMode || stopCompleted || streamStopRequested || !stopPending) return;
        streamStopRequested = true;
        stopRequestedAtMs = System.currentTimeMillis();
        MemoketSettings.recordingStopRequested(this, stopRequestedAtMs);
        if (continuousMode) {
            RecorderStateStore.write(this, "ROTATING", continuousSegmentId, null);
        }
        trace.phase("STREAM_5S_STOP_CONTROL_0200_REQUESTED", stateDetails());
        updateState("録音確定中");
        inFlightMetadata = true;
        lastMetadataRequestAtMs = stopRequestedAtMs;
        queue(MemoketTransfer.metadataCommand());
    };

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
        if (action == null && RecordingIntentStore.isRequested(this)
                && "MEMOKET".equals(MemoketSettings.source(this)))
            action = ACTION_START_CONTINUOUS;
        if (ACTION_STOP_CONTINUOUS.equals(action)) {
            continuousStopRequested = true;
            stopHandler.removeCallbacks(continuousRetry);
            stopHandler.removeCallbacks(continuousHeartbeat);
            if (continuousMode && stopPending && !streamStopRequested) {
                stopHandler.removeCallbacks(streamStopAtFiveSeconds);
                streamStopAtFiveSeconds.run();
            } else if (!continuousMode || !stopPending || stopCompleted) {
                stopHandler.removeCallbacks(streamStopAtFiveSeconds);
                closeGatt();
                RecorderStateStore.write(this, "STOPPED", null, null);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        boolean continuousRequest = ACTION_START_CONTINUOUS.equals(action);
        boolean stopRequest = ACTION_STOP_RECORDING.equals(action);
        boolean fiveSecondRequest = ACTION_RECORD_FIVE_SECONDS.equals(action);
        boolean manualStartRequest = ACTION_MANUAL_TEST_START.equals(action);
        boolean manualStopRequest = ACTION_MANUAL_TEST_STOP.equals(action);
        int requestedCandidate = ACTION_CANDIDATE_OFF.equals(action) ? 1
                : ACTION_CANDIDATE_TOGGLE.equals(action) ? 2
                : ACTION_CANDIDATE_STATUS.equals(action) ? 3
                : ACTION_CANDIDATE_TRANSFER.equals(action) ? 4 : 0;
        int diagnosticStop = ACTION_DIAG_STOP_OFF.equals(action) ? 1
                : ACTION_DIAG_STOP_TOGGLE.equals(action) ? 2
                : ACTION_DIAG_STOP_STATUS.equals(action) ? 3
                : ACTION_DIAG_STOP_TRANSFER.equals(action) ? 4 : 0;
        boolean diagnosticStart = ACTION_DIAG_START_ONLY.equals(action);
        boolean diagnosticFetch = ACTION_DIAG_FETCH_ONLY.equals(action);
        if (manualStopRequest) {
            if (!manualTestMode || !recordingActive || gatt == null || !session.isReady()
                    || manualStopPending || stopPending || stopCompleted) {
                trace.phase("MANUAL_STOP_REJECTED", stateDetails());
                finishWithError("録音開始テストの接続がありません");
            } else {
                beginManualTestStop();
            }
            return START_NOT_STICKY;
        }
        if (!stopRequest && !ACTION_START_RECORDING.equals(action)
                && !fiveSecondRequest && !continuousRequest && !manualStartRequest && requestedCandidate == 0
                && !diagnosticStart && diagnosticStop == 0 && !diagnosticFetch)
            return START_NOT_STICKY;
        if (!stopRequest && (recordingStartPending || recordingActive || stopPending || manualStopPending
                || diagFileOnly || diagStartOnly))
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
        if (continuousRequest) {
            continuousMode = true;
            continuousStopRequested = false;
            RecorderStateStore.write(this, "STARTING", null, null);
            RecorderStateStore.setInputSource(this, "MEMOKET");
        }
        streamCaptureMode = fiveSecondRequest || continuousRequest;
        fiveSecondRecording = false;
        diagStartOnly = diagnosticStart;
        diagStopOnly = diagnosticStop != 0;
        diagFileOnly = diagnosticFetch;
        diagMetadataInFlight = false;
        candidateId = diagStopOnly ? diagnosticStop : requestedCandidate;
        manualTestMode = manualStartRequest || candidateId != 0 || diagStartOnly;
        trace.phase(diagStartOnly ? "DIAG_START_ONLY_BUTTON_PRESSED"
                : diagStopOnly ? "DIAG_STOP_ONLY_BUTTON_PRESSED_" + diagnosticStop
                : diagFileOnly ? "DIAG_FETCH_ONLY_BUTTON_PRESSED"
                : requestedCandidate != 0 ? "CANDIDATE_BUTTON_PRESSED_" + requestedCandidate
                : manualStartRequest ? "MANUAL_START_BUTTON_PRESSED"
                : fiveSecondRecording ? "FIVE_SECOND_RECORDING_REQUESTED"
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

    private synchronized void beginManualTestStop() {
        manualStopPending = true;
        trace.phase("CANDIDATE_STOP_STARTED", stateDetails());
        log("MEMOKET_STOP_CANDIDATE_STARTED", stateDetails());
        updateState("停止操作中");
        stopHandler.postDelayed(manualStopTimeout, 10_000L);
        if (candidateId == 3) {
            candidateStep = 3;
            queue(new byte[]{0x00});
        } else {
            candidateStep = 1;
            setNotification(data, false);
        }
    }

    private synchronized void finishManualTestStop() {
        if (!manualStopPending || stopCompleted) return;
        manualStopPending = false;
        stopCompleted = true;
        recordingActive = false;
        stopHandler.removeCallbacks(manualStopTimeout);
        trace.phase("CANDIDATE_STOP_SENT", stateDetails());
        log("MEMOKET_STOP_CANDIDATE_SENT", stateDetails());
        trace.completed(stateDetails());
        String sent = diagStopOnly ? "停止のみ候補" + candidateId + "送信済み（結果未確認）"
                : "停止候補" + candidateId + "送信済み（結果未確認）";
        updateState(sent);
        MemoketSettings.saveResult(this, sent);
        stopForeground(STOP_FOREGROUND_REMOVE);
        closeGatt();
        stopSelf();
    }

    private synchronized void beginStreamCapture() {
        if (stopPending || stopCompleted || !session.isReady()) return;
        long started = System.currentTimeMillis();
        MemoketSettings.recordingStarted(this, started, java.time.ZoneId.systemDefault().getId());
        MemoketRecordingWindow window = new MemoketRecordingWindow(
                started, started + (continuousMode ? CONTINUOUS_SEGMENT_MS : 0L) + 15_000L,
                java.time.ZoneId.systemDefault().getId());
        if (continuousMode) {
            continuousStartedAtMs = started;
            continuousSegmentId = java.util.UUID.randomUUID().toString();
            continuousLastDataAtMs = started;
            RecorderStateStore.segmentStarted(this, continuousSegmentId, started, started);
            stopHandler.removeCallbacks(continuousHeartbeat);
            stopHandler.postDelayed(continuousHeartbeat, 5_000L);
        }
        streamCaptureTimerStarted = false;
        streamStopRequested = false;
        streamMetadataReceived = false;
        stopRequestedAtMs = 0;
        stopSavedFiles = 0;
        inFlightMetadata = false;
        lastMetadataRequestAtMs = 0;
        lastStopActivityAtMs = started;
        lastStopDataAtMs = started;
        lastDeleteStatusProbeAtMs = 0;
        deleteStatusStartedAtMs = 0;
        midTransferStatusStep = 0;
        earlyAudio.clear();
        stopTransfer = new MemoketTransfer((name, payload, crc) -> {
            new MemoketRecordingStore(this).persist(name, payload, crc);
            stopSavedFiles++;
            trace.filePersisted(name, payload.length, crc);
        }, window::matches, 1, true, false);
        stopNotifyStep = 3;
        stopPending = true;
        recordingActive = true;
        trace.phase(continuousMode ? "GEM_SEGMENT_START_010000" : "STREAM_CAPTURE_START_010000", stateDetails());
        updateState("録音中");
        updateNotification(continuousMode ? "Gemで録音中" : "Gemで5秒録音中");
        queue(MemoketTransfer.initialCommand());
        stopHandler.removeCallbacks(stopWatchdog);
        stopHandler.postDelayed(stopWatchdog, 500L);
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
        }, latest, 1, true, true);
        lastStopActivityAtMs = System.currentTimeMillis();
        lastStopDataAtMs = lastStopActivityAtMs;
        lastMetadataRequestAtMs = 0;
        lastDeleteStatusProbeAtMs = 0;
        deleteStatusStartedAtMs = 0;
        inFlightMetadata = false;
        midTransferStatusStep = 0;
        updateState("ファイル取得中");
        MemoketSettings.recordingStopRequested(this, stopRequestedAtMs);
        // PCS Wiki 04 §34: v0.7.107 removed a second CONTROL 03 because the
        // official HCI stop operation has no such frame. Do not re-introduce it.
        // DATA CCCD is a subscription transition, NOT independent STOP proof.
        stopSequence = new MemoketOfficialStopSequence();
        stopSequence.begin();
        stopNotifyStep = 1;
        trace.phase("STOP_CAPTURE_TRANSITION_DATA_CCCD_OFF_NOT_STOP_PROOF");
        setNotification(data, false);
        stopHandler.removeCallbacks(stopWatchdog);
        stopHandler.postDelayed(stopWatchdog, 500);
    }

    @Override
    public void onDestroy() {
        stopHandler.removeCallbacks(fiveSecondStop);
        stopHandler.removeCallbacks(streamStopAtFiveSeconds);
        stopHandler.removeCallbacks(stopWatchdog);
        stopHandler.removeCallbacks(manualStopTimeout);
        stopHandler.removeCallbacks(diagMetadataProbe);
        stopHandler.removeCallbacks(diagFetchDeadline);
        stopHandler.removeCallbacks(diagStartDeadline);
        stopHandler.removeCallbacks(continuousHeartbeat);
        stopHandler.removeCallbacks(continuousRetry);
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
            if (manualStopPending && (candidateStep == 1 || candidateStep == 2)) {
                byte[] expected = candidateStep == 1
                        ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if (descriptor == null || !CCCD.equals(descriptor.getUuid())
                        || descriptor.getCharacteristic() == null
                        || !MemoketGattSync.DATA.equals(descriptor.getCharacteristic().getUuid())
                        || !Arrays.equals(descriptor.getValue(), expected)) {
                    finishWithError("Gemへの停止候補送信が一致しませんでした");
                } else if (candidateStep == 1 && (candidateId == 2 || candidateId == 4)) {
                    candidateStep = 2;
                    trace.phase("CANDIDATE_DATA_RESUBSCRIBE_REQUESTED", stateDetails());
                    setNotification(data, true);
                } else if (candidateStep == 2 && candidateId == 4) {
                    candidateStep = 3;
                    trace.phase("CANDIDATE_010000_REQUESTED", stateDetails());
                    queue(new byte[]{0x01,0x00,0x00});
                } else {
                    finishManualTestStop();
                }
                return;
            }
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
            if (stopPending && (stopNotifyStep == 1 || stopNotifyStep == 2)) {
                // Both descriptor responses are required; STOP is still
                // unverified, and re-enabling DATA can reproduce the old
                // stop-then-restart bug reported around v0.7.88.
                Boolean nextEnabled = stopSequence.onCccdWritten(descriptor.getValue());
                if (nextEnabled != null) {
                    stopNotifyStep = 2;
                    trace.phase("FILE_TRANSFER_DATA_RESUBSCRIBE_RESTART_RISK_NOTED");
                    setNotification(data, nextEnabled);
                } else {
                    stopNotifyStep = 3;
                    trace.phase("FILE_TRANSFER_010000_REQUESTED_STOP_UNVERIFIED");
                    lastStopActivityAtMs = System.currentTimeMillis();
                    queue(MemoketTransfer.initialCommand());
                }
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
                if (streamCaptureMode && stopPending && !streamCaptureTimerStarted
                        && Arrays.equals(lastCommand, MemoketTransfer.initialCommand())) {
                    streamCaptureTimerStarted = true;
                    trace.phase("STREAM_CAPTURE_5S_TIMER_STARTED", stateDetails());
                    stopHandler.postDelayed(streamStopAtFiveSeconds,
                            continuousMode ? CONTINUOUS_SEGMENT_MS : 5_000L);
                }
                if (manualStopPending && candidateStep == 3) {
                    candidateStep = 4;
                    trace.phase("CANDIDATE_CONTROL_WRITE_ACK", stateDetails());
                    stopHandler.postDelayed(() -> finishManualTestStop(), 1_500L);
                }
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
            session.allowActiveRecordingStatus(diagStopOnly || diagFileOnly || continuousMode);
            trace.phase(diagStopOnly ? "HANDSHAKE_RECORDING_ACTIVE_ALLOWED_STOP_ONLY"
                    : diagFileOnly ? "HANDSHAKE_RECORDING_ACTIVE_ALLOWED_FETCH_ONLY"
                    : "HANDSHAKE_STARTED");
            queue(session.firstCommand());
        } else {
            setNotification(next, true);
        }
    }

    private synchronized void handleNotification(UUID uuid, byte[] value) {
        if (stopCompleted) return;
        try {
            if (diagFileOnly && diagTransfer != null) {
                onDiagnosticFileNotification(uuid, value);
                return;
            }
            if (stopPending) {
                // All post-start notifications belong to the STOP/transfer
                // phase; there is no additional CONTROL 03 ACK to wait for.
                onOfficialStopNotification(uuid, value);
                return;
            }
            if (!uuid.equals(MemoketGattSync.RESPONSE)) return;
            trace.response(value, session.debugStep(),
                    candidateId == 0 ? "RECORDING_CONTROL" : "CANDIDATE_" + candidateId);
            if (!session.isReady()) {
                byte[] next = session.onResponse(value);
                trace.phase(session.debugStep());
                if (next != null) queue(next);
                if (session.isReady() && recordingStartPending) {
                    recordingStartPending = false;
                    if (diagStopOnly) {
                        trace.phase("DIAG_STOP_ONLY_NO_START_03", stateDetails());
                        beginManualTestStop();
                    } else if (diagFileOnly) {
                        trace.phase("DIAG_FETCH_ONLY_NO_START_03_NO_GEM_DELETE", stateDetails());
                        diagTransfer = new MemoketTransfer((name, bytes, crc) -> {
                            new MemoketRecordingStore(this).persist(name, bytes, crc);
                            trace.filePersisted(name, bytes.length, crc);
                            trace.phase("DIAG_FILE_CRC_VALID_SAVED_NO_ACK", stateDetails());
                        }, null, 1, true, true, false);
                        diagLastDataAtMs = System.currentTimeMillis();
                        stopHandler.postDelayed(diagFetchDeadline, 120_000L);
                        updateState("ファイル取得のみ実行中");
                        queue(MemoketTransfer.initialCommand());
                    } else if (streamCaptureMode) {
                        trace.phase("STREAM_CAPTURE_AUTHENTICATED", stateDetails());
                        beginStreamCapture();
                    } else {
                        recordingStartAwaitingAck = true;
                        trace.phase(diagStartOnly
                                ? "DIAG_START_ONLY_CONTROL_03_REQUESTED"
                                : "RECORD_START_COMMAND_REQUESTED");
                        queue(new byte[]{0x03});
                        if (diagStartOnly) stopHandler.postDelayed(diagStartDeadline, 20_000L);
                        trace.phase("OFFICIAL_03_SENT_START_EFFECT_UNVERIFIED");
                    }
                }
                return;
            }
            if (recordingStartAwaitingAck && !stopPending && value != null
                    && value.length == 2 && value[0] == 0x03 && (value[1] & 0xff) == 0xff) {
                recordingStartAwaitingAck = false;
                if (diagStartOnly) {
                    trace.phase("DIAG_START_ONLY_03FF_RECEIVED_NOT_RECORDING_PROOF", stateDetails());
                    finishDiagnostic("開始のみ：03→03ff受信（録音成立は未確認）");
                    return;
                }
                recordingActive = true;
                MemoketSettings.recordingStarted(this, System.currentTimeMillis(),
                        java.time.ZoneId.systemDefault().getId());
                trace.phase("OFFICIAL_03FF_RECEIVED_START_EFFECT_UNVERIFIED");
                updateState(manualTestMode ? "開始候補送信済み" : "録音中");
                updateNotification(manualTestMode ? "Gem接続中" : "Memoket Gem 録音中");
                log(manualTestMode ? "MEMOKET_MANUAL_START_CANDIDATE_SENT"
                        : "MEMOKET_REMOTE_RECORDING_STARTED", stateDetails());
                if (fiveSecondRecording || candidateId != 0) {
                    trace.phase("FIVE_SECOND_RECORDING_TIMER_STARTED", stateDetails());
                    stopHandler.postDelayed(fiveSecondStop, 5_000L);
                }
            }
        } catch (Exception error) {
            finishWithError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }



    /**
     * Independent file acquisition, not a recording START or STOP.
     * Never send CONTROL 05. Persist only after the complete DATA length and CRC
     * have matched the metadata. The user can verify recording duration separately.
     */
    private void onDiagnosticFileNotification(UUID uuid, byte[] value) throws Exception {
        if (value == null || diagTransfer == null) return;
        if (uuid.equals(MemoketGattSync.DATA)) {
            diagLastDataAtMs = System.currentTimeMillis();
            if ("WAIT_LIST".equals(diagTransfer.debugState())) {
                if (diagEarlyAudio.size() >= 256)
                    throw new IllegalStateException("ファイル一覧受信前のDATAが多すぎます");
                diagEarlyAudio.addLast(Arrays.copyOf(value, value.length));
            } else {
                onDiagnosticFileData(value);
            }
            return;
        }
        if (!uuid.equals(MemoketGattSync.RESPONSE) || value.length == 0) return;
        trace.response(value, "DIAG_FILE_ONLY", diagTransfer.debugState());
        if ((value[0] & 0xff) == 2) diagMetadataInFlight = false;
        byte[] next = diagTransfer.onControl(value);
        if ((value[0] & 0xff) == 1 && !"WAIT_LIST".equals(diagTransfer.debugState())) {
            while (!diagEarlyAudio.isEmpty()) onDiagnosticFileData(diagEarlyAudio.removeFirst());
        }
        if (next != null) {
            if (next.length > 0 && next[0] == 5)
                throw new IllegalStateException("診断取得で禁止した05が発行されました");
            queue(next);
        }
        if (diagTransfer.isDone()) {
            finishDiagnostic(diagTransfer.completedCount() > 0
                    ? "ファイル取得のみ：CRC検証・端末保存完了（05未送信）"
                    : "ファイル取得のみ：対象ファイルなし（05未送信）");
        } else if ((value[0] & 0xff) == 2 && diagTransfer.shouldRequestMetadata()) {
            stopHandler.removeCallbacks(diagMetadataProbe);
            stopHandler.postDelayed(diagMetadataProbe, 800L);
        }
    }

    private void onDiagnosticFileData(byte[] dataPacket) {
        trace.data(dataPacket, diagTransfer.debugState(), diagTransfer.bufferedBytes());
        byte[] next = diagTransfer.onData(dataPacket);
        if (next != null) {
            if (next.length > 0 && next[0] == 5)
                throw new IllegalStateException("診断取得で禁止した05が発行されました");
            queue(next);
        }
        stopHandler.removeCallbacks(diagMetadataProbe);
        stopHandler.postDelayed(diagMetadataProbe, 800L);
    }

    private synchronized void finishDiagnostic(String message) {
        if (stopCompleted) return;
        stopCompleted = true;
        stopHandler.removeCallbacks(diagFetchDeadline);
        stopHandler.removeCallbacks(diagStartDeadline);
        stopHandler.removeCallbacks(diagMetadataProbe);
        trace.phase("DIAG_INDEPENDENT_FINISHED", stateDetails());
        trace.completed(stateDetails());
        MemoketSettings.saveResult(this, message);
        updateState(message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        closeGatt();
        stopSelf();
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
            if (continuousMode) continuousLastDataAtMs = now;
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
            if (value.length == 2 && value[1] == 0) {
                // Oct-31 HCI also has 00 00 after transfer; no e1 follows it.
                midTransferStatusStep = 3;
                trace.phase("OFFICIAL_IDLE_0000_NO_E1");
            } else {
                MemoketTransferStatus.verifyIntermediate00(value,
                        stopTransfer == null ? "" : stopTransfer.debugFileName());
                midTransferStatusStep = 2;
                trace.phase("OFFICIAL_E1_STATUS_REQUESTED_AFTER_00_RESPONSE");
                queue(new byte[]{(byte) 0xe1});
            }
        } else if (midTransferStatusStep == 2 && opcode == 0xe1) {
            MemoketTransferStatus.verifyIntermediateE1(value);
            midTransferStatusStep = 3;
            trace.phase("OFFICIAL_E1_STATUS_RECEIVED");
        }
        if (opcode == 2) {
            inFlightMetadata = false;
            if (streamCaptureMode && value.length >= 3 && value[1] == 0 && value[2] == 1) {
                streamMetadataReceived = true;
                trace.phase("STREAM_STOP_METADATA_RECEIVED", stateDetails());
            }
        }
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
        long blocks = stopTransfer.dataBlockCount();
        if (blocks == 1 || blocks == 32 || blocks == 128 || blocks == 512
                || (blocks > 512 && blocks % 1024 == 0)) {
            // Bounded progress breadcrumbs for recordings that never
            // finalize; do not confuse receiving audio with Gem STOP.
            trace.phase("GEM_TRANSFER_PROGRESS_STOP_UNVERIFIED", stateDetails());
        }
        if (!streamCaptureMode && midTransferStatusStep == 0 && blocks >= 19) {
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
            // No absolute download time cap: only missing BLE progress is fatal.
            if (now - lastStopActivityAtMs > 60_000L) {
                throw new IllegalStateException("Gemから60秒間DATA/RESPONSEがなく、処理を終了しました");
            }
            if (inFlightMetadata && now - lastMetadataRequestAtMs > 5_000L) {
                // A lost 02 response must not permanently block metadata queries.
                inFlightMetadata = false;
                trace.phase("METADATA_RESPONSE_TIMEOUT_CONTINUE");
            }
            if (streamCaptureMode) {
                if (streamStopRequested && !streamMetadataReceived
                        && now - stopRequestedAtMs > 30_000L) {
                    throw new IllegalStateException("5秒後の確定要求にGemが応答しませんでした");
                }
                if (streamStopRequested && !inFlightMetadata
                        && stopTransfer.shouldRequestMetadata()
                        && now - lastMetadataRequestAtMs >= 1_000L
                        && !commandBusy && commands.isEmpty()) {
                    inFlightMetadata = true;
                    lastMetadataRequestAtMs = now;
                    trace.phase("STREAM_STOP_METADATA_RETRY", stateDetails());
                    queue(MemoketTransfer.metadataCommand());
                }
            } else {
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
        stopHandler.removeCallbacks(streamStopAtFiveSeconds);
        stopCompleted = true;
        stopPending = false;
        recordingActive = false;
        stopHandler.removeCallbacks(stopWatchdog);
        trace.phase("FILE_SAVED_05_ACK_IDLE_STOP_UNVERIFIED", stateDetails());
        updateState("音声1件保存済み");
        MemoketSettings.saveResult(this, "音声ファイルを1件保存しました");
        log("MEMOKET_FILE_SAVED_05_ACK_IDLE_STOP_UNVERIFIED", stateDetails());
        updateNotification("音声1件を保存しました");
        if (continuousMode) {
            continuousRetries = 0;
            RecorderStateStore.segmentFinalized(this, continuousSegmentId,
                    System.currentTimeMillis(), Math.max(0L, System.currentTimeMillis() - continuousStartedAtMs));
            if (!continuousStopRequested && RecordingIntentStore.isRequested(this)
                    && "MEMOKET".equals(MemoketSettings.source(this))) {
                stopCompleted = false;
                beginStreamCapture();
                return;
            }
            RecorderStateStore.write(this, "STOPPED", null, null);
        }
        stopHandler.removeCallbacks(continuousHeartbeat);
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
        if (continuousMode && !continuousStopRequested
                && RecordingIntentStore.isRequested(this)) {
            stopHandler.removeCallbacks(streamStopAtFiveSeconds);
            stopHandler.removeCallbacks(stopWatchdog);
            stopHandler.removeCallbacks(continuousHeartbeat);
            stopCompleted = true;
            trace.failure(message, null);
            updateState("再接続中");
            RecorderStateStore.write(this, "RECOVERING", continuousSegmentId, message);
            updateNotification("Gemを再接続しています");
            closeGatt();
            continuousRetries++;
            stopHandler.removeCallbacks(continuousRetry);
            stopHandler.postDelayed(continuousRetry, Math.min(60_000L, 5_000L * continuousRetries));
            return;
        }
        if (continuousMode) RecorderStateStore.write(this,
                continuousStopRequested ? "STOPPED" : "ERROR", null, message);
        stopHandler.removeCallbacks(continuousRetry);
        stopHandler.removeCallbacks(continuousHeartbeat);
        stopHandler.removeCallbacks(fiveSecondStop);
        stopHandler.removeCallbacks(streamStopAtFiveSeconds);
        stopHandler.removeCallbacks(stopWatchdog);
        stopHandler.removeCallbacks(manualStopTimeout);
        stopHandler.removeCallbacks(diagMetadataProbe);
        stopHandler.removeCallbacks(diagFetchDeadline);
        stopHandler.removeCallbacks(diagStartDeadline);
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
            d.put("streamCaptureMode", streamCaptureMode);
            d.put("streamCaptureTimerStarted", streamCaptureTimerStarted);
            d.put("streamStopRequested", streamStopRequested);
            d.put("streamMetadataReceived", streamMetadataReceived);
            d.put("manualTestMode", manualTestMode);
            d.put("candidateId", candidateId);
            d.put("diagStartOnly", diagStartOnly);
            d.put("diagStopOnly", diagStopOnly);
            d.put("diagFileOnly", diagFileOnly);
            d.put("diagFileBytes", diagTransfer == null ? 0 : diagTransfer.bufferedBytes());
            d.put("diagFileSaved", diagTransfer == null ? 0 : diagTransfer.completedCount());
            d.put("diagFileDeleteAcks", diagTransfer == null ? 0 : diagTransfer.deleteAcks());
            d.put("candidateStep", candidateStep);
            d.put("manualStopPending", manualStopPending);
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
            d.put("receivedBlocks", stopTransfer == null ? 0 : stopTransfer.dataBlockCount());
            d.put("receivedBytes", stopTransfer == null ? 0 : stopTransfer.bufferedBytes());
            d.put("deviceStopConfirmed", false);
            d.put("restartAfterStopUnverified", stopPending);
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
