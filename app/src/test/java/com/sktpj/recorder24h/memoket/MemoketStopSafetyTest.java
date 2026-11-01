package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoketStopSafetyTest {
    @Test
    public void ut03NeverStopsOnlyBecausePersistedPreferenceClaimsRecording() {
        assertFalse(MemoketStopSafety.canStop(false, true, false, false));
        assertFalse(MemoketStopSafety.canStop(false, false, true, false));
    }
    @Test
    public void ut04OnlySameLiveAuthenticatedSessionWithAcceptedStartCanStop() {
        assertTrue(MemoketStopSafety.canStop(true, true, true, false));
        assertFalse(MemoketStopSafety.canStop(true, false, true, false));
        assertFalse(MemoketStopSafety.canStop(true, true, false, false));
        assertFalse(MemoketStopSafety.canStop(true, true, true, true));
    }
}
