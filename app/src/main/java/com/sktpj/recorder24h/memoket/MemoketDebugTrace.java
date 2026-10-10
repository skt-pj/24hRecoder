package com.sktpj.recorder24h.memoket;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONObject;

import java.util.UUID;

public final class MemoketDebugTrace {
    private final Context context;
    private final String sessionId;
    private final String route;
    private final long startedElapsedMs;

    private volatile String phase = "CREATED";
    private volatile String lastCommandHex = "";
    private volatile String lastResponseHex = "";
    private volatile long dataBlocks;
    private volatile long dataBytes;
    private volatile long lastDataSequence = -1;
    private volatile String lastSessionStep = "";
    private volatile String lastTransferState = "";
    private volatile String lastFileName = "";
    private volatile int lastBufferedBytes;
    private volatile long lastExpectedSize = -1;
    private volatile String lastExpectedCrcHex = "";
    private volatile int lastGattStatus = Integer.MIN_VALUE;
    private volatile int lastGattState = Integer.MIN_VALUE;
    private volatile int lastDescriptorStatus = Integer.MIN_VALUE;
    private volatile long lastCommandRequestedElapsedMs = -1;
    private volatile long lastDescriptorRequestedElapsedMs = -1;

    public MemoketDebugTrace(Context context, String route) {
        this.context = context.getApplicationContext();
        this.sessionId = UUID.randomUUID().toString();
        this.route = route == null ? "unknown" : route;
        this.startedElapsedMs = SystemClock.elapsedRealtime();

        JSONObject details = base();
        put(details, "versionName", versionName(this.context));
        put(details, "versionCode", versionCode(this.context));
        put(details, "manufacturer", Build.MANUFACTURER);
        put(details, "model", Build.MODEL);
        put(details, "device", Build.DEVICE);
        put(details, "sdkInt", Build.VERSION.SDK_INT);
        put(details, "androidRelease", Build.VERSION.RELEASE);
        AppLogger.diagnostic(this.context, "MEMOKET_TRACE_SESSION_STARTED", details);
    }

    public String sessionId() {
        return sessionId;
    }

    public void phase(String value) {
        phase(value, null);
    }

    public void phase(String value, JSONObject extra) {
        if (value != null && !value.isEmpty()) phase = value;
        AppLogger.diagnostic(context, "MEMOKET_TRACE_PHASE", merge(base(), extra));
    }

    public void gattConnection(int status, int newState) {
        lastGattStatus = status;
        lastGattState = newState;
        JSONObject d = base();
        put(d, "status", status);
        put(d, "newState", newState);
        AppLogger.diagnostic(context, "MEMOKET_GATT_CONNECTION_STATE", d);
    }

    public void servicesDiscovered(int status) {
        JSONObject d = base();
        put(d, "status", status);
        AppLogger.diagnostic(context, "MEMOKET_GATT_SERVICES_DISCOVERED", d);
    }

    public void characteristicInventory(boolean hasData, boolean hasControl, boolean hasResponse) {
        JSONObject d = base();
        put(d, "dataAvailable", hasData);
        put(d, "controlAvailable", hasControl);
        put(d, "responseAvailable", hasResponse);
        AppLogger.diagnostic(context, "MEMOKET_GATT_CHARACTERISTICS", d);
    }

    public void descriptorRequest(String label, boolean enabled, String characteristicUuid) {
        lastDescriptorRequestedElapsedMs = SystemClock.elapsedRealtime();
        JSONObject d = base();
        put(d, "label", label);
        put(d, "enabled", enabled);
        put(d, "characteristicUuid", characteristicUuid);
        AppLogger.diagnostic(context, "MEMOKET_GATT_CCCD_WRITE_REQUESTED", d);
    }

    public void descriptorResult(String characteristicUuid, int status) {
        lastDescriptorStatus = status;
        JSONObject d = base();
        put(d, "characteristicUuid", characteristicUuid);
        put(d, "status", status);
        put(d, "writeLatencyMs", lastDescriptorRequestedElapsedMs < 0 ? -1
                : SystemClock.elapsedRealtime() - lastDescriptorRequestedElapsedMs);
        AppLogger.diagnostic(context, "MEMOKET_GATT_CCCD_WRITE_RESULT", d);
    }

