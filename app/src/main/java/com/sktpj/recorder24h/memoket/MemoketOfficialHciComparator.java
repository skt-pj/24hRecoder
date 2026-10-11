package com.sktpj.recorder24h.memoket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks every diagnostic trace against the project's documented,
 * partial reference derived from previously observed official HCI traffic.
 * It never claims that the current official app's raw HCI was captured.
 */
public final class MemoketOfficialHciComparator {
    private MemoketOfficialHciComparator() {}

    public static JSONObject compare(JSONArray trace, String caseId,
                                     int savedFiles, long firstDataSequence) throws Exception {
        JSONObject report = new JSONObject();
        report.put("caseId", caseId);
        report.put("reference", "PROJECT_WIKI_PRIOR_OFFICIAL_HCI_OBSERVATION_PARTIAL");
        report.put("referenceRawHciInThisRun", false);
        report.put("officialTransport", "Wi-Fi and BLE supported; actual reference path not verified");
        report.put("appTransport", "BLE GATT");
        report.put("transportAssessment", "BLE is appropriate for this small-file diagnostic; Wi-Fi not required");
        JSONArray checks = new JSONArray();
        report.put("checks", checks);

        List<String> writes = new ArrayList<>();
        List<String> notifications = new ArrayList<>();
        boolean dataOn = false;
        boolean responseOn = false;
        boolean dataSeen = false;
        if (trace != null) {
            for (int i = 0; i < trace.length(); i++) {
                JSONObject e = trace.optJSONObject(i);
                if (e == null) continue;
                String kind = e.optString("kind");
                String value = e.optString("value");
                if ("WRITE".equals(kind)) writes.add(value);
                else if ("NOTIFY".equals(kind)) notifications.add(value);
                else if ("DATA".equals(kind)) dataSeen = true;
                else if ("CCCD".equals(kind)) {
                    if ("DATA=ON".equals(value)) dataOn = true;
                    if ("RESPONSE=ON".equals(value)) responseOn = true;
                }
            }
        }

        add(checks, "通知設定", "DATA/RESPONSE通知ON",
                dataOn && responseOn ? "一致" : "未確認",
                "DATA=" + dataOn + " RESPONSE=" + responseOn);
        // The reference handshake expects 00 00 in idle state.
        // Latest Gem logs emit 00 01 + a filename while a prior stream is
        // still in progress. That difference must not be hidden by an
        // opcode-only handshake comparison.
        String session00 = "";
        for (String notify : notifications) {
            int colon = notify.indexOf(':');
            String hex = colon < 0 ? notify : notify.substring(colon + 1);
            if (hex.startsWith("0000") || hex.startsWith("0001")) {
                session00 = hex;
                break;
            }
        }
        add(checks, "00待機状態", "00 00（既存公式HCI参照の待機応答）",
                session00.startsWith("0000") ? "一致"
                        : session00.startsWith("0001") ? "差分あり（先行転送中）" : "未確認",
                session00.isEmpty() ? "応答なし" : session00);
        String[] reference = {"00", "2701", "e1", "f300", "e301", "ff68", "e5", "e8"};
        int pos = 0;
        for (String written : writes) {
            if (pos >= reference.length) break;
            if (written.startsWith(reference[pos])) pos++;
        }
        add(checks, "認証シーケンス", "00→2701→e1→f300→e301→ff68→e5→e8",
                pos == reference.length ? "一致" : "差分あり",
                "確認済み " + pos + "/" + reference.length
                        + "、観測WRITE=" + String.join(" ", writes));

        if (caseId != null && (caseId.startsWith("FILE_") || caseId.equals("BATCH_ALL"))) {
            boolean requestedList = writes.contains("010000");
            boolean fileNameReceived = false;
            boolean emptyReceived = false;
            for (String notify : notifications) {
                int colon = notify.indexOf(':');
                String hex = colon < 0 ? notify : notify.substring(colon + 1);
                if (hex.startsWith("010101")) fileNameReceived = true;
                if (hex.startsWith("010001")) emptyReceived = true;
            }
            add(checks, "ファイル一覧",
                    "01 00 00からファイル名・DATAへ移行",
                    fileNameReceived ? "一致" : requestedList && emptyReceived && dataSeen
                            ? "矛盾あり" : "未確認",
                    "要求=" + requestedList + " ファイル名=" + fileNameReceived
                            + " 01 00 01=" + emptyReceived + " DATA受信=" + dataSeen);
            add(checks, "音声先頭連番", "完全転送は連番0から",
                    firstDataSequence == 0 ? "一致" : firstDataSequence > 0
                            ? "不一致" : "未確認",
                    firstDataSequence < 0 ? "未受信" : Long.toString(firstDataSequence));
            boolean metadata = writes.contains("0200");
            boolean finalized = writes.contains("03");
            boolean ack = false;
            for (String written : writes) if (written.startsWith("05")) ack = true;
            add(checks, "サイズとCRC", "02で取得後にサイズ/CRCを照合",
                    savedFiles > 0 && metadata ? "保存済み" : "未検証",
                    "照会=" + metadata + " 保存=" + savedFiles);
            add(checks, "完了ACK", "検証・永続保存後のみ03/05",
                    ack && savedFiles == 0 ? "重大な差分" : savedFiles > 0 && finalized && ack
                            ? "一致" : "未検証",
                    "03=" + finalized + " 05=" + ack + " 保存=" + savedFiles);
        }
        report.put("result", savedFiles > 0 ? "音声保存済み／公式HCI原本との完全一致は未検証"
                : "公式参照手順との比較完了／音声保存・公式原本一致は未検証");
        report.put("officialRawHciRequiredForExactDiff", true);
        return report;
    }

    private static void add(JSONArray checks, String label, String expected,
                            String status, String observed) throws Exception {
        checks.put(new JSONObject().put("label", label).put("expected", expected)
                .put("status", status).put("observed", observed));
    }
}
