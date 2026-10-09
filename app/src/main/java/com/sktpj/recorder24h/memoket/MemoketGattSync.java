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

    private final Context context;
    private final String address;
    private final MemoketTransfer protocol;
    private final MemoketSessionProtocol session = new MemoketSessionProtocol();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Deque<byte[]> commands = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic responseCharacteristic;
    private int notifyStep = 0;
    private boolean busy;
    private volatile String error;

    public MemoketGattSync(Context context, String address) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.protocol = new MemoketTransfer(new MemoketRecordingStore(this.context)::persist);
    }

    public int sync() throws Exception {
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Bluetooth connection permission required");
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is disabled");
        BluetoothDevice device = MemoketDeviceResolver.resolve(context, address, 8_000L);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("Cannot open GATT connection");
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Memoket sync timed out");
            if (error != null) throw new IllegalStateException(error);
            return protocol.completedCount();
        } finally {
            if (gatt != null) {
                try { gatt.disconnect(); } catch (Exception ignored) { }
                gatt.close();
            }
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT connection error " + status);
                return;
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                if (!connection.discoverServices()) fail("GATT service discovery failed");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                if (!protocol.isDone()) fail("Gem disconnected before transfer completed");
                else finished.countDown();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connection, int status) {
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
            if (dataCharacteristic == null || commandCharacteristic == null || responseCharacteristic == null) {
                fail("Memoket required GATT characteristics not found");
                return;
            }
            enableNextNotification(connection);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Memoket notification setup failed " + status);
                return;
            }
            enableNextNotification(connection);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Memoket command write failed " + status);
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
            queue(session.firstCommand());
            return;
        }
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
                protocol.onData(value);
            } else if (uuid.equals(RESPONSE)) {
                byte[] next;
                if (!session.isReady()) {
                    next = session.onResponse(value);
                    if (next == null && session.isReady()) next = MemoketTransfer.initialCommand();
                } else {
                    next = protocol.onControl(value);
                }
                if (next != null) queue(next);
                if (protocol.isDone()) finished.countDown();
            }
        } catch (Exception cause) {
            fail(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    private synchronized void queue(byte[] bytes) {
        commands.add(Arrays.copyOf(bytes, bytes.length));
        sendNext();
    }

    private void sendNext() {
        if (busy || commands.isEmpty() || gatt == null || commandCharacteristic == null) return;
        byte[] command = commands.removeFirst();
        busy = true;
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        commandCharacteristic.setValue(command);
        if (!gatt.writeCharacteristic(commandCharacteristic)) {
            busy = false;
            fail("Memoket command not accepted");
        }
    }

    private void fail(String message) {
        if (error == null) error = message;
        finished.countDown();
    }
}
