package com.sktpj.recorder24h.memoket;

/**
 * Permission to send the ambiguous Gem 0x03 on a user STOP request.
 * A persisted preference or 00 01 busy notification is NOT recording proof.
 */
public final class MemoketStopSafety {
    private MemoketStopSafety() {}

    public static boolean canStop(boolean sameLiveGatt, boolean sessionReady,
            boolean startAcknowledgedInThisService, boolean stopAlreadyPending) {
        return sameLiveGatt && sessionReady && startAcknowledgedInThisService
                && !stopAlreadyPending;
    }
}
