package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MemoketMetadataGateTest {
    @Test public void neverProbeWhileLiveDataContinuesEvenAfterTenSeconds() {
        long now = 0;
        long lastData = 0;
        for (int i = 0; i < 250; i++) {
            now += 120;
            lastData = now;
            assertFalse("02 forbidden during continuous DATA", MemoketMetadataGate.mayRequest(
                    now, lastData, 0, true, false));
        }
    }
    @Test public void requiresTwoSecondsOfSilenceAndOneSecondSincePreviousProbe() {
        assertFalse(MemoketMetadataGate.mayRequest(1499, 0, 0, true, false));
        assertTrue(MemoketMetadataGate.mayRequest(2000, 0, 0, true, false));
        assertFalse(MemoketMetadataGate.mayRequest(2200, 0, 1700, true, false));
        assertTrue(MemoketMetadataGate.mayRequest(2700, 0, 1700, true, false));
        assertFalse(MemoketMetadataGate.mayRequest(4000, 0, 0, false, false));
        assertFalse(MemoketMetadataGate.mayRequest(4000, 0, 0, true, true));
    }
}
