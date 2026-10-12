package com.sktpj.recorder24h.memoket;

/**
 * Fail-closed feature gates based on currently verified Gem protocol evidence.
 * The official HCI sample supports explicit START + same-GATT transfer.
 * A successful file ACK is NOT proof that the Gem stopped recording.
 * Remote START is enabled only for user-initiated protocol verification.
 * Automatic / unattended transfer remains disabled.
 */
public final class MemoketFlowPolicy {
    private static final boolean EXPLICIT_OFFICIAL_HCI_REPLAY = true;
    private static final boolean VERIFIED_RECORDING_STATUS_QUERY = false;

    private MemoketFlowPolicy() {}

    public static boolean remoteStartAllowed() {
        return EXPLICIT_OFFICIAL_HCI_REPLAY;
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
