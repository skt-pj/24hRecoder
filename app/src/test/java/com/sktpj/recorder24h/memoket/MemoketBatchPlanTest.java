package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.*;
import org.junit.Test;

public class MemoketBatchPlanTest {
    @Test public void onlyKnownStoppedFileTransferIsEnabled() {
        assertEquals(1, MemoketBatchPlan.FILE_CASES.size());
        assertEquals("FILE_ONE", MemoketBatchPlan.FILE_CASES.get(0));
        assertTrue(MemoketBatchPlan.isFileCase("FILE_ONE"));
        assertFalse(MemoketBatchPlan.isFileCase("FILE_LIST"));
        assertFalse(MemoketBatchPlan.isFileCase("FILE_THREE"));
        assertFalse(MemoketBatchPlan.isFileCase("FILE_SPECIFIC"));
    }
    @Test public void noUnverifiedStopCandidateMayRun() {
        assertEquals(0, MemoketBatchPlan.STOP_CASES.size());
        assertFalse(MemoketBatchPlan.isStopCase("STOP_ABC"));
        assertFalse(MemoketBatchPlan.isStopCase("STOP_03_REPEAT"));
        assertFalse(MemoketBatchPlan.isStopCase("STOP_DISCONNECT"));
    }
}
