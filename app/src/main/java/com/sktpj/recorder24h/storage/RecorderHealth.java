package com.sktpj.recorder24h.storage;

import org.json.JSONObject;

public final class RecorderHealth {
    public static final long HEARTBEAT_STALE_MS = 15_000L;
    public static final long AUDIO_READ_STALE_MS = 15_000L;
    public static final long STARTING_STALE_MS = 30_000L;
    public static final long ROTATING_STALE_MS = 30_000L;
    public static final long SEGMENT_OVERDUE_MS = 6L * 60L * 1000L + 30_000L;

    private RecorderHealth() {
    }

    public static Snapshot evaluate(JSONObject state, boolean requested, long nowMs) {
        String rawState = state.optString("state", "STOPPED");
        long heartbeatMs = state.optLong("heartbeatMs", 0L);
        long stateChangedAtMs = state.optLong("stateChangedAtMs", 0L);
        long audioReadMs = state.optLong("lastAudioReadMs", 0L);
        long segmentStartedAtMs = state.optLong("currentSegmentStartedAtMs", 0L);
        boolean captureSilenced = state.optBoolean("captureSilenced", false);
        String error = state.optString("error", "");

        long heartbeatAge = age(nowMs, heartbeatMs);
        long audioAge = age(nowMs, audioReadMs);
        long segmentAge = age(nowMs, segmentStartedAtMs);
        long stateAge = age(nowMs, stateChangedAtMs);
        boolean active = requested || "STARTING".equals(rawState) || "RECORDING".equals(rawState)
                || "ROTATING".equals(rawState) || "RECOVERING".equals(rawState)
                || "STOPPING".equals(rawState);

        if ("ERROR".equals(rawState)) {
            String detail = isBlank(error) ? "録音処理でエラーが発生しました。" : error;
            return problem("ERROR", "録音エラー", detail, active, heartbeatAge, audioAge, segmentAge);
        }
        if (!requested && "STOPPED".equals(rawState)) {
            return neutral("STOPPED", "録音停止中", "録音は停止しています。", false,
                    heartbeatAge, audioAge, segmentAge);
        }
        if ("STOPPING".equals(rawState)) {
            return neutral("STOPPING", "録音を停止中", "現在の音声セグメントを確定しています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if ("RECOVERING".equals(rawState)) {
            return neutral("RECOVERING", "録音を復旧中", "録音サービスの復旧状態を確認しています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if ("STARTING".equals(rawState)) {
            if (stateAge > STARTING_STALE_MS) {
                return problem("STARTING_STALE", "録音開始を確認できません",
                        "録音開始から30秒以上経過しました。マイク開始を確認できていません。", true,
                        heartbeatAge, audioAge, segmentAge);
            }
            return neutral("STARTING", "録音を開始中", "マイクとエンコーダを準備しています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (requested && !"RECORDING".equals(rawState) && !"ROTATING".equals(rawState)) {
            return problem("STATE_MISMATCH", "録音状態を確認できません",
                    "録音継続が要求されていますが、録音サービスの状態が一致していません。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (heartbeatMs <= 0L || heartbeatAge > HEARTBEAT_STALE_MS) {
            return problem("HEARTBEAT_STALE", "録音が止まった可能性があります",
                    "録音heartbeatが15秒以上更新されていません。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (captureSilenced) {
            return problem("CAPTURE_SILENCED", "マイク入力が無音化されています",
                    "Androidから録音入力のsilenced状態が通知されています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (audioReadMs > 0L && audioAge > AUDIO_READ_STALE_MS) {
            return problem("AUDIO_READ_STALE", "音声入力が止まった可能性があります",
                    "AudioRecordから15秒以上音声データを取得できていません。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if ("ROTATING".equals(rawState)) {
            if (stateAge > ROTATING_STALE_MS) {
                return problem("ROTATION_STALE", "5分セグメント切替が停止しています",
                        "セグメント切替状態が30秒以上続いています。", true,
                        heartbeatAge, audioAge, segmentAge);
            }
            return neutral("ROTATING", "録音中・セグメント切替", "5分音声を確定して次のセグメントへ切り替えています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (segmentStartedAtMs <= 0L && stateAge > STARTING_STALE_MS) {
            return problem("SEGMENT_MISSING", "録音ファイルを確認できません",
                    "録音状態ですが、現在の5分セグメントが開始されていません。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        if (segmentStartedAtMs > 0L && segmentAge > SEGMENT_OVERDUE_MS) {
            return problem("SEGMENT_OVERDUE", "5分セグメント更新が止まっています",
                    "現在の音声セグメントが6分30秒を超えて継続しています。", true,
                    heartbeatAge, audioAge, segmentAge);
        }
        return new Snapshot("HEALTHY", "録音中・正常",
                "マイク入力・heartbeat・5分セグメント更新を監視しています。",
                true, false, true, heartbeatAge, audioAge, segmentAge);
    }

    private static Snapshot problem(String code, String label, String detail, boolean active,
                                    long heartbeatAge, long audioAge, long segmentAge) {
        return new Snapshot(code, label, detail, false, true, active,
                heartbeatAge, audioAge, segmentAge);
    }

    private static Snapshot neutral(String code, String label, String detail, boolean active,
                                    long heartbeatAge, long audioAge, long segmentAge) {
        return new Snapshot(code, label, detail, false, false, active,
                heartbeatAge, audioAge, segmentAge);
    }

    private static long age(long nowMs, long timestampMs) {
        if (timestampMs <= 0L) return Long.MAX_VALUE;
        return Math.max(0L, nowMs - timestampMs);
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty() || "null".equals(text);
    }

    public static final class Snapshot {
        public final String code;
        public final String label;
        public final String detail;
        public final boolean healthy;
        public final boolean problem;
        public final boolean active;
        public final long heartbeatAgeMs;
        public final long audioReadAgeMs;
        public final long segmentAgeMs;

        Snapshot(String code, String label, String detail, boolean healthy, boolean problem,
                 boolean active, long heartbeatAgeMs, long audioReadAgeMs, long segmentAgeMs) {
            this.code = code;
            this.label = label;
            this.detail = detail;
            this.healthy = healthy;
            this.problem = problem;
            this.active = active;
            this.heartbeatAgeMs = heartbeatAgeMs;
            this.audioReadAgeMs = audioReadAgeMs;
            this.segmentAgeMs = segmentAgeMs;
        }
    }
}
