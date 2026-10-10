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
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.sktpj.recorder24h.util.AppLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MemoketTestEngine {
    public interface ProgressListener {
        void onProgress(String title, String detail, boolean observeVibration);
    }

    public static final UUID EXTRA5 = UUID.fromString("a1b2c305-4f5c-6e7d-df23-ab12cd34ef56");
    public static final UUID EXTRA6 = UUID.fromString("a1b2c306-4f5c-6e7d-df23-ab12cd34ef56");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private MemoketTestEngine() {}

    public static JSONObject run(
            Context context,
            String caseId,
            String requestedFile,
            ProgressListener listener
    ) {
        long startedAt = System.currentTimeMillis();
        JSONObject result = new JSONObject();
        Session session = null;
        try {
            result.put("id", startedAt + "-" + caseId);
            result.put("caseId", caseId);
            result.put("startedAtMs", startedAt);
            result.put("vibration", -1);
            result.put("status", "RUNNING");
            progress(listener, "˝˛ñô", "Ge}x˝∫Wvt~Y", false);

            if (Build.VERSION.SDK_INT >= 31 &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetootk˝öøpLr∫~{ì");
            }
            String address = MemoketSettings.address(context);
            if (address.isEmpty()) throw new IllegalStateException("Memoket Gem‹zûuºft~[ì");

            Set<String> before = localRawNames(context);
            session = new Session(context, address, result);
            session.connect();

            if (caseId.startsWith("START_")) {
                runStartCase(context, session, caseId, before, result, listener);
            } else if (caseId.startsWith("STOP_")) {
                runStopCase(context, session, caseId, before, result, listener);
            } else if (caseId.startsWith("FILE_")) {
                runFileCase(context, session, caseId, requestedFile, result, listener);
            } else {
                throw new IllegalArgumentException("Unknown Memoket test case: " + caseId);
            }

            result.put("status", "COMPLETED");
            result.put("finishedAtMs", System.currentTimeMillis());
            AppLogger.event(context, "MEMOKET_TEST_COMPLETED", compact(result));
        } catch (Exception error) {
            try {
                result.put("status", "FAILED");
                result.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                result.put("finishedAtMs", System.currentTimeMillis());
                AppLogger.event(context, "MEMOKET_TEST_FAILED", compact(result));
            } catch (Exception ignored) { }
            progress(listener, 2∆πŸ1W", result.optString("error", noz∏È¸"), false);
        } finally {
            if (session != null) session.close();
            MemoketTestStore.save(context, result);
        }
        return result;
    }

    private static void runStartCase(
            Context context,
            Session s,
            String caseId,
            Set<String> before,
            JSONObject result,
            ProgressListener listener
    ) throws Exception {
        progress(listener, ≤Ôö", startLabel(caseId), false);

        if ("START_OFFICIAL".equals(caseId)) {
            s.enableResponse(true);
            s.enableExtra5(true);
            s.enableResponse(true);
            s.enableData(true);
            s.exchange(new byte[]{0x00}, 0x00, 4_000);
            s.enableExtra6(true);
            s.handshakeFrom27();
        } else {
            if ("START_EXTRA5".equals(caseId) || "START_EXTRA56".equals(caseId)) s.enableExtra5(true);
            if ("START_EXTRA56".equals(caseId)) s.enableExtra6(true);
            s.enableData(true);
            s.enableResponse(true);
            s.handshakeStandard();

            if ("START_DATA_ONLY".equals(caseId)) s.enableResponse(false);
            if ("START_RESPONSE_ONLY".equals(caseId)) s.enableData(false);
            if ("START_NONE".equals(caseId)) {
                s.enableData(false);
                s.enableResponse(false);
            }
        }

        progress(listener, ≥∑Û€˚≥˛Û…", "0x03 íO·w~YN˙SΩìo’ˇpí∫xfpUD", true);
        long commandAt = System.currentTimeMillis();
        s.write(new byte[]{0x03});
        if (s.responseEnabled) {
            Event response = s.waitResponse(0x03, commandAt, 4_000);
            result.put("startResponseHex", hex(response.value));
        } else {
            result.put("startResponseHex", "notification disabled");
        }

        progress(listener, ≥∑Û∫ç", "}◊ì∑ÛW~y_ºnŸx\ro/ıˇãÔrn◊ﬁp+±zDw`uD", false);
        Thread.sleep(5_000);

        progress(listener, {âxb", g˜Ân OFgíOoí01 00 00 ˜∑Û˙˚öwóÛ”÷∑w~Y", false);
        ensureForCleanup(s);
        s.enableData(false);
        Thread.sleep(150);
        s.enableData(true);
        Thread.sleep(150);
        int downloaded = s.downloadPending(context, 50, null);
        result.put("downloadedFiles", downloaded);

        File newest = newestNewRaw(context, before);
        attachAudioResult(result, newest);
        long duration = result.optLong("audioDurationMs", 0);
        result.put("recordingVerified", duration >= 3_000);
        progress(listener, "wù„ê", duration >= 3_000
                ? g∞w◊2Ûı°¥˚í∫ΩW~w_"
                : "{ﬁÂ:o∞ü∑Û˙∫çw}~{≥gw_", false);
    }

    private static void runStopCase(
            Context context,
            Session s,
            String caseId,
            Set<String> before,
            JSONObject result,
            ProgressListener listener
    ) throws Exception {
        progress(listener, 2∆π˝(∑˜ãÀ", j˜˝ög}“~ˆπ€∑Ûó€Àw~Y", false);
        s.enableData(true);
        s.enableResponse(true);
        s.handshakeStandard();
        Event started = s.exchange(new byte[]{0x03}, 0x03, 4_000);
        result.put("startResponseHex", hex(started.value));
        Thread.sleep(5_000);

        progress(listener, r\rô‹€üL", stopLabel(caseId) + "N˙SΩìo’ˇpí∫xfpUD", true);
        long candidateAt = System.currentTimeMillis();
        executeStopCandidate(s, caseId);
        result.put("candidateAtMs", candidateAt);

        progress(listener, "ô¸Õ_ºíˇ,", "{ﬂÖq~Y2snºâxbo’ˆπ˝wú;ÅztgpUD", false);
        Thread.sleep(3_000);

        long cleanupAt = System.currentTimeMillis();
        result.put("cleanupAtMs", cleanupAt);
        progress(listener, {Ÿhr˚ﬂó", "˜ınbK6©˜wπü2Û”÷∑w~Y", false);
        ensureForCleanup(s);
        s.enableData(false);
        Thread.sleep(150);
        s.enableData(true);
        Thread.sleep(150);
        int downloaded = s.downloadPending(context, 50, null);
        result.put("downloadedFiles", downloaded);

        File newest = newestNewRaw(context, before);
        attachAudioResult(result, newest);
        long duration = result.optLong("audioDurationMs", 0);
        long candidateExpected = candidateAt - result.optLong("startedAtMs", candidateAt);
        // The connection/handshake time is outside audio duration. Compare against the controlled 5s + 3s windows.
        if (duration > 0 && duration <= 6_700) {
            result.put("stopInference", r¸œ\wbw_Ô˝7L¯D");
        } else if (duration >= 7_200) {
            result.put("stopInference", "ô¸Õ_ºÇ∑ÛL˝∫W_Ô˝7ﬁÿD");
        } else {
            result.put("stopInference", "∑˜B≥{âöç˝");
        }
        result.put("recordingVerified", duration >= 3_000);
        progress(listener, PùÁê", result.optString("stopInference"), false);
    }

    private static void executeStopCandidate(Session s, String caseId) throws Exception {
        switch (caseId) {
            case "STOP_A":
                s.enableData(false);
                break;
            case "STOP_B":
                s.enableData(true);
                break;
            case "STOP_C":
                s.write(new byte[]{0x01, 0x00, 0x00});
                break;
            case "STOP_AB":
                s.enableData(false);
                s.enableData(true);
                break;
            case "STOP_AC":
                s.enableData(false);
                s.write(new byte[]{0x01, 0x00, 0x00});
                break;
            case "STOP_BC":
                s.enableData(true);
                s.write(new byte[]{0x01, 0x00, 0x00});
                break;
            case "STOP_ABC":
                s.enableData(false);
                s.enableData(true);
                s.write(new byte[]{0x01, 0x00, 0x00});
                break;
            case "STOP_OFFICIAL_TIMING":
                s.enableData(false);
                Thread.sleep(400);
                s.enableData(true);
                Thread.sleep(270);
                s.write(new byte[]{0x01, 0x00, 0x00});
                break;
            default:
                throw new IllegalArgumentException("Unknown stop case " + caseId);
        }
    }

    private static void runFileCase(
            Context context,
            Session s,
            String caseId,
            String requestedFile,
            JSONObject result,
            ProgressListener listener
    ) throws Exception {
        progress(listener, c•∫˚ç<", 2’±¥Îﬂ˜(ªÛ∑˜Ûí÷πW~Y", false);
        s.enableData(true);
        s.enableResponse(true);
        s.handshakeStandard();

        if ("FILE_LIST".equals(caseId)) {
            Event event = s.exchange(new byte[]{0x01, 0x00, 0x00}, 0x01, 5_000);
            result.put("listResponseHex", hex(event.value));
            result.put("listedFile", parseListName(event.value));
            result.put("downloadedFiles", 0);
            progress(listener, nâßﬂó", result.optString("listedFile", 2ı°¥˚jW"), false);
            return;
        }

        int max = "FILE_THREE".equals(caseId) ? 3 : "FILE_SPECIFIC".equals(caseId) ? 50 : 1;
        String target = "FILE_SPECIFIC".equals(caseId) ? requestedFile : null;
        if (target != null && target.trim().isEmpty()) {
            throw new IllegalArgumentException("ﬂ∑Yªı°¥ˇ”wõwvOpuD");
        }
        progress(listener, "ı±§˚ﬂó", target == null
                ? max + nˆ~wﬂów~Y"
                : "_∫’±¥Î{∞Tyª~ˇ6kﬂ∑W~Y", false);
        int downloaded = s.downloadPending(context, max, target);
        result.put("downloadedFiles", downloaded);
        if (target != null) result.put("requestedFile", target);
        progress(listener, sﬂóŒÜ", downloaded + n˜÷∑w~w_", false);
    }

    private static void ensureForCleanup(Session s) throws Exception {
        if (!s.responseEnabled) s.enableResponse(true);
        if (!s.dataEnabled) s.enableData(true);
    }

    private static void attachAudioResult(JSONObject result, File raw) throws Exception {
        if (raw == null || !raw.isFile()) {
            result.put("audioFile", JSONObject.NULL);
            result.put("audioDurationMs", 0);
            return;
        }
        long frames = raw.length() / 80L;
        result.put("audioFile", raw.getName().replace(".raw", ""));
        result.put("rawBytes", raw.length());
        result.put("audioDurationMs", frames * 20L);
    }

    private static Set<String> localRawNames(Context context) {
        Set<String> names = new HashSet<>();
        File[] files = new MemoketRecordingStore(context).directory().listFiles((dir, name) -> name.endsWith(".raw"));
        if (files != null) for (File file : files) names.add(file.getName());
        return names;
    }

    private static File newestNewRaw(Context context, Set<String> before) {
        File newest = null;
        File[] files = new MemoketRecordingStore(context).directory().listFiles((dir, name) -> name.endsWith(".raw"));
        if (files == null) return null;
        for (File file : files) {
            if (before.contains(file.getName())) continue;
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    private static String parseListName(byte[] value) {
        if (value == null || value.length < 4 || value[0] != 1 || value[1] != 1 || value[2] != 1) return "";
        return new String(value, 3, value.length - 3, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void progress(ProgressListener listener, String title, String detail, boolean observe) {
        if (listener != null) listener.onProgress(title, detail, observe);
    }

    private static JSONObject compact(JSONObject result) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", result.optString("id"));
            out.put("caseId", result.optString("caseId"));
            out.put("status", result.optString("status"));
            out.put("error", result.optString("error"));
            out.put("audioFile", result.opt("audioFile"));
            out.put("audioDurationMs", result.optLong("audioDurationMs"));
            out.put("stopInference", result.optString("stopInference"));
            out.put("downloadedFiles", result.optInt("downloadedFiles"));
        } catch (Exception ignored) { }
        return out;
    }

    public static String startLabel(String id) {
        switch (id) {
            case "START_CURRENT": return s˛8üˇDATA + RESPONSˇ	";
            case "START_OFFICIAL": return s≤˜Íˇˇ0036/0039÷;Ä	";
            case "START_DATA_ONLY": return "€ˇÙ}oDATAın";
            case "START_RESPONSE_ONLY": return ∑€Àˆ}oRESPONS’ı~";
            case "START_EXTRA5": return "DATA + RESPONSE + 0036";
            case "START_EXTRA56": return "DATA + RESPONSE + 0036 + 0039";
            case "START_NONE": return ∑ãˇˆMˇÂzW";
            default: return id;
        }
    }

    public static String stopLabel(String id) {
        switch (id) {
            case "STOP_A": return "A: DAT—ÂOFF~";
            case "STOP_B": return "B: DAT—ÂON~";
            case "STOP_C": return "C: 01 00 00~";
            case "STOP_AB": return "AíB: OFgíON";
            case "STOP_AC": return "aíC: OFFí01 00 00";
            case "STOP_BC": return "cíC: Ooí01 00 00";
            case "STOP_ABC": return "AícíC: OFFíONí01 00 00";
            case "STOP_OFFICIAL_TIMING": return sˇS: OFgí400msíOoí270msí01 00 00";
            default: return id;
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static final class Event {
        final UUID uuid;
        final byte[] value;
        final long atMs;
        Event(UUID uuid, byte[] value) {
            this.uuid = uuid;
            this.value = value == null ? new byte[0] : Arrays.copyOf(value, value.length);
            this.atMs = System.currentTimeMillis();
        }
    }

    private static final class Session {
        final Context context;
        final String address;
        final JSONObject result;
        final JSONArray trace = new JSONArray();
        final BlockingQueue<Event> notifications = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> descriptorStatuses = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> writeStatuses = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch mtuReady = new CountDownLatch(1);
        final CountDownLatch servicesReady = new CountDownLatch(1);

        BluetoothGatt gatt;
        BluetoothGattCharacteristic data;
        BluetoothGattCharacteristic control;
        BluetoothGattCharacteristic response;
        BluetoothGattCharacteristic extra5;
        BluetoothGattCharacteristic extra6;
        volatile int connectionStatus = Integer.MIN_VALUE;
        volatile int connectionState = BluetoothProfile.STATE_DISCONNECTED;
        volatile int serviceStatus = Integer.MIN_VALUE;
        volatile int mtuStatus = Integer.MIN_VALUE;
        volatile int negotiatedMtu = 23;
        boolean dataEnabled;
        boolean responseEnabled;

        Session(Context context, String address, JSONObject result) {
            this.context = context.getApplicationContext();
            this.address = address;
            this.result = result;
        }

        void connect() throws Exception {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("BluetootxLsπgY");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("GATw˝öó€Àw}~{ì");
            if (!connected.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("GATT˝∫Lø¥‡≤∂»w~W_");
            if (connectionStatus != BluetoothGatt.GATT_SUCCESS || connectionState != BluetoothProfile.STATE_CONNECTED) {
                throw new IllegalStateException("GATw•∫∏È¸: " + connectionStatus);
            }
            if (!gatt.requestMtu(513)) throw new IllegalStateException("Memoket MTUÌrí€˚g}~[ì");
            if (!mtuReady.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Memoket MT_Ó	|ø§≤¶¯w~w_");
            if (mtuStatus != BluetoothGatt.GATT_SUCCESS || negotiatedMtu < 488) {
                throw new IllegalStateException("Memoket MT_Ó	uW status=" + mtuStatus + " mtu=" + negotiatedMtu);
            }
            result.put("negotiatedMtu", negotiatedMtu);
            if (!gatt.discoverServices()) throw new IllegalStateException("GATTµ¸”˚ˇ"ó€Àw}~{ì");
            if (!servicesReady.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("GATtµ¸Û˚¢2|ø¥¢∂¯W~w_");
            if (serviceStatus != BluetoothGatt.GATT_SUCCESS) throw new IllegalStateException("GATTµ¸”˚ˇ"∏˘¸: " + serviceStatus);

            BluetoothGattService service = gatt.getService(MemoketGattSync.SERVICE);
            if (service == null) throw new IllegalStateException("Memoketµ¸ÛπLr∫~{ì");
            data = service.getCharacteristic(MemoketGattSync.DATA);
            control = service.getCharacteristic(MemoketGattSync.CONTROL);
            response = service.getCharacteristic(MemoketGattSync.RESPONSE);
            extra5 = service.getCharacteristic(EXTRA5);
            extra6 = service.getCharacteristic(EXTRA6);
            if (data == null || control == null || response == null) {
                throw new IllegalStateException("Memoke›CharacteristisLç≥Wvt~Y");
            }
            result.put("extra0036Available", extra5 != null && extra5.getDescriptor(CCCD) != null);
            result.put("extra0039Available", extra6 != null && extra6.getDescriptor(CCCD) != null);
            result.put("trace", trace);
        }

        void enableData(boolean enabled) throws Exception {
            setNotify(data, enabled, "DATA");
            dataEnabled = enabled;
        }

        void enableResponse(boolean enabled) throws Exception {
            setNotify(response, enabled, "RESPONSE");
            responseEnabled = enabled;
        }

        void enableExtra5(boolean enabled) throws Exception {
            if (extra5 == null) throw new IllegalStateException("0x0036 characteristicÕªd{∫~{ì");
            setNotify(extra5, enabled, "0036");
        }

        void enableExtra6(boolean enabled) throws Exception {
            if (extra6 == null) throw new IllegalStateException("0x0039 characteristicÕªd{∫~{ì");
            setNotify(extra6, enabled, "0039");
        }

        void setNotify(BluetoothGattCharacteristic characteristic, boolean enabled, String label) throws Exception {
            if (!gatt.setCharacteristicNotification(characteristic, enabled)) {
                throw new IllegalStateException(label + " notificatio~ˇ{1ww~w_");
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) throw new IllegalStateException(label + " CCCt|B∫~[ì");
            descriptorStatuses.clear();
            descriptor.setValue(enabled
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            addTrace("CCCD", label + "=" + (enabled ? "ON" : "OFF"));
            if (!gatt.writeDescriptor(descriptor)) throw new IllegalStateException(label + " CCCD writeó€Àw}~{ì");
            Integer status = descriptorStatuses.poll(5, TimeUnit.SECONDS);
            if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                throw new IllegalStateException(label + " CCCD writ}uW: " + status);
            }
        }

        void handshakeStandard() throws Exception {
            exchange(new byte[]{0x00}, 0x00, 4_000);
            handshakeFrom27();
        }

        void handshakeFrom27() throws Exception {
            exchange(new byte[]{0x27,0x01}, 0x27, 4_000);
            exchange(new byte[]{(byte)0xe1}, 0xe1, 4_000);
            exchange(new byte[]{(byte)0xf3,0x00}, 0xf3, 4_000);
            exchange(new byte[]{(byte)0xe3,0x01}, 0xe3, 4_000);
            Event challenge = exchange(new byte[]{(byte)0xff,0x68}, 0xff, 4_000);
            if (challenge.value.length < 6 || (challenge.value[1] & 0xff) != 0x68) {
                throw new IllegalStateException("ff68 challengˇTNocwY: " + hex(challenge.value));
            }
            byte[] token = Arrays.copyOfRange(challenge.value, 2, 6);
            incrementBigEndian(token);
            byte[] e5 = new byte[5];
            e5[0] = (byte)0xe5;
            System.arraycopy(token, 0, e5, 1, 4);
            exchange(e5, 0xe5, 4_000);
            exchange(new byte[]{(byte)0xe8}, 0xe8, 4_000);
        }

        Event exchange(byte[] command, int opcode, long timeoutMs) throws Exception {
            long marker = System.currentTimeMillis();
            write(command);
            return waitResponse(opcode, marker, timeoutMs);
        }

        void write(byte[] command) throws Exception {
            writeStatuses.clear();
            control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            control.setValue(command);
            addTrace("WRITE", hex(command));
            if (!gatt.writeCharacteristic(control)) throw new IllegalStateException("control writuí€˚g}~[ì: " + hex(command));
            Integer status = writeStatuses.poll(5, TimeUnit.SECONDS);
            if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                throw new IllegalStateException("control writeuW status=" + status + " command=" + hex(command));
            }
        }

        Event waitResponse(int opcode, long afterMs, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                long remain = Math.max(1, deadline - System.currentTimeMillis());
                Event event = notifications.poll(remain, TimeUnit.MILLISECONDS);
                if (event == null) break;
                if (!MemoketGattSync.RESPONSE.equals(event.uuid)) continue;
                if (event.atMs < afterMs || event.value.length == 0) continue;
                if ((event.value[0] & 0xff) == opcode) return event;
            }
            throw new IllegalStateException(String.format("response 0x%02x timeout", opcode));
        }

        int downloadPending(Context context, int maxFiles, String targetName) throws Exception {
            if (!dataEnabled) enableData(true);
            if (!responseEnabled) enableResponse(true);
            MemoketRecordingStore store = new MemoketRecordingStore(context);
            List<String> saved = new ArrayList<>();
            MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {
                store.persist(name, payload, crc);
                saved.add(name);
            });

            byte[] next = MemoketTransfer.initialCommand();
            long deadline = System.currentTimeMillis() + 90_000;
            long lastActivity = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                if (next != null) {
                    write(next);
                    next = null;
                }

                Event event = notifications.poll(300, TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (transfer.shouldRequestMetadata()) {
                        next = MemoketTransfer.metadataCommand();
                        continue;
                    }
                    if (System.currentTimeMillis() - lastActivity > 8_000) {
                        throw new IllegalStateException(2’±¥ÎÚ_‹t|ø¥¢∂¯W~w_");
                    }
                    continue;
                }
                lastActivity = System.currentTimeMillis();

                if (MemoketGattSync.DATA.equals(event.uuid)) {
                    byte[] candidate = transfer.onData(event.value);
                    if (candidate != null) next = candidate;
                    continue;
                }
                if (!MemoketGattSync.RESPONSE.equals(event.uuid)) continue;

                byte[] candidate = transfer.onControl(event.value);
                if (candidate != null && candidate.length > 0 && candidate[0] == 0x05) {
                    write(candidate);
                    Event ack = waitResponse(0x05, System.currentTimeMillis() - 500, 5_000);
                    byte[] following = transfer.onControl(ack.value);
                    boolean targetReached = targetName != null && saved.contains(targetName);
                    if (saved.size() >= maxFiles || targetReached) break;
                    next = following;
                    lastActivity = System.currentTimeMillis();
                } else {
                    next = candidate;
                }
                if (transfer.isDone()) break;
            }
            return saved.size();
        }

        void addTrace(String kind, String value) {
            try {
                JSONObject row = new JSONObject();
                row.put("atMs", System.currentTimeMillis());
                row.put("kind", kind);
                row.put("value", value);
                trace.put(row);
            } catch (Exception ignored) { }
        }

        void close() {
            BluetoothGatt current = gatt;
            gatt = null;
            if (current != null) {
                try { current.disconnect(); } catch (Exception ignored) { }
                try { current.close(); } catch (Exception ignored) { }
            }
        }

        final BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
                connectionStatus = status;
                connectionState = newState;
                addTrace("CONNECTION", "status=" + status + " state=" + newState);
                connected.countDown();
            }

            @Override
            public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
                mtuStatus = status;
                negotiatedMtu = mtu;
                addTrace("MTU", "status=" + status + " mtu=" + mtu);
                mtuReady.countDown();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt connection, int status) {
                serviceStatus = status;
                addTrace("SERVICES", "status=" + status);
                servicesReady.countDown();
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
                descriptorStatuses.offer(status);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
                writeStatuses.offer(status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
                onChanged(characteristic.getUuid(), characteristic.getValue());
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
                onChanged(characteristic.getUuid(), value);
            }

            void onChanged(UUID uuid, byte[] value) {
                if (MemoketGattSync.DATA.equals(uuid)) {
                    String sequence = "";
                    if (value != null && value.length >= 5) {
                        long seq = ((long)(value[0] & 0xff) << 32)
                                | ((long)(value[1] & 0xff) << 24)
                                | ((long)(value[2] & 0xff) << 16)
                                | ((long)(value[3] & 0xff) << 8)
                                | (value[4] & 0xff);
                        sequence = " seq=" + seq;
                    }
                    addTrace("DATA", "bytes=" + (value == null ? 0 : value.length) + sequence);
                } else {
                    addTrace("NOTIFY", uuid + ":" + hex(value));
                }
                notifications.offer(new Event(uuid, value));
            }
        };
    }

    private static void incrementBigEndian(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            bytes[i]++;
            if (bytes[i] != 0) return;
        }
    }
}
