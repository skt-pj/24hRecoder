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
import java.util.concurrent.atomic.AtomicBoolean;

public final class MemoketTestEngine {
    public interface ProgressListener {
        void onProgress(String title, String detail, boolean observeVibration);
    }

    public static final UUID EXTRA5 = UUID.fromString("a1b2c305-4f5c-6e7d-df23-ab12cd34ef56");
    public static final UUID EXTRA6 = UUID.fromString("a1b2c306-4f5c-6e7d-df23-ab12cd34ef56");
    private static final AtomicBoolean BATCH_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL_REQUESTED = new AtomicBoolean(false);

    /** User cancellation, never a fixed transfer-duration limit. */
    public static void cancelActiveTest() {
        CANCEL_REQUESTED.set(true);
    }
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private MemoketTestEngine() {}

    /** No BLE operation without confirmed device stop. Legacy callers are denied. */
    public static JSONObject run(Context context, String caseId, String requestedFile,
            ProgressListener listener) {
        return run(context, caseId, requestedFile, listener, false);
    }

    public static JSONObject run(Context context, String caseId, String requestedFile,
            ProgressListener listener, boolean userConfirmedPhysicalStop) {
        CANCEL_REQUESTED.set(false);
        long startedAt = System.currentTimeMillis();
        JSONObject result = new JSONObject();
        Session session = null;
        try {
            result.put("id", startedAt + "-" + caseId);
            result.put("caseId", caseId);
            result.put("startedAtMs", startedAt);
            result.put("vibration", -1);
            result.put("status", "RUNNING");
            result.put("physicalStopUserConfirmed", userConfirmedPhysicalStop);
            if (!userConfirmedPhysicalStop) {
                throw new IllegalStateException("Gem本体が停止したことを確認するまでBLE試験は実行できません");
            }
            if (!"FILE_ONE".equals(caseId)) {
                throw new IllegalStateException("未検証の録音開始/停止候補と一覧だけの切断試験は無効です。"
                        + "公式HCI由来の停止済みファイル1件取得だけを実行してください");
            }
            progress(listener, "接続準備", "Gem停止をユーザーが確認済み。ファイル1件のBLE転送を開始します", false);

            if (Build.VERSION.SDK_INT >= 31 &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth接続権限がありません");
            }
            String address = MemoketSettings.address(context);
            if (address.isEmpty()) throw new IllegalStateException("Memoket Gemが選択されていません");

            session = new Session(context, address, result);
            session.connect();
            runFileCase(context, session, "FILE_ONE", "", result, listener);
            if (result.optInt("downloadedFiles", 0) != 1) {
                throw new IllegalStateException("CRC照合・端末保存・Gem ACKまで完了したファイルが0件です");
            }
            result.put("status", "COMPLETED");
            result.put("finishedAtMs", System.currentTimeMillis());
            if (session != null) session.debug.completed(
                    new JSONObject().put("caseId", caseId).put("status", "COMPLETED"));
            AppLogger.event(context, "MEMOKET_TEST_COMPLETED", compact(result));
        } catch (Exception error) {
            if (session != null) session.debug.failure(
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
            try {
                result.put("status", CANCEL_REQUESTED.get() ? "CANCELLED" : "FAILED");
                result.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                result.put("finishedAtMs", System.currentTimeMillis());
                AppLogger.event(context, "MEMOKET_TEST_FAILED", compact(result));
            } catch (Exception ignored) { }
            progress(listener, "テスト失敗", result.optString("error", "不明なエラー"), false);
        } finally {
            if (session != null) {
                attachOfficialComparison(context, result, session.trace, session.firstDataSequence);
                session.close();
            } else {
                attachOfficialComparison(context, result, new JSONArray(), -1);
            }
            MemoketTestStore.save(context, result);
        }
        return result;
    }

    /**
     * A batch never guesses any Gem stop command and never starts recording.
     * The physical stop confirmation is a strict prerequisite, not proof
     * inferred from DATA/CCCD/0x03 responses.
     */
    public static JSONObject runBatch(Context context, ProgressListener listener) {
        return runBatch(context, listener, false);
    }

    public static JSONObject runBatch(Context context, ProgressListener listener,
            boolean userConfirmedPhysicalStop) {
        long began = System.currentTimeMillis();
        JSONObject report = new JSONObject();
        if (!BATCH_RUNNING.compareAndSet(false, true)) {
            try {
                report.put("id", began + "-BATCH_ALL");
                report.put("caseId", "BATCH_ALL");
                report.put("status", "BUSY_ALREADY_RUNNING");
                report.put("error", "別のGem診断が実行中です");
            } catch (Exception ignored) { }
            return report;
        }

        CANCEL_REQUESTED.set(false);
        Session session = null;
        JSONArray cases = new JSONArray();
        int downloaded = 0;
        try {
            report.put("id", began + "-BATCH_ALL");
            report.put("caseId", "BATCH_ALL");
            report.put("startedAtMs", began);
            report.put("status", "RUNNING");
            report.put("cases", cases);
            report.put("physicalStopUserConfirmed", userConfirmedPhysicalStop);
            report.put("gemStopVerifiedByDevice", false);
            report.put("recordingStartAttempted", false);
            report.put("remoteStopAttempted", false);
            report.put("executedStopCandidates", 0);
            report.put("officialReference", "PRIOR_CAPTURED_HCI_PARTIAL");
            report.put("fileTransferMethod", "BLE_ONE_GATT_SESSION_ONE_FILE");
            report.put("gemFileAckPolicy", "CRC verified and persisted before 0x05 ACK");
            if (!userConfirmedPhysicalStop) {
                throw new IllegalStateException(
                        "Gem本体で録音停止し、赤LED消灯と停止の振動を確認するまで診断を開始できません");
            }
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth接続権限がありません");
            }
            String address = MemoketSettings.address(context);
            if (address.isEmpty()) throw new IllegalStateException("Memoket Gemが選択されていません");
            JSONObject file = new JSONObject();
            file.put("caseId", "FILE_ONE");
            file.put("startedAtMs", System.currentTimeMillis());
            cases.put(file);
            session = new Session(context, address, file);
            progress(listener, "既知の公式HCI手順", "通知ON→認証→待機00 00の確認→音声1件受信。停止候補は実行しません", false);
            session.connect();
            runFileCase(context, session, "FILE_ONE", "", file, listener);
            downloaded = file.optInt("downloadedFiles", 0);
            if (downloaded != 1) {
                throw new IllegalStateException(
                        "停止済みファイルのサイズ・CRC・端末保存・Gem ACKが完了していません");
            }
            file.put("status", "SAVED_AND_ACKED");
            file.put("finishedAtMs", System.currentTimeMillis());
            report.put("status", "COMPLETED");
            report.put("fileAcquisitionVerified", true);
            report.put("downloadedFiles", downloaded);
            report.put("gemAckAccepted", true);
            progress(listener, "取得完了", "音声1件のCRC・永続保存・Gem ACKを確認しました", false);
        } catch (Exception error) {
            String why = error.getMessage() == null ? error.toString() : error.getMessage();
            try {
                report.put("status", CANCEL_REQUESTED.get() ? "CANCELLED" : "FAILED");
                report.put("error", why);
                report.put("downloadedFiles", downloaded);
                report.put("fileAcquisitionVerified", false);
                report.put("gemAckAccepted", false);
                if (cases.length() > 0) {
                    JSONObject file = cases.optJSONObject(0);
                    file.put("status", CANCEL_REQUESTED.get() ? "CANCELLED" : "FAILED");
                    file.put("error", why);
                    file.put("finishedAtMs", System.currentTimeMillis());
                }
            } catch (Exception ignored) { }
            if (session != null) session.debug.failure(why, error);
            progress(listener, "取得未完了", why, false);
        } finally {
            try {
                JSONArray trace = session == null ? new JSONArray() : session.trace;
                long firstSeq = session == null ? -1 : session.firstDataSequence;
                report.put("traceSessionId", session == null ? "" : session.debug.sessionId());
                report.put("firstDataSequence", firstSeq);
                report.put("dataBlockCount", session == null ? 0 : session.observedDataBlocks);
                report.put("listedFile", session == null ? "" : session.firstAnnouncedFile);
                report.put("listResponseHex", session == null ? "" : session.lastListResponseHex);
                report.put("lastMetadataResponseHex", session == null ? "" : session.lastMetadataResponseHex);
                report.put("savedFiles", new JSONArray(session == null
                        ? java.util.Collections.emptyList() : session.savedFileNames));
                report.put("trace", trace);
                attachOfficialComparison(context, report, trace, firstSeq);
                if (cases.length() > 0) {
                    JSONObject item = cases.optJSONObject(0);
                    item.put("traceSessionId", report.optString("traceSessionId"));
                    item.put("firstDataSequence", firstSeq);
                    item.put("officialComparison", report.optJSONObject("officialComparison"));
                }
                report.put("finishedAtMs", System.currentTimeMillis());
                AppLogger.event(context, "MEMOKET_BATCH_COMPLETED", new JSONObject()
                        .put("status", report.optString("status"))
                        .put("downloadedFiles", downloaded)
                        .put("physicalStopUserConfirmed", userConfirmedPhysicalStop)
                        .put("stopCandidates", 0));
            } catch (Exception ignored) { }
            if (session != null) session.close();
            MemoketTestStore.save(context, report);
            BATCH_RUNNING.set(false);
        }
        return report;
    }

