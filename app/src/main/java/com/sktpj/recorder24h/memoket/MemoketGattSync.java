package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MemoketGattSync {
    public static final UUID SERVICE = UUID.fromString("a1b2c300-4f5c-6e7d-df23-ab12cd34ef56");
    public static final UUID DATA = UUID.fromString("a1b2c301-4f5c-6e7d-df23-ab12cd34ef56");
    public static final UUID CONTROL = UUID.fromString("a1b2c302-4f5c-6e7d-df23-ab12cd34ef56");
    public static final UUID RESPONSE = UUID.fromString("a1b2c303-4f5c-6e7d-df23-ab12cd34ef56");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long TIMEOUT_SECONDS = 120;
    private static final long DATA_QUIET_MS = 250;

    private final Context context;
    private final String address;
    private final MemoketTransfer protocol;
    private final MemoketStopTransferGuard stopTransferGuard;
    private final MemoketSessionProtocol session = new MemoketSessionProtocol();
    private final MemoketDebugTrace trace;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Deque<byte[]> commands = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable metadataProbe;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic responseCharacteristic;
    private int notifyStep = 0;
    private boolean busy;
    private volatile String error;
    private byte[] lastCommand;

    public MemoketGattSync(Context context, String address) {
        this(context, address, null);
    }

    public MemoketGattSync(Context context, String address, MemoketRecordingWindow onlyCurrentRecording) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.trace = new MemoketDebugTrace(this.context, "SYNC_WORKER");
        MemoketRecordingStore store = new MemoketRecordingStore(this.context);
        this.stopTransferGuard = onlyCurrentRecording == null ? null
                : new MemoketStopTransferGuard(onlyCurrentRecording.startMs(), onlyCurrentRecording.stopMs());
        this.protocol = new MemoketTransfer((name, payload, crc) -> {
            store.persist(name, payload, crc);
            trace.filePersisted(name, payload.length, crc);
        }, onlyCurrentRecording == null ? null : onlyCurrentRecording::matches,
                onlyCurrentRecording == null ? 50 : 1);
        this.metadataProbe = () -> {
            if (protocol.shouldRequestMetadata()) queue(MemoketTransfer.metadataCommand());
        };
    }

    public String sessionId() {
        return trace.sessionId();
    }

    public int sync() throws Exception {
        trace.phase("SYNC_PREFLIGHT");
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Bluetooth connection permission required");
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is disabled");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        try {
            trace.phase("GATT_CONNECT_REQUESTED");
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("Cannot open GATT connection");
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Memoket sync timed out");
            if (error != null) throw new IllegalStateException(error);
            int count = protocol.completedCount();
            trace.completed(new org.json.JSONObject().put("completedCount", count));
            return count;
        } finally {
            handler.removeCallbacks(metadataProbe);
            if (gatt != null) {
                try { gatt.disconnect(); } catch (Exception ignored) { }
                gatt.close();
            }
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            trace.gattConnection(status, newState);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection error " + status);
                return;
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                trace.phase("GATT_CONNECTED");
                trace.phase("SERVICE_DISCOVERY_REQUESTED");
                if (!connection.discoverServices()) fail("GATT service discovery failed");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (!protocol.isDone()) fail("Gem disconnected before transfer completed");
                else finished.countDown();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
            trace.servicesDiscovered(status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Memoket service discovery failed " + status);
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE);
            if (service == null) {
                fail("Memoket file transfer service not found");
                return;
            }
            dataCharacteristic = service.getCharacteristic(DATA);
            commandCharacteristic = service.getCharacteristic(CONTROL);
            responseCharacteristic = service.getCharacteristic(RESPONSE);
            trace.characteristicInventory(dataCharacteristic != null, commandCharacteristic != null, responseCharacteristic != null);
            if (dataCharacteristic == null || commandCharacteristic == null || responseCharacteristic == null) {
                fail("Memoket required GATT characteristics not found");
                return;
            }
            enableNextNotification(connection);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            String uuid = descriptor == null || descriptor.getCharacteristic() == null
                    ? "" : descriptor.getCharacteristic().getUuid().toString();
            trace.descriptorResult(uuid, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Memoket notification setup failed " + status);
                return;
            }
            enableNextNotification(connection);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            trace.commandWriteResult(status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Memoket command write failed status=" + status + " command=" + hex(lastCommand));
                return;
            }
            synchronized (MemoketGattSync.this) {
                busy = false;
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

    private void enableNextNotification(BluetoothGatt connection) {
        BluetoothGattCharacteristic next = notifyStep++ == 0 ? dataCharacteristic :
                notifyStep == 2 ? responseCharacteristic : null;
        if (next == null) {
            trace.phase("HANDSHAKE_STARTED");
            queue(session.firstCommand());
            return;
        }
        String label = next == dataCharacteristic ? "DATA" : "RESPONSE";
        trace.descriptorRequest(label, true, next.getUuid().toString());
        BluetoothGattDescriptor descriptor = next.getDescriptor(CCCD);
        if (descriptor == null || !connection.setCharacteristicNotification(next, true)) {
            fail("Memoket notification configuration missing");
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!connection.writeDescriptor(descriptor)) fail("Memoket CCCD write rejected");
    }

    private void handleNotification(UUID uuid, byte[] value) {
        try {
            if (uuid.equals(DATA)) {
                trace.data(value, protocol.debugState(), protocol.bufferedBytes());
                byte[] next = protocol.onData(value);
                if (stopTransferGuard != null
                        && stopTransferGuard.exceedsRecordedWindow(protocol.bufferedBytes())) {
                    throw new IllegalStateException("録音停止後にも音声が増え続けています。"
                            + "受信 " + protocol.bufferedBytes() + " bytes / 許容 "
                            + stopTransferGuard.limitBytes()
                            + " bytes。Gem側ファイルに完了・削除通知は送っていません");
                }
                trace.transferState(protocol, "DATA_RECEIVED");
                if (next != null) queue(next);
                if (protocol.shouldRequestMetadata()) scheduleMetadata(DATA_QUIET_MS);
            } else if (uuid.equals(RESPONSE)) {
                trace.response(value, session.debugStep(), protocol.debugState());
                byte[] next;
                if (!session.isReady()) {
                    next = session.onResponse(value);
                    trace.phase(session.debugStep());
                    if (next == null && session.isReady()) {
                        trace.phase("TRANSFER_LIST_REQUEST");
                        next = MemoketTransfer.initialCommand();
                    }
                } else {
                    next = protocol.onControl(value);
                    trace.transferState(protocol, "RESPONSE_RECEIVED");
                    if (isShortMetadataStatus(value) && protocol.shouldRequestMetadata()) {
                        scheduleMetadata(300);
                    }
                }
                if (next != null) queue(next);
                if (protocol.isDone()) finished.countDown();
            }
        } catch (Exception cause) {
            fail(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    private void scheduleMetadata(long delayMs) {
        trace.metadataProbeScheduled(delayMs, protocol.debugState(), protocol.bufferedBytes());
        handler.removeCallbacks(metadataProbe);
        handler.postDelayed(metadataProbe, delayMs);
    }

    private static boolean isShortMetadataStatus(byte[] value) {
        return value != null && value.length <= 4 && value.length > 0 && (value[0] & 0xff) == 2;
    }

    private synchronized void queue(byte[] bytes) {
        commands.add(Arrays.copyOf(bytes, bytes.length));
        trace.commandQueued(bytes, commands.size());
        sendNext();
    }

    private void sendNext() {
        if (busy || commands.isEmpty() || gatt == null || commandCharacteristic == null) return;
        byte[] command = commands.removeFirst();
        lastCommand = Arrays.copyOf(command, command.length);
        trace.commandWriteRequested(command, commands.size());
        busy = true;
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        commandCharacteristic.setValue(command);
        if (!gatt.writeCharacteristic(commandCharacteristic)) {
            busy = false;
            fail("Memoket command not accepted command=" + hex(command));
        }
    }

    private void fail(String message) {
        handler.removeCallbacks(metadataProbe);
        trace.transferState(protocol, "FAILURE");
        trace.failure(message, null);
        if (error == null) error = message;
        finished.countDown();
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