    public void commandQueued(byte[] command, int queueDepth) {
        JSONObject d = base();
        put(d, "commandHex", hex(command));
        put(d, "commandName", commandName(command));
        put(d, "queueDepth", queueDepth);
        AppLogger.diagnostic(context, "MEMOKET_CONTROL_QUEUED", d);
    }

    public void commandWriteRequested(byte[] command, int remainingQueueDepth) {
        lastCommandHex = hex(command);
        lastCommandRequestedElapsedMs = SystemClock.elapsedRealtime();
        JSONObject d = base();
        put(d, "commandHex", lastCommandHex);
        put(d, "commandName", commandName(command));
        put(d, "remainingQueueDepth", remainingQueueDepth);
        AppLogger.diagnostic(context, "MEMOKET_CONTROL_WRITE_REQUESTED", d);
    }

    public void commandWriteResult(int status) {
        JSONObject d = base();
        put(d, "commandHex", lastCommandHex);
        put(d, "status", status);
        put(d, "writeLatencyMs", lastCommandRequestedElapsedMs < 0 ? -1
                : SystemClock.elapsedRealtime() - lastCommandRequestedElapsedMs);
        AppLogger.diagnostic(context, "MEMOKET_CONTROL_WRITE_RESULT", d);
    }

    public void response(byte[] value, String sessionStep, String transferState) {
        lastResponseHex = hex(value);
        lastSessionStep = sessionStep == null ? "" : sessionStep;
        lastTransferState = transferState == null ? "" : transferState;
        JSONObject d = base();
        put(d, "responseHex", lastResponseHex);
        put(d, "sinceLastCommandMs", lastCommandRequestedElapsedMs < 0 ? -1
                : SystemClock.elapsedRealtime() - lastCommandRequestedElapsedMs);
        put(d, "sessionStep", sessionStep);
        put(d, "transferState", transferState);
        AppLogger.diagnostic(context, "MEMOKET_RESPONSE_RECEIVED", d);
    }

    public void notification(String characteristicUuid, byte[] value) {
        JSONObject d = base();
        put(d, "characteristicUuid", characteristicUuid);
        put(d, "valueHex", hex(value));
        AppLogger.diagnostic(context, "MEMOKET_NOTIFICATION_RECEIVED", d);
    }

    public void data(byte[] value, String transferState, int bufferedBytes) {
        long sequence = sequence(value);
        int payloadBytes = value == null ? 0 : Math.max(0, value.length - 5);
        dataBlocks++;
        dataBytes += payloadBytes;
        lastDataSequence = sequence;
        lastTransferState = transferState == null ? "" : transferState;
        lastBufferedBytes = bufferedBytes;

        JSONObject d = base();
        put(d, "sequence", sequence);
        put(d, "packetBytes", value == null ? 0 : value.length);
        put(d, "payloadBytes", payloadBytes);
        put(d, "dataBlocks", dataBlocks);
        put(d, "dataBytes", dataBytes);
        put(d, "bufferedBytes", bufferedBytes);
        put(d, "transferState", transferState);
        AppLogger.diagnostic(context, "MEMOKET_DATA_BLOCK_RECEIVED", d);
    }

    public void metadataProbeScheduled(long delayMs, String transferState, int bufferedBytes) {
        JSONObject d = base();
        put(d, "delayMs", delayMs);
        put(d, "transferState", transferState);
        put(d, "bufferedBytes", bufferedBytes);
        AppLogger.diagnostic(context, "MEMOKET_METADATA_PROBE_SCHEDULED", d);
    }

    public void filePersisted(String fileName, int payloadBytes, long expectedCrc) {
        JSONObject d = base();
        put(d, "fileName", fileName);
        put(d, "payloadBytes", payloadBytes);
        put(d, "expectedCrcHex", Long.toHexString(expectedCrc));
        AppLogger.event(context, "MEMOKET_FILE_PERSISTED", d);
    }

    public void transferState(MemoketTransfer transfer, String reason) {
        JSONObject d = base();
        put(d, "reason", reason);
        if (transfer != null) {
            lastTransferState = transfer.debugState();
            lastFileName = transfer.debugFileName();
            lastExpectedSize = transfer.debugExpectedSize();
            lastExpectedCrcHex = transfer.debugExpectedCrcHex();
            lastBufferedBytes = transfer.bufferedBytes();
            put(d, "transferState", lastTransferState);
            put(d, "fileName", lastFileName);
            put(d, "expectedSize", lastExpectedSize);
            put(d, "expectedCrcHex", lastExpectedCrcHex);
            put(d, "bufferedBytes", lastBufferedBytes);
            put(d, "dataBlocks", transfer.dataBlockCount());
            put(d, "completedCount", transfer.completedCount());
        }
        AppLogger.diagnostic(context, "MEMOKET_TRANSFER_STATE", d);
    }

