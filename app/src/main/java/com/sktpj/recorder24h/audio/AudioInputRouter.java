package com.sktpj.recorder24h.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Build;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves and verifies the persisted recording-input preference against the actual AudioRecord route. */
public final class AudioInputRouter {
    private AudioInputRouter() {
    }

    // Android's communication-routing guide explicitly allows up to 30 seconds for route activation.
    private static final long BT_ROUTE_TIMEOUT_MS = 30_000L;
    private static volatile WeakReference<AudioRecord> activeRecordRef = new WeakReference<>(null);

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

        ApplyResult(long settingsUpdatedAtMs,
                    AudioDeviceInfo preferred,
                    String fallbackReason,
                    boolean accepted) {
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

    private static final class CommunicationResult {
        final AudioDeviceInfo requestedDevice;
        final AudioDeviceInfo currentDevice;
        final boolean accepted;
        final int attempts;
        final String failureReason;

        CommunicationResult(AudioDeviceInfo requestedDevice,
                            AudioDeviceInfo currentDevice,
                            boolean accepted,
                            int attempts,
                            String failureReason) {
            this.requestedDevice = requestedDevice;
            this.currentDevice = currentDevice;
            this.accepted = accepted;
            this.attempts = attempts;
            this.failureReason = failureReason;
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
        activeRecordRef = new WeakReference<>(record);
        AudioInputSettingsStore.Settings settings = AudioInputSettingsStore.read(context);
        Selection selection = resolve(context, settings);
        AudioDeviceInfo desired = selection.device;
        AudioDeviceInfo preferred = desired;
        String fallbackReason = selection.fallbackReason;
        boolean communicationRequired = isBluetoothMic(desired);
        boolean communicationAccepted = !communicationRequired;
        AudioDeviceInfo communicationDevice = null;
        int communicationAttempts = 0;
        long now = System.currentTimeMillis();
        String routeStatus = communicationRequired ? "PENDING" : "REQUESTED";

        if (communicationRequired) {
            CommunicationResult communication = requestCommunicationRoute(context, desired, trigger);
            communicationDevice = communication.requestedDevice;
            communicationAccepted = communication.accepted;
            communicationAttempts = communication.attempts;
            fallbackReason = appendReason(fallbackReason, communication.failureReason);
            if (!communicationAccepted) {
                preferred = selection.builtIn;
                routeStatus = "FALLBACK";
                fallbackReason = appendReason(fallbackReason, "BT_COMMUNICATION_ROUTE_REJECTED");
                clearCommunicationRoute(context, "COMMUNICATION_REJECTED");
            }
        } else {
            clearCommunicationRoute(context, "NON_BLUETOOTH_ROUTE");
        }

        boolean preferredAccepted = setPreferred(record, preferred);
        if (!preferredAccepted && preferred != null && preferred != selection.builtIn) {
            clearCommunicationRoute(context, "PREFERRED_BT_REJECTED");
            preferred = selection.builtIn;
            routeStatus = "FALLBACK";
            fallbackReason = appendReason(fallbackReason, "PREFERRED_BT_REJECTED");
            preferredAccepted = setPreferred(record, preferred);
        }

        if (!preferredAccepted) {
            preferred = null;
            preferredAccepted = setPreferred(record, null);
            routeStatus = "FALLBACK";
            fallbackReason = appendReason(fallbackReason, "SYSTEM_DEFAULT_FALLBACK");
        }

        AudioInputRouteStateStore.writeRequested(
                context,
                settings,
                desired,
                preferred,
                preferredAccepted,
                communicationDevice,
                communicationRequired,
                communicationAccepted,
                fallbackReason,
                now,
                now,
                communicationAttempts,
                routeStatus);
        logRequested(context,
                settings,
                desired,
                preferred,
                preferredAccepted,
                communicationDevice,
                communicationRequired,
                communicationAccepted,
                communicationAttempts,
                fallbackReason,
                trigger);

        try {
            AudioDeviceInfo routed = record.getRoutedDevice();
            if (routed != null) recordActualInput(context, routed, "APPLY_RESULT");
        } catch (RuntimeException ignored) {
        }

        return new ApplyResult(settings.updatedAtMs, preferred, fallbackReason, preferredAccepted);
    }

    public static void recordActualInput(Context context, AudioDeviceInfo actual, String trigger) {
        if (actual == null) return;
        String actualKey = deviceKey(actual);
        JSONObject before = AudioInputRouteStateStore.read(context);
        String previousKey = before.optString("actualDeviceKey", "");
        if (!("HEARTBEAT".equals(trigger) && actualKey.equals(previousKey))) {
            AudioInputRouteStateStore.writeActual(context, actual);
            try {
                JSONObject d = new JSONObject();
                d.put("trigger", trigger);
                d.put("changed", !actualKey.equals(previousKey));
                putDevice(d, "actual", actual);
                AppLogger.event(context, "AUDIO_INPUT_ROUTE_ACTUAL", d);
            } catch (Exception ignored) {
            }
        }
        verifyActualRoute(context, actual, trigger);
    }

    /** Clear a communication-route request when the recorder session ends. */
    public static void releaseCommunicationRoute(Context context, String trigger) {
        clearCommunicationRoute(context, trigger);
        activeRecordRef = new WeakReference<>(null);
    }

    private static void verifyActualRoute(Context context, AudioDeviceInfo actual, String trigger) {
        JSONObject state = AudioInputRouteStateStore.read(context);
        String desiredKey = state.optString("desiredDeviceKey", "");
        if (desiredKey.isEmpty() || "null".equals(desiredKey)) return;

        String actualKey = deviceKey(actual);
        String routeStatus = state.optString("routeStatus", "");
        boolean desiredBluetooth = state.optBoolean("desiredBluetooth", false);

        if (desiredKey.equals(actualKey)) {
            if (!"VERIFIED".equals(routeStatus)) {
                long now = System.currentTimeMillis();
                AudioInputRouteStateStore.markStatus(context, "VERIFIED", null, now);
                try {
                    JSONObject d = new JSONObject();
                    d.put("trigger", trigger);
                    d.put("elapsedMs", Math.max(0L,
                            now - state.optLong("routeRequestStartedAtMs", now)));
                    putDevice(d, "actual", actual);
                    AppLogger.event(context, "AUDIO_INPUT_ROUTE_VERIFIED", d);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        if (!desiredBluetooth || "FALLBACK".equals(routeStatus)) return;

        long now = System.currentTimeMillis();
        long startedAt = state.optLong("routeRequestStartedAtMs", now);
        long ageMs = Math.max(0L, now - startedAt);
        if (ageMs >= BT_ROUTE_TIMEOUT_MS) {
            fallbackBluetoothRoute(context, state, actual, "BT_ROUTE_TIMEOUT");
            return;
        }

        // setCommunicationDevice() returning true only means Android accepted the request.
        // Keep the route pending until getRoutedDevice/AudioRecordingConfiguration proves the source.
        if (!"HEARTBEAT".equals(trigger)) {
            try {
                JSONObject d = new JSONObject();
                d.put("trigger", trigger);
                d.put("elapsedMs", ageMs);
                d.put("communicationAttempts", state.optInt("routeRetryCount", 0));
                d.put("desiredKey", desiredKey);
                putDevice(d, "actual", actual);
                AppLogger.event(context, "AUDIO_INPUT_ROUTE_PENDING", d);
            } catch (Exception ignored) {
            }
        }
    }

    private static void fallbackBluetoothRoute(Context context,
                                               JSONObject state,
                                               AudioDeviceInfo actual,
                                               String reason) {
        if ("FALLBACK".equals(state.optString("routeStatus", ""))) return;
        AudioRecord record = activeRecordRef.get();
        AudioDeviceInfo builtIn = findBuiltIn(getInputs(context));
        clearCommunicationRoute(context, "BT_ROUTE_FALLBACK");
        boolean preferredAccepted = record != null && setPreferred(record, builtIn);
        AudioDeviceInfo preferred = builtIn;
        if (!preferredAccepted && record != null) {
            preferred = null;
            preferredAccepted = setPreferred(record, null);
            reason = appendReason(reason, "SYSTEM_DEFAULT_FALLBACK");
        }

        long now = System.currentTimeMillis();
        AudioInputRouteStateStore.updateAttempt(
                context,
                preferred,
                preferredAccepted,
                null,
                false,
                now,
                Math.max(1, state.optInt("routeRetryCount", 0)),
                "FALLBACK",
                reason);
        try {
            JSONObject d = new JSONObject();
            d.put("reason", reason);
            d.put("elapsedMs", Math.max(0L,
                    now - state.optLong("routeRequestStartedAtMs", now)));
            putDevice(d, "actual", actual);
            putDevice(d, "fallback", preferred);
            AppLogger.event(context, "AUDIO_INPUT_ROUTE_FALLBACK", d);
        } catch (Exception ignored) {
        }
    }

    private static CommunicationResult requestCommunicationRoute(Context context,
                                                                 AudioDeviceInfo bluetoothSource,
                                                                 String trigger) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) {
            return new CommunicationResult(null, null, false, 0, "AUDIO_MANAGER_UNAVAILABLE");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo requested = findMatchingCommunicationDevice(manager, bluetoothSource);
            if (requested == null) {
                AudioDeviceInfo current = manager.getCommunicationDevice();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        null,
                        current,
                        false,
                        0,
                        "COMMUNICATION_DEVICE_NOT_AVAILABLE",
                        manager);
                return new CommunicationResult(null,
                        current,
                        false,
                        0,
                        "COMMUNICATION_DEVICE_NOT_AVAILABLE");
            }

            try {
                boolean accepted = manager.setCommunicationDevice(requested);
                AudioDeviceInfo current = manager.getCommunicationDevice();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        requested,
                        current,
                        accepted,
                        1,
                        accepted ? null : "SET_COMMUNICATION_DEVICE_REJECTED",
                        manager);
                if (accepted) {
                    return new CommunicationResult(requested, current, true, 1, null);
                }

                // Android's guide says that on an error, clear the communication device and retry.
                manager.clearCommunicationDevice();
                boolean retryAccepted = manager.setCommunicationDevice(requested);
                AudioDeviceInfo retryCurrent = manager.getCommunicationDevice();
                String failure = retryAccepted ? null : "SET_COMMUNICATION_DEVICE_RETRY_REJECTED";
                logCommunicationRequest(context,
                        trigger + "_RETRY_AFTER_ERROR",
                        bluetoothSource,
                        requested,
                        retryCurrent,
                        retryAccepted,
                        2,
                        failure,
                        manager);
                return new CommunicationResult(requested,
                        retryCurrent,
                        retryAccepted,
                        2,
                        failure);
            } catch (SecurityException security) {
                String failure = "SET_COMMUNICATION_DEVICE_SECURITY_"
                        + security.getClass().getSimpleName();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        requested,
                        null,
                        false,
                        1,
                        failure,
                        manager);
                return new CommunicationResult(requested, null, false, 1, failure);
            } catch (RuntimeException error) {
                String failure = "SET_COMMUNICATION_DEVICE_EXCEPTION_"
                        + error.getClass().getSimpleName();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        requested,
                        null,
                        false,
                        1,
                        failure,
                        manager);
                return new CommunicationResult(requested, null, false, 1, failure);
            }
        }

