package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoketStopTransferGuardTest {
    @Test
    public void fiveSecondRecordingRejectsLiveStreamingForMinutes() {
        long start = 1791624183842L;
        MemoketStopTransferGuard guard = new MemoketStopTransferGuard(start, start + 4_900L);
        assertTrue(guard.limitBytes() >= 128_000L);
        assertFalse(guard.exceedsRecordedWindow(20_000L));
        assertTrue(guard.exceedsRecordedWindow(869_760L));
    }

    @Test
    public void longRecordingAllowsLegitimatePayloadWithHeadroom() {
        MemoketStopTransferGuard guard = new MemoketStopTransferGuard(100_000L, 3_700_000L);
        assertFalse(guard.exceedsRecordedWindow(14_400_000L));
        assertFalse(guard.exceedsRecordedWindow(21_600_000L));
        assertTrue(guard.exceedsRecordedWindow(35_000_000L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesMissingRecordingTimes() {
        new MemoketStopTransferGuard(0, 123L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesStopBeforeStart() {
        new MemoketStopTransferGuard(300L, 200L);
    }
}
