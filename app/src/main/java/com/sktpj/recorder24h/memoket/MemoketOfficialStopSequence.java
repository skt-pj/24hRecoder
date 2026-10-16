package com.sktpj.recorder24h.memoket;

import java.util.Arrays;

/**
 * Observed official-HCI subscription transition after an acknowledged
 * recording START. This is NOT proof of the Gem's physical recording STOP.
 *
 * PCS Wiki 04 §34: never repeat CONTROL 03 in the STOP phase.
 * Descriptor OFF/ON acknowledgments are GATT subscription acknowledgments,
 * not a firmware STOP acknowledgment. The subsequent file fetch requires
 * its own data/CRC/durable-save verification.
 */
public final class MemoketOfficialStopSequence {
    private int phase; // 0=idle, 1=await DATA OFF, 2=await DATA ON, 3=transfer
    private static final byte[] OFF = {0, 0};
    private static final byte[] ON = {1, 0};

    public synchronized void begin() {
        if (phase != 0) throw new IllegalStateException("Duplicate Gem STOP transition");
        phase = 1;
    }

    /**
     * @return TRUE only when a DATA subscription ON write is next,
     *         NULL when ready for file request 010000.
     *         No CONTROL bytes (especially 03) are produced by STOP.
     */
    public synchronized Boolean onCccdWritten(byte[] actual) {
        if (phase == 1 && Arrays.equals(OFF, actual)) {
            phase = 2;
            return Boolean.TRUE;
        }
        if (phase == 2 && Arrays.equals(ON, actual)) {
            phase = 3;
            return null;
        }
        throw new IllegalStateException("Unexpected DATA CCCD response in STOP transition");
    }

    public synchronized boolean readyForFileTransfer() { return phase == 3; }
    public synchronized boolean hardwareStopConfirmed() { return false; }
}
