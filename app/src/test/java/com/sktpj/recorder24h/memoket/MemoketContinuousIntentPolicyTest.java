package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import static org.junit.Assert.*;

public final class MemoketContinuousIntentPolicyTest {
    private static final String START = "com.sktpj.recorder24h.memoket.START_CONTINUOUS";
    private static final String STOP = "com.sktpj.recorder24h.memoket.STOP_CONTINUOUS";

    @Test public void continuousStartNeverRoutesToStop() {
        assertTrue(MemoketContinuousIntentPolicy.isContinuousStart(START));
        assertFalse(MemoketContinuousIntentPolicy.isContinuousStop(START));
        assertTrue(MemoketContinuousIntentPolicy.isContinuousStop(STOP));
        assertFalse(MemoketContinuousIntentPolicy.isContinuousStart(STOP));
    }

    @Test public void stickyRestartRestoresRequestedGemRecording() {
        assertEquals(START, MemoketContinuousIntentPolicy.resolveAction(null, true, "MEMOKET"));
        assertNull(MemoketContinuousIntentPolicy.resolveAction(null, false, "MEMOKET"));
        assertNull(MemoketContinuousIntentPolicy.resolveAction(null, true, "LOCAL"));
    }

    @Test public void explicitStopIsNeverConvertedToStart() {
        assertEquals(STOP, MemoketContinuousIntentPolicy.resolveAction(STOP, true, "MEMOKET"));
        assertEquals(START, MemoketContinuousIntentPolicy.resolveAction(START, false, "LOCAL"));
    }
}
