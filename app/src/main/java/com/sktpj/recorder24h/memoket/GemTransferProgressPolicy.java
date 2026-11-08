package com.sktpj.recorder24h.memoket;

/**
 * Gem recording is not a real-time microphone stream. Transfer progress may
 * only be evaluated after the stop metadata was received AND actual file DATA
 * has arrived after stop. VAD silence is not a BLE transfer timeout.
 */
final class GemTransferProgressPolicy {
    static final long TRANSFER_STALL_MS = 60_000L;
    static final long METADATA_RETRY_MS = 5_000L;

    private GemTransferProgressPolicy() {}

    static boolean stalled(boolean micDriven, boolean stopRequested,
            boolean metadataConfirmed, boolean transferDataSeen,
            long nowMs, long lastProgressAtMs) {
        if (lastProgressAtMs <= 0L) return false;
        if (micDriven && (!stopRequested || !metadataConfirmed || !transferDataSeen)) {
            return false;
        }
        return nowMs - lastProgressAtMs > TRANSFER_STALL_MS;
    }

    static boolean retryMetadata(boolean stopRequested, boolean micDriven,
            boolean metadataConfirmed, boolean transferNeedsMetadata,
            boolean inFlight, boolean commandIdle, long nowMs, long lastRequestAtMs) {
        return stopRequested && !inFlight && commandIdle
                && (micDriven && !metadataConfirmed || transferNeedsMetadata)
                && nowMs - lastRequestAtMs >= METADATA_RETRY_MS;
    }
}
