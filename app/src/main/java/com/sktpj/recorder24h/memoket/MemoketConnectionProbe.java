package com.sktpj.recorder24h.memoket;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class MemoketConnectionProbe {
    private MemoketConnectionProbe() {}

    public static JSONObject observe(Context context, String selectedAddress, long durationMs) throws Exception {
        JSONObject out = new JSONObject();
        out.put("selectedAddress", selectedAddress);
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            out.put("scanAvailable", false);
            out.put("reason", "BLUETOOTH_SCAN permission missing");
            return out;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            out.put("scanAvailable", false);
            out.put("reason", "BLE scanner unavailable");
            return out;
        }

        Map<String, JSONObject> seen = new LinkedHashMap<>();
        CountDownLatch finished = new CountDownLatch(1);
        ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                try {
                    String address = result.getDevice().getAddress();
                    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                    if (name == null) {
                        try { name = result.getDevice().getName(); } catch (SecurityException ignored) { }
                    }
                    if (!address.equalsIgnoreCase(selectedAddress) &&
                            (name == null || !name.toLowerCase().contains("memoket"))) return;
                    JSONObject row = new JSONObject();
                    row.put("address", address);
                    row.put("name", name == null ? JSONObject.NULL : name);
                    row.put("rssi", result.getRssi());
                    if (Build.VERSION.SDK_INT >= 26) row.put("connectable", result.isConnectable());
                    seen.put(address, row);
                } catch (Exception ignored) { }
            }
            @Override public void onScanFailed(int errorCode) {
                try { out.put("scanError", errorCode); } catch (Exception ignored) { }
                finished.countDown();
            }
        };
        adapter.getBluetoothLeScanner().startScan(callback);
        try {
            finished.await(durationMs, TimeUnit.MILLISECONDS);
        } finally {
            try { adapter.getBluetoothLeScanner().stopScan(callback); } catch (Exception ignored) { }
        }
        JSONArray rows = new JSONArray();
        boolean selectedSeen = false;
        boolean selectedConnectable = false;
        for (JSONObject row : seen.values()) {
            rows.put(row);
            if (selectedAddress.equalsIgnoreCase(row.optString("address"))) {
                selectedSeen = true;
                selectedConnectable = row.optBoolean("connectable", true);
            }
        }
        out.put("scanAvailable", true);
        out.put("selectedSeen", selectedSeen);
        out.put("selectedConnectable", selectedConnectable);
        out.put("seen", rows);
        return out;
    }
}
