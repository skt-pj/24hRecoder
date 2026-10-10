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
            progress(listener, "接続準備", "Gemへ接続しています", false);

            if (Build.VERSION.SDK_INT >= 31 &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth接続権限がありません");
            }
            String address = MemoketSettings.address(context);
            if (address.isEmpty()) throw new IllegalStateException("Memoket Gemが選択されていません");

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
            if (session != null) session.debug.completed(
                    new JSONObject().put("caseId", caseId).put("status", "COMPLETED"));
            AppLogger.event(context, "MEMOKET_TEST_COMPLETED", compact(result));
        } catch (Exception error) {
            if (session != null) session.debug.failure(
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), error);
            try {
                result.put("status", "FAILED");
                result.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                result.put("finishedAtMs", System.currentTimeMillis());
                AppLogger.event(context, "MEMOKET_TEST_FAILED", compact(result));
            } catch (Exception ignored) { }
            progress(listener, "テスト失敗", result.optString("error", "不明なエラー"), false);
        } finally {
            if (session != null) session.close();
            MemoketTestStore.save(context, result);
        }
        return result;
    }

    public static JSONObject runBatch(Context context, ProgressListener listener) {
        long began = System.currentTimeMillis();
        JSONObject report = new JSONObject();
        if (!BATCH_RUNNING.compareAndSet(false, true)) {
            try {
                report.put("caseId", "BATCH_ALL");
                report.put("status", "BUSY_ALREADY_RUNNING");
                report.put("error", "別のMemoket一括診断が実行中です。並列BLE通信を防止しました");
                report.put("startedAtMs", began);
                report.put("finishedAtMs", System.currentTimeMillis());
            } catch (Exception ignored) { }
            progress(listener, "一括診断は実行中", "同時接続による音声転送破損を防止しました", false);
            return report;
        }
        JSONArray cases = new JSONArray();
        int downloaded = 0;
        int errors = 0;
        int executedStops = 0;
        String discoveredFile = "";
        boolean startAcknowledged = false;
        long recordingStartedAtMs = 0L;
        try {
            report.put("id", began + "-BATCH_ALL");
            report.put("caseId", "BATCH_ALL");
            report.put("startedAtMs", began);
            report.put("status", "RUNNING");
            report.put("cases", cases);
            report.put("verification", "BLE operations are not proof of physical stop");
            report.put("fileTransferStage", "BEFORE_RECORDING");
            report.put("gemStopVerified", false);
            report.put("gemFileAckPolicy", "Only after CRC verification and durable local persistence");
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth接続権限がありません");
            }
            String address = MemoketSettings.address(context);
            if (address.isEmpty()) throw new IllegalStateException("Memoket Gemが選択されていません");

            // Gem's initial file list command also starts audio streaming.
            // Observe all four file checks within ONE BLE session to avoid
            // discarding blocks 0..N when the LIST connection closes.
            JSONObject transferResult = new JSONObject();
            Session transferSession = null;
            long transferStart = System.currentTimeMillis();
            String transferError = "";
            try {
                transferResult.put("caseId", "FILE_THREE");
                transferResult.put("startedAtMs", transferStart);
                progress(listener, "ファイル取得", "一覧・音声受信を同じ接続で確認しています", false);
                transferSession = new Session(context, address, transferResult);
                transferSession.connect();
                runFileCase(context, transferSession, "FILE_THREE", "", transferResult, listener);
                downloaded = transferResult.optInt("downloadedFiles", 0);
                discoveredFile = transferSession.firstAnnouncedFile;
                transferSession.debug.completed(new JSONObject().put("downloadedFiles", downloaded));
            } catch (Exception error) {
                transferError = error.getMessage() == null ? error.toString() : error.getMessage();
                errors++;
                if (transferSession != null) transferSession.debug.failure(transferError, error);
            } finally {
                if (transferSession != null) {
                    transferResult.put("traceSessionId", transferSession.debug.sessionId());
                    transferResult.put("trace", transferSession.trace);
                    transferResult.put("firstAnnouncedFile", transferSession.firstAnnouncedFile);
                    transferResult.put("savedFiles", new JSONArray(transferSession.savedFileNames));
                    transferResult.put("dataBlockCount", transferSession.observedDataBlocks);
                    transferResult.put("firstDataSequence", transferSession.firstDataSequence);
                    transferResult.put("listResponseHex", transferSession.lastListResponseHex);
                    transferSession.close();
                }
                transferResult.put("finishedAtMs", System.currentTimeMillis());
                if (!transferError.isEmpty()) transferResult.put("error", transferError);
            }
            for (String fileCase : MemoketBatchPlan.FILE_CASES) {
                JSONObject item = new JSONObject();
                item.put("caseId", fileCase);
                item.put("startedAtMs", transferStart);
                item.put("finishedAtMs", transferResult.optLong("finishedAtMs"));
                item.put("traceSessionId", transferResult.optString("traceSessionId"));
                item.put("sharedGattSession", true);
                item.put("dataBlockCount", transferResult.optInt("dataBlockCount"));
                item.put("firstDataSequence", transferResult.optLong("firstDataSequence", -1));
                if ("FILE_LIST".equals(fileCase)) {
                    item.put("listedFile", discoveredFile);
                    item.put("status", discoveredFile.isEmpty() ? "NOT_VERIFIED" : "OBSERVED");
                    item.put("listResponseHex", transferResult.optString("listResponseHex"));
                } else if ("FILE_SPECIFIC".equals(fileCase)) {
                    boolean matched = transferSession != null
                            && !discoveredFile.isEmpty()
                            && transferSession.savedFileNames.contains(discoveredFile);
                    item.put("targetFile", discoveredFile);
                    item.put("status", matched ? "SAVED_AND_MATCHED" : "NOT_VERIFIED");
                } else if ("FILE_ONE".equals(fileCase)) {
                    item.put("downloadedFiles", downloaded > 0 ? 1 : 0);
                    item.put("status", downloaded > 0 ? "SAVED" : "NOT_VERIFIED");
                } else {
                    item.put("downloadedFiles", downloaded);
                    item.put("savedFiles", transferResult.optJSONArray("savedFiles"));
                    item.put("trace", transferResult.optJSONArray("trace"));
                    item.put("error", transferResult.optString("error"));
                    item.put("status", MemoketBatchAssessment.fileState(downloaded,
                            transferError, transferSession == null ? -1 : transferSession.firstDataSequence));
                }
                cases.put(item);
            }
            report.put("fileTransferCaseMethod", "ONE_CONTINUOUS_GATT_SESSION");
            report.put("fileTransferResult", MemoketBatchAssessment.fileState(downloaded,
                    transferError, transferSession == null ? -1 : transferSession.firstDataSequence));
            report.put("fileTransferMetadataProbeCount",
                    transferSession == null ? 0 : transferSession.metadataProbeCount);
            report.put("fileTransferLastMetadataResponseHex",
                    transferSession == null ? "" : transferSession.lastMetadataResponseHex);
            report.put("fileTransferMayStillBeStreaming",
                    downloaded == 0 && transferSession != null
                            && transferSession.observedDataBlocks > 0);
            if (downloaded == 0 && transferSession != null && transferSession.firstDataSequence > 0) {
                report.put("fileStreamState", "PARTIAL_STREAM_ALREADY_ACTIVE");
                report.put("fileStreamFirstSequence", transferSession.firstDataSequence);
            }

            for (String id : MemoketBatchPlan.STOP_CASES) {
                JSONObject item = new JSONObject();
                Session session = null;
                item.put("caseId", id);
                item.put("startedAtMs", System.currentTimeMillis());
                cases.put(item);
                if (!startAcknowledged && recordingStartedAtMs != 0L) {
                    item.put("status", "SKIPPED_UNVERIFIED_RECORDING_START");
                    item.put("finishedAtMs", System.currentTimeMillis());
                    continue;
                }
                progress(listener, id, stopLabel(id), false);
                try {
                    session = new Session(context, address, item);
                    session.connect();
                    session.enableData(true);
                    session.enableResponse(true);
                    session.handshakeStandard();
                    if (recordingStartedAtMs == 0L) {
                        progress(listener, "録音開始", "03を一度だけ送り5秒間録音します", false);
                        recordingStartedAtMs = System.currentTimeMillis();
                        item.put("startResponseHex", hex(session.exchange(new byte[]{0x03}, 0x03, 4_000).value));
                        startAcknowledged = true;
                        report.put("recordingStartedAtMs", recordingStartedAtMs);
                        Thread.sleep(5_000);
                    }
                    item.put("candidateAtMs", System.currentTimeMillis());
                    item.put("candidateLabel", stopLabel(id));
                    executeStopCandidate(session, id);
                    Thread.sleep(800L);
                    item.put("status", "EXECUTED_UNVERIFIED");
                    item.put("stopVerified", false);
                    executedStops++;
                    session.debug.completed(new JSONObject().put("caseId", id)
                            .put("stopVerified", false));
                } catch (Exception error) {
                    errors++;
                    item.put("status", "FAILED");
                    item.put("error", error.getMessage() == null ? error.toString() : error.getMessage());
                    if (session != null) session.debug.failure(item.optString("error"), error);
                } finally {
                    if (session != null) {
                        item.put("traceSessionId", session.debug.sessionId());
                        item.put("trace", session.trace);
                        session.close();
                    }
                    item.put("finishedAtMs", System.currentTimeMillis());
                }
            }
            report.put("status", "PARTIAL"); // BLE control actions alone do not verify physical stop.
            report.put("failedCases", errors);
            report.put("executedStopCandidates", executedStops);
            report.put("totalCases", cases.length());
            report.put("downloadedFiles", downloaded);
            report.put("fileAcquisitionVerified", downloaded > 0);
            report.put("stopVerificationRequired", true);
            report.put("stopObserved", "UNSET");
            report.put("stopInference", "停止は未確定です。録音継続中ならGem本体で停止してください");
            report.put("requiresPhysicalStop", startAcknowledged);
            report.put("afterStopFileTransferAttempted", false);
            report.put("candidateTrialsIndependent", false);
            report.put("finishedAtMs", System.currentTimeMillis());
            progress(listener, "一括診断完了", "結果・全候補の時系列ログをコピーできます", false);
            AppLogger.event(context, "MEMOKET_BATCH_COMPLETED", new JSONObject()
                    .put("cases", cases.length()).put("failed", errors)
                    .put("stopCandidates", executedStops)
                    .put("downloadedFiles", downloaded)
                    .put("gemStopVerified", false));
        } catch (Exception error) {
            try {
                report.put("status", "FAILED");
                report.put("error", error.getMessage() == null ? error.toString() : error.getMessage());
                report.put("finishedAtMs", System.currentTimeMillis());
                AppLogger.event(context, "MEMOKET_BATCH_FAILED",
                        new JSONObject().put("error", report.optString("error")));
            } catch (Exception ignored) { }
            progress(listener, "一括診断失敗", report.optString("error"), false);
        } finally {
            try {
                MemoketTestStore.save(context, report);
            } finally {
                BATCH_RUNNING.set(false);
            }
        }
        return report;
    }

    private static void runStartCase(
            Context context,
            Session s,
            String caseId,
            Set<String> before,
            JSONObject result,
            ProgressListener listener
    ) throws Exception {
        progress(listener, "通知設定", startLabel(caseId), false);

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

        progress(listener, "録音開始コマンド", "0x03 を送信します。今この瞬間の振動回数を覚えてください", true);
        long commandAt = System.currentTimeMillis();
        s.write(new byte[]{0x03});
        if (s.responseEnabled) {
            Event response = s.waitResponse(0x03, commandAt, 4_000);
            result.put("startResponseHex", hex(response.value));
        } else {
            result.put("startResponseHex", "notification disabled");
        }

        progress(listener, "録音確認", "5秒間録音します。最後の未検証の停止手順の振動は開始時の振動回数に含めないでください", false);
        Thread.sleep(5_000);

        progress(listener, "未検証の停止手順", "既知の OFF→ON→01 00 00 で録音を確定し、音声を取得します", false);
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
        progress(listener, "結果解析", duration >= 3_000
                ? "新しい録音ファイルを確認しました"
                : "3秒以上の新規録音を確認できませんでした", false);
    }

    /**
     * Independent hypothesis trial. Never auto-retrieve, auto-finalize, or send
     * 0x05 ACK: CCCD switching is not proven to stop hardware recording.
     */
    private static void runStopCase(Context context, Session s, String caseId,
            Set<String> before, JSONObject result, ProgressListener listener) throws Exception {
        progress(listener, "接続・認証", "Gem本体が停止していることを事前に確認してください", false);
        s.enableData(true);
        s.enableResponse(true);
        s.handshakeStandard();
        progress(listener, "録音開始", "03を送信し、5秒録音します", false);
        long startAt = System.currentTimeMillis();
        Event started = s.exchange(new byte[]{0x03}, 0x03, 4_000);
        result.put("startCommandAtMs", startAt);
        result.put("startResponseHex", hex(started.value));
        result.put("startAckIsStopProof", false);
        Thread.sleep(5_000);
        progress(listener, "停止候補", stopLabel(caseId) + " を1回実行します", false);
        result.put("candidateAtMs", System.currentTimeMillis());
        result.put("candidateLabel", stopLabel(caseId));
        executeStopCandidate(s, caseId);
        progress(listener, "10秒間観測", "この間、候補以外のBLEコマンドは送りません", false);
        Thread.sleep(10_000);
        result.put("observationEndedAtMs", System.currentTimeMillis());
        result.put("stopInference", "録音ファイルの検証待ち：BLE応答だけでは停止を証明できません");
        result.put("stopObserved", "UNSET");
        result.put("downloadedFiles", 0);
        result.put("gemFileAckSent", false);
        progress(listener, "観測終了", "Gemが録音中なら本体ボタンで停止後、今回の録音ファイルを取り込み、結果画面で長さを検証してください", false);
    }

    private static void executeStopCandidate(Session s, String id) throws Exception {
        switch (id) {
            case "STOP_A": s.enableData(false); break;
            case "STOP_B": s.enableData(true); break;
            case "STOP_C": s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_AB": s.enableData(false); s.enableData(true); break;
            case "STOP_AC": s.enableData(false); s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_BC": s.enableData(true); s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_ABC": s.enableData(false); s.enableData(true); s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_OFFICIAL_TIMING":
                s.enableData(false); Thread.sleep(400); s.enableData(true); Thread.sleep(270);
                s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_OFF_WAIT_ON": s.enableData(false); Thread.sleep(1000); s.enableData(true); break;
            case "STOP_ON_OFF": s.enableData(true); Thread.sleep(400); s.enableData(false); break;
            case "STOP_LIST_DELAY":
                s.enableData(false); Thread.sleep(1000); s.enableData(true); Thread.sleep(1000);
                s.write(new byte[]{0x01,0x00,0x00}); break;
            case "STOP_03_REPEAT": s.write(new byte[]{0x03}); break;
            case "STOP_DISCONNECT": s.close(); break;
            case "STOP_OFF_DISCONNECT": s.enableData(false); s.close(); break;
            default: throw new IllegalArgumentException("Unknown stop case " + id);
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
        progress(listener, "接続・認証", "ファイル取得用セッションを準備します", false);
        s.enableData(true);
        s.enableResponse(true);
        s.handshakeStandard();

        if ("FILE_LIST".equals(caseId)) {
            Event event = s.exchange(new byte[]{0x01, 0x00, 0x00}, 0x01, 5_000);
            result.put("listResponseHex", hex(event.value));
            result.put("listedFile", parseListName(event.value));
            result.put("downloadedFiles", 0);
            progress(listener, "一覧取得", result.optString("listedFile", "ファイルなし"), false);
            return;
        }

        int max = "FILE_THREE".equals(caseId) ? 3 : "FILE_SPECIFIC".equals(caseId) ? 50 : 1;
        String target = "FILE_SPECIFIC".equals(caseId) ? requestedFile : null;
        if (target != null && target.trim().isEmpty()) {
            throw new IllegalArgumentException("取得するファイル名を入力してください");
        }
        progress(listener, "ファイル取得", target == null
                ? max + "件まで取得します"
                : "指定ファイルに到達するまで順に取得します", false);
        int downloaded = s.downloadPending(context, max, target);
        result.put("downloadedFiles", downloaded);
        if (target != null) result.put("requestedFile", target);
        progress(listener, "取得完了", downloaded + "件取得しました", false);
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
                debug.filePersisted(name, payload.length, crc);
                saved.add(name);
                savedFileNames.add(name);
            });

            byte[] next = MemoketTransfer.initialCommand();
            final long MAX_DIAGNOSTIC_TRANSFER_MS = 90_000L;
            final long METADATA_PROBE_INTERVAL_MS = 10_000L;
            long deadline = System.currentTimeMillis() + MAX_DIAGNOSTIC_TRANSFER_MS;
            long lastMetadataProbeAt = System.currentTimeMillis();
            long lastActivity = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                if (next != null) {
                    if (next.length > 0 && next[0] == 0x02) {
                        metadataProbeCount++;
                        lastMetadataProbeAt = System.currentTimeMillis();
                    }
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
                        throw new IllegalStateException("ファイル転送応答がタイムアウトしました");
                    }
                    continue;
                }
                lastActivity = System.currentTimeMillis();

                if (MemoketGattSync.DATA.equals(event.uuid)) {
                    byte[] candidate = transfer.onData(event.value);
                    debug.transferState(transfer, "TEST_DATA_PARSED");
                    if (candidate != null) next = candidate;
                    // In v0.7.96 continuous DATA occupied the queue for 90 seconds,
                    // so the idle-only metadata probe never ran. Probe conservatively
                    // while streaming (no faster than once every 10 seconds).
                    if (next == null && transfer.shouldRequestMetadata()
                            && System.currentTimeMillis() - lastMetadataProbeAt
                            >= METADATA_PROBE_INTERVAL_MS) {
                        next = MemoketTransfer.metadataCommand();
                    }
                    continue;
                }
                if (!MemoketGattSync.RESPONSE.equals(event.uuid)) continue;

                if (event.value.length > 0 && (event.value[0] & 0xff) == 1) {
                    lastListResponseHex = hex(event.value);
                }
                if (event.value.length > 0 && (event.value[0] & 0xff) == 2) {
                    lastMetadataResponseHex = hex(event.value);
                }
                byte[] candidate = transfer.onControl(event.value);
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
            if (saved.isEmpty() && transfer.dataBlockCount() > 0 && !transfer.isDone()) {
                throw new IllegalStateException(
                        "音声DATAは" + transfer.dataBlockCount() + "ブロック受信しましたが"
                        + "90秒以内にファイル完了とCRC照合まで進めません。"
                        + " filename=" + transfer.debugFileName()
                        + " bufferedBytes=" + transfer.bufferedBytes()
                        + " firstSeq=" + firstDataSequence
                        + " metadataProbes=" + metadataProbeCount
                        + " lastMetadata=" + lastMetadataResponseHex
                        + " state=" + transfer.debugState()
                        + "。Gemの録音が続いているか、長時間ファイルの転送が未完了の可能性があります");
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
