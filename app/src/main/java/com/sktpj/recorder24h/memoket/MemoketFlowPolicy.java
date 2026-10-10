package com.sktpj.recorder24h.memoket;

/**
 * Fail-closed feature gates based on currently verified Gem protocol evidence.
 * Only recording START (idle 0x03 -> 0x03ff) is known; STOP is unknown.
 *
 * Never enable one-sided remote START or unattended transfer until the Gem's
 * true recording state and stop transitions are independently verified.
 */
public final class MemoketFlowPolicy {
    private static final boolean VERIFIED_REMOTE_STOP = false;
    private static final boolean VERIFIED_RECORDING_STATUS_QUERY = false;

    private MemoketFlowPolicy() {}

    public static boolean remoteStartAllowed() {
        return VERIFIED_REMOTE_STOP && VERIFIED_RECORDING_STATUS_QUERY;
    }

    public static boolean periodicTransferAllowed() {
        return VERIFIED_RECORDING_STATUS_QUERY;
    }

    /** A human confirmation is a fallback, not a device status readback. */
    public static boolean transferAllowed(boolean verifiedDeviceStopped,
            boolean userConfirmedPhysicalStop) {
        return verifiedDeviceStopped || userConfirmedPhysicalStop;
    }

    public static boolean canAcknowledgeFile(boolean crcVerified,
            boolean durableSaveCompleted, boolean transferFinalized) {
        return crcVerified && durableSaveCompleted && transferFinalized;
    }
}
