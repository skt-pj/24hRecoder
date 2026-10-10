package com.sktpj.recorder24h.memoket;

/**
 * Safety bound for a stopped Gem recording. The observed 80-byte / 20-ms
 * Opus cadence is approximately 4 bytes/ms. A completed 5-second recording
 * must not grow to multiple minutes of audio after a stop request.
 *
 * This does not send any BLE command or assert that Gem actually stopped.
 */
public final class MemoketStopTransferGuard {
    private static final long MAX_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_BYTES = 128L * 1024L;
    // 2x the observed 32 kbps with 32 seconds of extra headroom.
    private static final long BYTES_PER_MS_BUDGET = 8L;

    private final long recordedDurationMs;
    private final long limitBytes;

    public MemoketStopTransferGuard(long startedAtMs, long stoppedAtMs) {
        if (startedAtMs <= 0 || stoppedAtMs < startedAtMs) {
            throw new IllegalArgumentException("録音開始・停止時刻が不正です");
        }
        recordedDurationMs = stoppedAtMs - startedAtMs;
        long budget = recordedDurationMs > (Long.MAX_VALUE - MIN_BYTES) / BYTES_PER_MS_BUDGET
                ? Long.MAX_VALUE : recordedDurationMs * BYTES_PER_MS_BUDGET + MIN_BYTES;
        limitBytes = Math.min(MAX_BYTES, Math.max(MIN_BYTES, budget));
    }

    public boolean exceedsRecordedWindow(long receivedBytes) {
        return receivedBytes > limitBytes;
    }

    public long recordedDurationMs() { return recordedDurationMs; }
    public long limitBytes() { return limitBytes; }
}