        if (bluetoothSource.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            try {
                manager.startBluetoothSco();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        null,
                        null,
                        true,
                        1,
                        null,
                        manager);
                return new CommunicationResult(null, null, true, 1, null);
            } catch (RuntimeException error) {
                String failure = "START_BLUETOOTH_SCO_EXCEPTION_"
                        + error.getClass().getSimpleName();
                logCommunicationRequest(context,
                        trigger,
                        bluetoothSource,
                        null,
                        null,
                        false,
                        1,
                        failure,
                        manager);
                return new CommunicationResult(null, null, false, 1, failure);
            }
        }
        return new CommunicationResult(null,
                null,
                false,
                0,
                "BT_COMMUNICATION_API_UNAVAILABLE");
    }

    private static void clearCommunicationRoute(Context context, String trigger) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo before = manager.getCommunicationDevice();
                manager.clearCommunicationDevice();
                if (before != null && isBluetoothAudioDevice(before)) {
                    try {
                        JSONObject d = new JSONObject();
                        d.put("trigger", trigger);
                        putDevice(d, "previousCommunication", before);
                        AppLogger.event(context, "AUDIO_INPUT_COMMUNICATION_ROUTE_CLEARED", d);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                manager.stopBluetoothSco();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static AudioDeviceInfo findMatchingCommunicationDevice(AudioManager manager,
                                                                    AudioDeviceInfo source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        List<AudioDeviceInfo> available = manager.getAvailableCommunicationDevices();
        if (available == null || available.isEmpty()) return null;

        String sourceAddress = safeAddress(source);
        String sourceName = deviceLabel(source);
        AudioDeviceInfo typeOnly = null;
        AudioDeviceInfo nameMatch = null;
        for (AudioDeviceInfo device : available) {
            if (device == null || !device.isSink() || device.getType() != source.getType()) continue;
            if (typeOnly == null) typeOnly = device;
            if (sourceName.equals(deviceLabel(device)) && nameMatch == null) nameMatch = device;
            String candidateAddress = safeAddress(device);
            if (!sourceAddress.isEmpty() && sourceAddress.equals(candidateAddress)) return device;
        }
        if (nameMatch != null) return nameMatch;
        return typeOnly;
    }

    private static boolean setPreferred(AudioRecord record, AudioDeviceInfo preferred) {
        if (record == null) return false;
        try {
            return record.setPreferredDevice(preferred);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static String deviceKey(AudioDeviceInfo device) {
        if (device == null) return "system-default";
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return AudioInputSettingsStore.BUILTIN_KEY;
        }
        String address = safeAddress(device);
        String name = String.valueOf(device.getProductName());
        return device.getType() + "|" + address + "|" + name;
    }

    public static String deviceLabel(AudioDeviceInfo device) {
        if (device == null) return "システム既定";
        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "端末マイク";
        String name = String.valueOf(device.getProductName()).trim();
        if (name.isEmpty() || "null".equalsIgnoreCase(name)) {
            return isBluetoothAudioDevice(device) ? "Bluetoothマイク" : "外部マイク";
        }
        return name;
    }

    public static boolean isBluetoothMic(AudioDeviceInfo device) {
        return device != null && device.isSource() && isBluetoothAudioDevice(device);
    }

    public static boolean isBluetoothAudioDevice(AudioDeviceInfo device) {
        if (device == null) return false;
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && type == AudioDeviceInfo.TYPE_BLE_HEADSET);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) return 0;
        return 1;
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

    private static String safeAddress(AudioDeviceInfo device) {
        if (device == null) return "";
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String appendReason(String existing, String addition) {
        if (addition == null || addition.isEmpty()) return existing;
        if (existing == null || existing.isEmpty()) return addition;
        if (existing.contains(addition)) return existing;
        return existing + "," + addition;
    }

    private static void logRequested(Context context,
                                     AudioInputSettingsStore.Settings settings,
                                     AudioDeviceInfo desired,
                                     AudioDeviceInfo preferred,
                                     boolean preferredAccepted,
                                     AudioDeviceInfo communicationDevice,
                                     boolean communicationRequired,
                                     boolean communicationAccepted,
                                     int communicationAttempts,
                                     String fallbackReason,
                                     String trigger) {
        try {
            JSONObject d = new JSONObject();
            d.put("trigger", trigger);
            d.put("mode", settings.mode);
            d.put("manualDeviceKey", settings.manualDeviceKey);
            d.put("manualDeviceLabel", settings.manualDeviceLabel);
            d.put("preferredAccepted", preferredAccepted);
            d.put("communicationRequired", communicationRequired);
            d.put("communicationAccepted", communicationAccepted);
            d.put("communicationAttempts", communicationAttempts);
            d.put("fallbackReason", fallbackReason == null ? JSONObject.NULL : fallbackReason);
            putDevice(d, "desired", desired);
            putDevice(d, "preferred", preferred);
            putDevice(d, "communication", communicationDevice);
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

    private static void logCommunicationRequest(Context context,
                                                String trigger,
                                                AudioDeviceInfo source,
                                                AudioDeviceInfo requested,
                                                AudioDeviceInfo current,
                                                boolean accepted,
                                                int attempt,
                                                String failureReason,
                                                AudioManager manager) {
        try {
            JSONObject d = new JSONObject();
            d.put("trigger", trigger);
            d.put("accepted", accepted);
            d.put("attempt", attempt);
            d.put("failureReason", failureReason == null ? JSONObject.NULL : failureReason);
            putDevice(d, "source", source);
            putDevice(d, "requestedCommunication", requested);
            putDevice(d, "currentCommunication", current);
            JSONArray available = new JSONArray();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager != null) {
                for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
                    JSONObject row = new JSONObject();
                    putDevice(row, "device", device);
                    row.put("sink", device != null && device.isSink());
                    row.put("source", device != null && device.isSource());
                    available.put(row);
                }
            }
            d.put("availableCommunicationDevices", available);
            AppLogger.event(context, "AUDIO_INPUT_COMMUNICATION_ROUTE_REQUESTED", d);
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