    private static void attachOfficialComparison(Context context, JSONObject target,
            JSONArray trace, long firstSeq) {
        try {
            JSONObject comparison = MemoketOfficialHciComparator.compare(
                    trace, target.optString("caseId"), target.optInt("downloadedFiles", 0), firstSeq);
            target.put("officialComparison", comparison);
            AppLogger.event(context, "MEMOKET_OFFICIAL_HCI_COMPARISON", comparison);
        } catch (Exception ignored) { }
    }

    /** A list-only command may start DATA streaming; never issue it then disconnect. */
    private static void runFileCase(Context context, Session session, String caseId,
            String requestedFile, JSONObject result, ProgressListener listener) throws Exception {
        if (!"FILE_ONE".equals(caseId)) {
            throw new IllegalStateException("ファイルは一接続で先頭から末尾まで1件ずつ取得します");
        }
        progress(listener, "接続・認証", "公式HCIで観測した通知・認証手順を使用します", false);
        session.enableData(true);
        session.enableResponse(true);
        session.handshakeStandard();
        progress(listener, "音声ファイル取得", "01一覧→DATA連番0から→02メタデータ→CRC検証→03確定→保存→05 ACK", false);
        int downloaded = session.downloadPending(context, 1, null);
        result.put("downloadedFiles", downloaded);
        result.put("listedFile", session.firstAnnouncedFile);
        if (downloaded != 1) {
            throw new IllegalStateException("停止済みGem音声ファイルの保存を完了できませんでした");
        }
        progress(listener, "ファイル保存", "端末に1件保存しGem完了応答を確認しました", false);
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
            out.put("stopObserved", result.optString("stopObserved"));
            out.put("candidateAtMs", result.optLong("candidateAtMs"));
            out.put("gemFileAckSent", result.optBoolean("gemFileAckSent"));
            out.put("downloadedFiles", result.optInt("downloadedFiles"));
            out.put("traceSessionId", result.optString("traceSessionId"));
        } catch (Exception ignored) { }
        return out;
    }

    public static String startLabel(String id) {
        switch (id) {
            case "START_CURRENT": return "現在の実装（DATA + RESPONSE）";
            case "START_OFFICIAL": return "公式アプリ相当（0036/0039を含む）";
            case "START_DATA_ONLY": return "開始直前はDATA通知のみ";
            case "START_RESPONSE_ONLY": return "開始直前はRESPONSE通知のみ";
            case "START_EXTRA5": return "DATA + RESPONSE + 0036";
            case "START_EXTRA56": return "DATA + RESPONSE + 0036 + 0039";
            case "START_NONE": return "開始直前は通知なし";
            default: return id;
        }
    }

    public static String stopLabel(String id) {
        switch (id) {
            case "STOP_A": return "A: DATA通知OFFのみ";
            case "STOP_B": return "B: DATA通知ONのみ";
            case "STOP_C": return "C: 01 00 00のみ";
            case "STOP_AB": return "A→B: OFF→ON";
            case "STOP_AC": return "A→C: OFF→01 00 00";
            case "STOP_BC": return "B→C: ON→01 00 00";
            case "STOP_ABC": return "A→B→C: OFF→ON→01 00 00";
            case "STOP_OFFICIAL_TIMING": return "OFF→400ms→ON→270ms→一覧要求";
            case "STOP_OFF_WAIT_ON": return "OFF→1000ms→ON";
            case "STOP_ON_OFF": return "ON→400ms→OFF";
            case "STOP_LIST_DELAY": return "OFF→1000ms→ON→1000ms→一覧要求";
            case "STOP_03_REPEAT": return "03再送";
            case "STOP_DISCONNECT": return "単純切断";
            case "STOP_OFF_DISCONNECT": return "DATA OFF→切断";
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
        final MemoketDebugTrace debug;
        final BlockingQueue<Event> notifications = new LinkedBlockingQueue<>();
        // DATA can arrive during the session handshake, before 01 announces a
        // filename. Preserve those original blocks until transfer state is ready.
        final BlockingQueue<Event> earlyAudio = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> descriptorStatuses = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> writeStatuses = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1);
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
        boolean dataEnabled;
        boolean responseEnabled;
        String firstAnnouncedFile = "";
        String lastListResponseHex = "";
        final List<String> savedFileNames = new ArrayList<>();
        volatile long firstDataSequence = -1L;
        volatile int observedDataBlocks = 0;
        volatile int metadataProbeCount = 0;
        volatile String lastMetadataResponseHex = "";

        Session(Context context, String address, JSONObject result) {
            this.context = context.getApplicationContext();
            this.address = address;
            this.result = result;
            this.debug = new MemoketDebugTrace(this.context, "TEST_ENGINE:" + result.optString("caseId", "unknown"));
            try { this.result.put("traceSessionId", this.debug.sessionId()); } catch (Exception ignored) { }
        }

        void connect() throws Exception {
            debug.phase("GATT_CONNECT_REQUESTED");
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetoothが無効です");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IllegalStateException("GATT接続を開始できません");
            if (!connected.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("GATT接続がタイムアウトしました");
            if (connectionStatus != BluetoothGatt.GATT_SUCCESS || connectionState != BluetoothProfile.STATE_CONNECTED) {
                throw new IllegalStateException("GATT接続エラー: " + connectionStatus);
            }
            debug.phase("SERVICE_DISCOVERY_REQUESTED");
            if (!gatt.discoverServices()) throw new IllegalStateException("GATTサービス探索を開始できません");
            if (!servicesReady.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("GATTサービス探索がタイムアウトしました");
            if (serviceStatus != BluetoothGatt.GATT_SUCCESS) throw new IllegalStateException("GATTサービス探索エラー: " + serviceStatus);

            BluetoothGattService service = gatt.getService(MemoketGattSync.SERVICE);
            if (service == null) throw new IllegalStateException("Memoketサービスがありません");
            data = service.getCharacteristic(MemoketGattSync.DATA);
            control = service.getCharacteristic(MemoketGattSync.CONTROL);
            response = service.getCharacteristic(MemoketGattSync.RESPONSE);
            extra5 = service.getCharacteristic(EXTRA5);
            extra6 = service.getCharacteristic(EXTRA6);
            debug.characteristicInventory(data != null, control != null, response != null);
            if (data == null || control == null || response == null) {
                throw new IllegalStateException("Memoket必須Characteristicが不足しています");
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
            if (extra5 == null) throw new IllegalStateException("0x0036 characteristicが見つかりません");
            setNotify(extra5, enabled, "0036");
        }

        void enableExtra6(boolean enabled) throws Exception {
            if (extra6 == null) throw new IllegalStateException("0x0039 characteristicが見つかりません");
            setNotify(extra6, enabled, "0039");
        }

        void setNotify(BluetoothGattCharacteristic characteristic, boolean enabled, String label) throws Exception {
            if (!gatt.setCharacteristicNotification(characteristic, enabled)) {
                throw new IllegalStateException(label + " notification切替に失敗しました");
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) throw new IllegalStateException(label + " CCCDがありません");
            descriptorStatuses.clear();
            debug.descriptorRequest(label, enabled, characteristic.getUuid().toString());
            descriptor.setValue(enabled
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            addTrace("CCCD", label + "=" + (enabled ? "ON" : "OFF"));
            if (!gatt.writeDescriptor(descriptor)) throw new IllegalStateException(label + " CCCD writeを開始できません");
            Integer status = descriptorStatuses.poll(5, TimeUnit.SECONDS);
            if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                throw new IllegalStateException(label + " CCCD write失敗: " + status);
            }
        }

        void handshakeStandard() throws Exception {
            Event status = exchange(new byte[]{0x00}, 0x00, 4_000);
            if (status.value.length < 2) {
                throw new IllegalStateException("Gemの00応答が短すぎます: " + hex(status.value));
            }
            // 00 01+filename was observed during an interrupted transfer, but
            // its meaning for a physically stopped, pending file is UNPROVEN.
            // Record this official-reference difference without inventing a
            // stop-state interpretation. Require physical confirmation and
            // validate DATA sequence zero before starting any file command.
            if (status.value[1] != 0) {
                addTrace("OFFICIAL_STATUS_DIFF", "reference=0000 actual=" + hex(status.value)
                        + " meaning=UNVERIFIED; physical stop confirmed by user");
            }
            handshakeFrom27();
        }

        void handshakeFrom27() throws Exception {
            exchange(new byte[]{0x27,0x01}, 0x27, 4_000);
            exchange(new byte[]{(byte)0xe1}, 0xe1, 4_000);
            exchange(new byte[]{(byte)0xf3,0x00}, 0xf3, 4_000);
            exchange(new byte[]{(byte)0xe3,0x01}, 0xe3, 4_000);
            Event challenge = exchange(new byte[]{(byte)0xff,0x68}, 0xff, 4_000);
            if (challenge.value.length < 6 || (challenge.value[1] & 0xff) != 0x68) {
                throw new IllegalStateException("ff68 challenge応答が不正です: " + hex(challenge.value));
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
            debug.commandWriteRequested(command, 0);
            control.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            control.setValue(command);
            addTrace("WRITE", hex(command));
            if (!gatt.writeCharacteristic(control)) throw new IllegalStateException("control writeを開始できません: " + hex(command));
            Integer status = writeStatuses.poll(5, TimeUnit.SECONDS);
            if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                throw new IllegalStateException("control write失敗 status=" + status + " command=" + hex(command));
            }
        }

        Event waitResponse(int opcode, long afterMs, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                long remain = Math.max(1, deadline - System.currentTimeMillis());
                Event event = notifications.poll(remain, TimeUnit.MILLISECONDS);
                if (event == null) break;
                if (!MemoketGattSync.RESPONSE.equals(event.uuid)) {
                    if (MemoketGattSync.DATA.equals(event.uuid)) earlyAudio.offer(event);
                    continue;
                }
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
                debug.filePersisted(name, payload.length, crc);
                saved.add(name);
                savedFileNames.add(name);
            });

            // If the Gem is already streaming from a previous connection,
            // the initial audio bytes have been lost. Never pretend a new
            // file-list cycle will recover an intact file.
            if (firstDataSequence > 0) {
                throw new IllegalStateException("公式アプリと比較: Gemの先行転送が継続中です。"
                        + "接続直後のDATA連番=" + firstDataSequence
                        + "（先頭0がないためCRC照合できません）。"
                        + "既存ファイルの削除ACKは行いません。");
            }
            byte[] next = MemoketTransfer.initialCommand();
            final long METADATA_PROBE_INTERVAL_MS = 1_000L;
            // Detect a genuinely stalled BLE session, not the duration of an active transfer.
            final long NO_DATA_OR_RESPONSE_STALL_MS = 60_000L;
            long lastMetadataProbeAt = System.currentTimeMillis();
            long lastActivity = System.currentTimeMillis();
            while (true) {
                if (CANCEL_REQUESTED.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("ファイル取得をユーザー操作で中止しました。未検証のデータはACKしません");
                }
                if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                    throw new IllegalStateException("ファイル転送中にGemのBLE接続が切断されました");
                }
                if (next != null) {
                    if (next.length > 0 && next[0] == 0x02) {
                        metadataProbeCount++;
                        lastMetadataProbeAt = System.currentTimeMillis();
                    }
                    write(next);
                    next = null;
                }

                Event event = "WAIT_LIST".equals(transfer.debugState())
                        ? null : earlyAudio.poll();
                if (event == null) event = notifications.poll(300, TimeUnit.MILLISECONDS);
                if (event == null) {
                    long now = System.currentTimeMillis();
                    if (transfer.shouldRequestMetadata()
                            && now - lastMetadataProbeAt >= METADATA_PROBE_INTERVAL_MS) {
                        next = MemoketTransfer.metadataCommand();
                    }
                    if (now - lastActivity > NO_DATA_OR_RESPONSE_STALL_MS) {
                        throw new IllegalStateException("Gemからデータ・応答が60秒間届かず通信が停止しました"
                                + " state=" + transfer.debugState()
                                + " saved=" + saved.size()
                                + " bufferedBytes=" + transfer.bufferedBytes());
                    }
                    continue;
                }

                if (MemoketGattSync.DATA.equals(event.uuid)) {
                    lastActivity = System.currentTimeMillis();
                    if ("WAIT_LIST".equals(transfer.debugState())) {
                        earlyAudio.offer(event);
                        continue;
                    }
                    byte[] candidate = transfer.onData(event.value);
                    debug.transferState(transfer, "TEST_DATA_PARSED");
                    if (candidate != null) next = candidate;
                    // Match the existing HCI-derived flow: finish the DATA
                    // stream, then request metadata after a quiet interval.
                    // Do not inject speculative periodic commands mid-stream.
                    continue;
                }
                if (!MemoketGattSync.RESPONSE.equals(event.uuid)) continue;
                lastActivity = System.currentTimeMillis();

                if (event.value.length > 0 && (event.value[0] & 0xff) == 1) {
                    lastListResponseHex = hex(event.value);
                }
                if (event.value.length > 0 && (event.value[0] & 0xff) == 2) {
                    lastMetadataResponseHex = hex(event.value);
                }
                byte[] candidate = transfer.onControl(event.value);
                if (transfer.isDone() && saved.isEmpty()
                        && (observedDataBlocks > 0 || firstDataSequence > 0)) {
                    throw new IllegalStateException("DATA受信中の01 00 01をファイルなしと誤認しません。"
                            + "進行中の旧転送があり、先頭データの回復が必要です。"
                            + " firstSeq=" + firstDataSequence
                            + " blocks=" + observedDataBlocks
                            + " response=" + hex(event.value));
                }
                if (firstAnnouncedFile.isEmpty() && !transfer.debugFileName().isEmpty()) {
                    firstAnnouncedFile = transfer.debugFileName();
                }
                debug.transferState(transfer, "TEST_RESPONSE_PARSED");
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
                debug.gattConnection(status, newState);
                connectionStatus = status;
                connectionState = newState;
                addTrace("CONNECTION", "status=" + status + " state=" + newState);
                connected.countDown();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt connection, int status) {
                debug.servicesDiscovered(status);
                serviceStatus = status;
                addTrace("SERVICES", "status=" + status);
                servicesReady.countDown();
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
                String uuid = descriptor == null || descriptor.getCharacteristic() == null
                        ? "" : descriptor.getCharacteristic().getUuid().toString();
                debug.descriptorResult(uuid, status);
                descriptorStatuses.offer(status);
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
                debug.commandWriteResult(status);
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
                    debug.data(value, "TEST_QUEUE", 0);
                    String sequence = "";
                    if (value != null && value.length >= 5) {
                        long seq = ((long)(value[0] & 0xff) << 32)
                                | ((long)(value[1] & 0xff) << 24)
                                | ((long)(value[2] & 0xff) << 16)
                                | ((long)(value[3] & 0xff) << 8)
                                | (value[4] & 0xff);
                        sequence = " seq=" + seq;
                        if (firstDataSequence < 0) firstDataSequence = seq;
                        observedDataBlocks++;
                    }
                    addTrace("DATA", "bytes=" + (value == null ? 0 : value.length) + sequence);
                } else {
                    if (MemoketGattSync.RESPONSE.equals(uuid)) {
                        debug.response(value, "TEST_DIRECT", "TEST_QUEUE");
                    } else {
                        debug.notification(uuid.toString(), value);
                    }
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