    public void failure(String error, Throwable throwable) {
        JSONObject d = base();
        put(d, "error", error);
        put(d, "lastCommandHex", lastCommandHex);
        put(d, "lastResponseHex", lastResponseHex);
        put(d, "dataBlocks", dataBlocks);
        put(d, "dataBytes", dataBytes);
        put(d, "lastDataSequence", lastDataSequence);
        put(d, "lastSessionStep", lastSessionStep);
        put(d, "lastTransferState", lastTransferState);
        put(d, "lastFileName", lastFileName);
        put(d, "lastBufferedBytes", lastBufferedBytes);
        put(d, "lastExpectedSize", lastExpectedSize);
        put(d, "lastExpectedCrcHex", lastExpectedCrcHex);
        put(d, "lastGattStatus", lastGattStatus);
        put(d, "lastGattState", lastGattState);
        put(d, "lastDescriptorStatus", lastDescriptorStatus);
        if (throwable != null) {
            put(d, "exceptionClass", throwable.getClass().getName());
            put(d, "exceptionMessage", throwable.getMessage());
        }
        AppLogger.event(context, "MEMOKET_TRACE_FAILED", d);
    }

    public void completed(JSONObject extra) {
        AppLogger.event(context, "MEMOKET_TRACE_COMPLETED", merge(base(), extra));
    }

    public JSONObject snapshot() {
        JSONObject d = base();
        put(d, "lastCommandHex", lastCommandHex);
        put(d, "lastResponseHex", lastResponseHex);
        put(d, "dataBlocks", dataBlocks);
        put(d, "dataBytes", dataBytes);
        put(d, "lastDataSequence", lastDataSequence);
        put(d, "lastSessionStep", lastSessionStep);
        put(d, "lastTransferState", lastTransferState);
        put(d, "lastFileName", lastFileName);
        put(d, "lastBufferedBytes", lastBufferedBytes);
        put(d, "lastExpectedSize", lastExpectedSize);
        put(d, "lastExpectedCrcHex", lastExpectedCrcHex);
        put(d, "lastGattStatus", lastGattStatus);
        put(d, "lastGattState", lastGattState);
        put(d, "lastDescriptorStatus", lastDescriptorStatus);
        return d;
    }

    private JSONObject base() {
        JSONObject d = new JSONObject();
        put(d, "sessionId", sessionId);
        put(d, "route", route);
        put(d, "phase", phase);
        put(d, "elapsedMs", SystemClock.elapsedRealtime() - startedElapsedMs);
        return d;
    }

    private static JSONObject merge(JSONObject base, JSONObject extra) {
        if (extra == null) return base;
        java.util.Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try { base.put(key, extra.opt(key)); } catch (Exception ignored) { }
        }
        return base;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String commandName(byte[] value) {
        if (value == null || value.length == 0) return "EMPTY";
        String h = hex(value);
        if ("00".equals(h)) return "SESSION_00";
        if ("2701".equals(h)) return "SESSION_27";
        if ("e1".equals(h)) return "SESSION_E1";
        if ("f300".equals(h)) return "SESSION_F3";
        if ("e301".equals(h)) return "SESSION_E3";
        if ("ff68".equals(h)) return "SESSION_CHALLENGE";
        if ((value[0] & 0xff) == 0xe5) return "SESSION_CHALLENGE_REPLY";
        if ("e8".equals(h)) return "SESSION_E8";
        if ("03".equals(h)) return "STATEFUL_03";
        if ("010000".equals(h)) return "FILE_LIST";
        if ("0200".equals(h)) return "FILE_METADATA";
        if ((value[0] & 0xff) == 0x05) return "FILE_ACK";
        return "UNKNOWN";
    }

    private static long sequence(byte[] value) {
        if (value == null || value.length < 5) return -1;
        return ((long)(value[0] & 0xff) << 32)
                | ((long)(value[1] & 0xff) << 24)
                | ((long)(value[2] & 0xff) << 16)
                | ((long)(value[3] & 0xff) << 8)
                | (value[4] & 0xff);
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (Exception ignored) { }
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
