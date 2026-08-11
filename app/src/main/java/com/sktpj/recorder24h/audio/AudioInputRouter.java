package com.sktpj.recorder24h.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves the persisted recording-input preference to a currently connected input device. */
public final class AudioInputRouter {
    private AudioInputRouter() {
    }

    public static final class DeviceOption {
        public final String key;
        public final String label;
        public final int type;
        public final int id;
        public final boolean bluetooth;
        public final boolean builtIn;

        DeviceOption(AudioDeviceInfo device) {
            key = deviceKey(device);
            label = deviceLabel(device);
            type = device.getType();
            id = device.getId();
            bluetooth = isBluetoothMic(device);
            builtIn = device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC;
        }
    }

    public static final class ApplyResult {
        public final long settingsUpdatedAtMs;
        public final String preferredKey;
        public final String preferredLabel;
        public final String fallbackReason;
        public final boolean accepted;

        ApplyResult(long settingsUpdatedAtMs, AudioDeviceInfo preferred,
                    String fallbackReason, boolean accepted) {
            this.settingsUpdatedAtMs = settingsUpdatedAtMs;
            this.preferredKey = preferred == null ? "system-default" : deviceKey(preferred);
            this.preferredLabel = preferred == null ? "システム既定" : deviceLabel(preferred);
            this.fallbackReason = fallbackReason;
            this.accepted = accepted;
        }
    }

    private static final class Selection {
        final AudioDeviceInfo device;
        final AudioDeviceInfo builtIn;
        final String fallbackReason;

        Selection(AudioDeviceInfo device, AudioDeviceInfo builtIn, String fallbackReason) {
            this.device = device;
            this.builtIn = builtIn;
            this.fallbackReason = fallbackReason;
        }
    }

    public static List<DeviceOption> availableBluetoothMics(Context context) {
        List<AudioDeviceInfo> devices = bluetoothMics(context);
        List<DeviceOption> options = new ArrayList<>();
        for (AudioDeviceInfo device : devices) options.add(new DeviceOption(device));
        return options;
    }

    public static DeviceOption builtInMic(Context context) {
        AudioDeviceInfo device = findBuiltIn(getInputs(context));
        return device == null ? null : new DeviceOption(device);
    }

    public static ApplyResult applyPreferredInput(Context context, AudioRecord record, String trigger) {
        AudioInputSettingsStore.Settings settings = AudioInputSettingsStore.read(context);
        Selection selection = resolve(context, settings);
        AudioDeviceInfo preferred = selection.device;
        String fallbackReason = selection.fallbackReason;
        boolean accepted;

        try {
            accepted = record.setPreferredDevice(preferred);
        } catch (RuntimeException error) {
            accepted = false;
            fallbackReason = appendReason(fallbackReason,
                    "PREFERRED_DEVICE_EXCEPTION_" + error.getClass().getSimpleName());
        }

        if (!accepted && preferred != null && preferred != selection.builtIn) {
            preferred = selection.builtIn;
            fallbackReason = appendReason(fallbackReason, "PREFERRED_BT_REJECTED");
            try {
                accepted = record.setPreferredDevice(preferred);
            } catch (RuntimeException error) {
                accepted = false;
                fallbackReason = appendReason(fallbackReason,
                        "BUILTIN_FALLBACK_EXCEPTION_" + error.getClass().getSimpleName());
            }
        }

        if (!accepted) {
            preferred = null;
            try {
                // Null clears the explicit preference and leaves Android to choose a valid input.
                accepted = record.setPreferredDevice(null);
            } catch (RuntimeException ignored) {
                accepted = false;
            }
            fallbackReason = appendReason(fallbackReason, "SYSTEM_DEFAULT_FALLBACK");
        }

        AudioInputRouteStateStore.writeRequested(
                context, settings, preferred, accepted, fallbackReason);
        logRequested(context, settings, preferred, accepted, fallbackReason, trigger);

        try {
            AudioDeviceInfo routed = record.getRoutedDevice();
            if (routed != null) AudioInputRouteStateStore.writeActual(context, routed);
        } catch (RuntimeException ignored) {
        }

        return new ApplyResult(settings.updatedAtMs, preferred, fallbackReason, accepted);
    }

    public static void recordActualInput(Context context, AudioDeviceInfo actual, String trigger) {
        AudioInputRouteStateStore.writeActual(context, actual);
        try {
            JSONObject d = new JSONObject();
            d.put("trigger", trigger);
            putDevice(d, "actual", actual);
            AppLogger.event(context, "AUDIO_INPUT_ROUTE_ACTUAL", d);
        } catch (Exception ignored) {
        }
    }

