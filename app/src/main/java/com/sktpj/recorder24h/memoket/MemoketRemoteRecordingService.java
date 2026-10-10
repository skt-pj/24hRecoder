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
    private static final int REQUESTED_MTU = 513;
    private static final int MIN_DATA_MTU = 488;
    private static final long DATA_QUIET_MS = 250;

    private final Deque<byte[]> commands = new ArrayDeque<>();
    private final MemoketSessionProtocol session = new MemoketSessionProtocol();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable metadataProbe = () -> {
        if (transfer != null && transfer.shouldRequestMetadata()) queue(MemoketTransfer.metadataCommand());
    };
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic data;
    private BluetoothGattCharacteristic control;
    private BluetoothGattCharacteristic response;
    private MemoketTransfer transfer;
    private boolean commandBusy;
    private int notifySetupStep;
    private boolean recordingStartPending;
    private boolean recordingActive;
    private boolean stopPending;
    private int stopNotifyStep;
    private byte[] lastCommand;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        transfer = new MemoketTransfer(new MemoketRecordingStore(this)::persist);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_RECORDING.equals(action)) {
            stopPending = true;
            updateState(r\sˆ-");
            if (gatt != null && data != null) beginStopSequence();
            return START_NOT_STICKY;
        }
        if (!ACTION_START_RECORDING.equals(action)) return START_NOT_STICKY;

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            finishWithError("Bluetooth˝˙)p|B∫~[ì");
            return START_NOT_STICKY;
        }
        String address = MemoketSettings.address(this);
        if (address.isEmpty()) {
            finishWithError("Memoket Ge}‹xæuåvt~{ì");
            return START_NOT_STICKY;
        }

        recordingStartPending = true;
        startForegroundCompat(buildNotification("Gem{˝ö-"));
        updateState("˝ﬁ-");

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishWithError("BluetootxLsπgY");
            return START_NOT_STICKY;
        }
        new Thread(() -> {
            try {
                JSONObject probe = MemoketConnectionProbe.observe(this, address, 3_000L);
                AppLogger.event(this, "MEMOKET_CONNECT_PREFLIGHT", probe);
                BluetoothDevice device = adapter.getRemoteDevice(address);
                BluetoothGatt connection = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (connection == null) {
                    finishWithError("Ge}{•∫wM~{ìww_");
                    return;
                }
                gatt = connection;
            } catch (Exception error) {
                finishWithError(error.getMessage() == null ? "Ge}{•∫wM~{ìww_" : error.getMessage());
            }
        }, "memoket-connect").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(metadataProbe);
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("GATT˝∫®˘¸: " + status);
                return;
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                if (!connection.requestMtu(REQUESTED_MTU)) finishWithError("Memoket MT›Åróã˚wM~{ì");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && !stopPending) {
                finishWithError("Ge}xn˝∫L7º~w_");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < MIN_DATA_MTU) {
                finishWithError("Memoket MT_Ó	uW: status=" + status + " mtu=" + mtu);
                return;
            }
            if (!connection.discoverServices()) finishWithError("GATTµ¸”˚ˇ"ó€Àw}~{ì");
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("Memoketµ¸Ûπˇ{1W: " + status);
                return;
            }
            BluetoothGattService service = connection.getService(MemoketGattSync.SERVICE);
            if (service == null) {
                finishWithError("Memoket GATTµ¸”πÕãt{ä~{ì");
                return;
            }
            data = service.getCharacteristic(MemoketGattSync.DATA);
            control = service.getCharacteristic(MemoketGattSync.CONTROL);
            response = service.getCharacteristic(MemoketGattSync.RESPONSE);
            if (data == null || control == null || response == null) {
                finishWithError("Memoket GATT characteristisN≥wft~Y");
                return;
            }
            enableInitialNotifications();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError(≤Â∫kuwW~w_: " + status);
                return;
            }
            if (stopPending && stopNotifyStep > 0) {
                continueStopSequence();
            } else {
                enableInitialNotifications();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishWithError("Gem≥˛ÛŸO·{uWw~W_: status=" + status + " command=" + hex(lastCommand));
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
            queue(session.firstCommand());
            return;
        }
        setNotification(next, true);
    }

    private void handleNotification(UUID uuid, byte[] value) {
        try {
            if (uuid.equals(MemoketGattSync.DATA)) {
                byte[] next = transfer.onData(value);
                if (next != null) queue(next);
                if (transfer.shouldRequestMetadata()) scheduleMetadata(DATA_QUIET_MS);
                return;
            }
            if (!uuid.equals(MemoketGattSync.RESPONSE)) return;

            if (!session.isReady()) {
                byte[] next = session.onResponse(value);
                if (next != null) queue(next);
                if (session.isReady() && recordingStartPending) {
                    recordingStartPending = false;
                    queue(new byte[]{0x03});
                }
                return;
            }

            if (!recordingActive && value != null && value.length == 2 &&
                    value[0] == 0x03 && (value[1] & 0xff) == 0xff) {
                recordingActive = true;
                updateState(≥2ˇ-");
                updateNotification("Memoket Gem ∑ˇ-");
                log("MEMOKET_REMOTE_RECORDING_STARTED", null);
                return;
            }

            byte[] next = transfer.onControl(value);
            if (isShortMetadataStatus(value) && transfer.shouldRequestMetadata()) {
                scheduleMetadata(300);
            }
            if (next != null) queue(next);
            if (transfer.isDone() && stopPending) {
                int count = transfer.completedCount();
                MemoketSettings.saveResult(this, count + "ˆˇ2Û”÷∑w~w_");
                log("MEMOKET_REMOTE_RECORDING_STOPPED",
                        new JSONObject().put("downloadedFiles", count));
                stopPending = false;
                recordingActive = false;
                updateState(r\b");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        } catch (Exception error) {
            finishWithError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void beginStopSequence() {
        if (!recordingActive || data == null) {
            finishWithError("Geˇ∑Û=o•∫|B∫~[ì");
            return;
        }
        stopNotifyStep = 1;
        setNotification(data, false);
    }

    private void continueStopSequence() {
        if (stopNotifyStep == 1) {
            stopNotifyStep = 2;
            setNotification(data, true);
            return;
        }
        if (stopNotifyStep == 2) {
            stopNotifyStep = 3;
            queue(MemoketTransfer.initialCommand());
        }
    }

    private void setNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (gatt == null || characteristic == null ||
                !gatt.setCharacteristicNotification(characteristic, enabled)) {
            finishWithError("Gem˜ˇ{1ww~w_");
            return;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            finishWithError("GemÂdescriptor|rä~{ì");
            return;
        }
        descriptor.setValue(enabled
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) finishWithError("GemÂdescriptorˇºkuwW~w_");
    }

    private void scheduleMetadata(long delayMs) {
        handler.removeCallbacks(metadataProbe);
        handler.postDelayed(metadataProbe, delayMs);
    }

    private static boolean isShortMetadataStatus(byte[] value) {
        return value != null && value.length <= 4 && value.length > 0 && (value[0] & 0xff) == 2;
    }

    private synchronized void queue(byte[] command) {
        commands.add(Arrays.copyOf(command, command.length));
        sendNext();
    }

    private void sendNext() {
        if (commandBusy || commands.isEmpty() || gatt == null || control == null) return;
        byte[] command = commands.removeFirst();
        lastCommand = Arrays.copyOf(command, command.length);
        commandBusy = true;
        control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        control.setValue(command);
        if (!gatt.writeCharacteristic(control)) {
            commandBusy = false;
            finishWithError("Ge}≥˛Û…”˜w}~{≥gw_ command=" + hex(command));
        }
    }

    private void updateState(String state) {
        getSharedPreferences("memoket_sync", MODE_PRIVATE).edit()
                .putString("remote_recording_state", state)
                .apply();
    }

    private void finishWithError(String message) {
        MemoketSettings.saveResult(this, "Geˇ2˜œ\uW: " + message);
        updateState("∏˘¸");
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

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private void log(String event, JSONObject details) {
        if (details == null) AppLogger.event(this, event);
        else AppLogger.event(this, event, details);
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Memoket Gem ∑Û", NotificationManager.IMPORTANCE_LOW));
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
