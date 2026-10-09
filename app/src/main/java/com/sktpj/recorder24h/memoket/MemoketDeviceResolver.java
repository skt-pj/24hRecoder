package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MemoketDeviceResolver {
    private MemoketDeviceResolver() {}

    public static BluetoothDevice resolve(Context context, String preferredAddress, long timeoutMs)
            throws Exception {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 31 &&
                app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Bluetooth scan permission required");
        }
        BluetoothManager manager = app.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth is disabled");
        }
        if (adapter.getBluetoothLeScanner() == null) {
            throw new IllegalStateException("Bluetooth LE scanner unavailable");
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<BluetoothDevice> exact = new AtomicReference<>();
        AtomicReference<BluetoothDevice> fallback = new AtomicReference<>();
        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String address = device.getAddress();
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                if (name == null || name.isEmpty()) {
                    try { name = device.getName(); } catch (SecurityException ignored) { }
                }
                boolean looksLikeMemoket = name != null && name.toLowerCase().contains("memoket");
                if (preferredAddress != null && !preferredAddress.isEmpty()
                        && preferredAddress.equalsIgnoreCase(address)) {
                    exact.compareAndSet(null, device);
                    done.countDown();
                } else if (looksLikeMemoket) {
                    fallback.compareAndSet(null, device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                done.countDown();
            }
        };

        adapter.getBluetoothLeScanner().startScan(callback);
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try { adapter.getBluetoothLeScanner().stopScan(callback); } catch (Exception ignored) { }
        }
        BluetoothDevice result = exact.get();
        if (result == null) result = fallback.get();
        if (result == null) throw new IllegalStateException("Memoket Gem not found during scan");
        return result;
    }
}