    public static String deviceKey(AudioDeviceInfo device) {
        if (device == null) return "system-default";
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return AudioInputSettingsStore.BUILTIN_KEY;
        }
        String address;
        try {
            address = device.getAddress();
        } catch (RuntimeException ignored) {
            address = "";
        }
        if (address == null) address = "";
        String name = String.valueOf(device.getProductName());
        return device.getType() + "|" + address + "|" + name;
    }

    public static String deviceLabel(AudioDeviceInfo device) {
        if (device == null) return "システム既定";
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "端末マイク";
        String name = String.valueOf(device.getProductName()).trim();
        if (name.isEmpty() || "null".equalsIgnoreCase(name)) {
            return isBluetoothMic(device) ? "Bluetoothマイク" : "外部マイク";
        }
        return name;
    }

    public static boolean isBluetoothMic(AudioDeviceInfo device) {
        if (device == null || !device.isSource()) return false;
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
    }

    private static Selection resolve(Context context, AudioInputSettingsStore.Settings settings) {
        List<AudioDeviceInfo> inputs = getInputs(context);
        AudioDeviceInfo builtIn = findBuiltIn(inputs);
        List<AudioDeviceInfo> bluetooth = filterBluetooth(inputs);

        if (settings.isAuto()) {
            if (!bluetooth.isEmpty()) return new Selection(bluetooth.get(0), builtIn, null);
            return new Selection(builtIn, builtIn, "AUTO_BT_NOT_AVAILABLE");
        }

        if (AudioInputSettingsStore.BUILTIN_KEY.equals(settings.manualDeviceKey)) {
            return new Selection(builtIn, builtIn, null);
        }

        for (AudioDeviceInfo device : bluetooth) {
            if (settings.manualDeviceKey.equals(deviceKey(device))) {
                return new Selection(device, builtIn, null);
            }
        }
        return new Selection(builtIn, builtIn, "MANUAL_BT_NOT_AVAILABLE");
    }

    private static List<AudioDeviceInfo> bluetoothMics(Context context) {
        return filterBluetooth(getInputs(context));
    }

    private static List<AudioDeviceInfo> filterBluetooth(List<AudioDeviceInfo> inputs) {
        List<AudioDeviceInfo> bluetooth = new ArrayList<>();
        for (AudioDeviceInfo device : inputs) {
            if (isBluetoothMic(device)) bluetooth.add(device);
        }
        bluetooth.sort(Comparator
                .comparingInt(AudioInputRouter::bluetoothPriority)
                .thenComparing(AudioInputRouter::deviceLabel)
                .thenComparingInt(AudioDeviceInfo::getId));
        return bluetooth;
    }

    private static int bluetoothPriority(AudioDeviceInfo device) {
        return device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET ? 0 : 1;
    }

    private static AudioDeviceInfo findBuiltIn(List<AudioDeviceInfo> inputs) {
        for (AudioDeviceInfo device : inputs) {
            if (device.isSource() && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return device;
        }
        return null;
    }

    private static List<AudioDeviceInfo> getInputs(Context context) {
        List<AudioDeviceInfo> inputs = new ArrayList<>();
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) return inputs;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return inputs;
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.isSource()) inputs.add(device);
        }
        return inputs;
    }

    private static String appendReason(String existing, String addition) {
        if (existing == null || existing.isEmpty()) return addition;
        return existing + "," + addition;
    }

    private static void logRequested(Context context,
                                     AudioInputSettingsStore.Settings settings,
                                     AudioDeviceInfo preferred,
                                     boolean accepted,
                                     String fallbackReason,
                                     String trigger) {
        try {
            JSONObject d = new JSONObject();
            d.put("trigger", trigger);
            d.put("mode", settings.mode);
            d.put("manualDeviceKey", settings.manualDeviceKey);
            d.put("manualDeviceLabel", settings.manualDeviceLabel);
            d.put("preferredAccepted", accepted);
            d.put("fallbackReason", fallbackReason == null ? JSONObject.NULL : fallbackReason);
            putDevice(d, "preferred", preferred);
            JSONArray available = new JSONArray();
            for (DeviceOption option : availableBluetoothMics(context)) {
                available.put(new JSONObject()
                        .put("key", option.key)
                        .put("label", option.label)
                        .put("type", option.type)
                        .put("id", option.id));
            }
            d.put("availableBluetoothInputs", available);
            AppLogger.event(context, "AUDIO_INPUT_ROUTE_REQUESTED", d);
        } catch (Exception ignored) {
        }
    }

    private static void putDevice(JSONObject d, String prefix, AudioDeviceInfo device) throws Exception {
        if (device == null) {
            d.put(prefix + "Key", JSONObject.NULL);
            d.put(prefix + "Label", JSONObject.NULL);
            d.put(prefix + "Type", JSONObject.NULL);
            d.put(prefix + "Id", JSONObject.NULL);
            return;
        }
        d.put(prefix + "Key", deviceKey(device));
        d.put(prefix + "Label", deviceLabel(device));
        d.put(prefix + "Type", device.getType());
        d.put(prefix + "Id", device.getId());
    }
}
