package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.HashSet;

public class MemoketBatchPlanTest {
    @Test public void fileTestsPrecedeRecordingAndCoverAllCases() {
        assertEquals(4, MemoketBatchPlan.FILE_CASES.size());
        assertEquals("FILE_LIST", MemoketBatchPlan.FILE_CASES.get(0));
        assertTrue(MemoketBatchPlan.FILE_CASES.contains("FILE_SPECIFIC"));
        assertTrue(MemoketBatchPlan.FILE_CASES.contains("FILE_ONE"));
        assertTrue(MemoketBatchPlan.FILE_CASES.contains("FILE_THREE"));
    }
    @Test public void everyStopCandidateIsIncludedOnce() {
        assertEquals(14, MemoketBatchPlan.STOP_CASES.size());
        assertEquals(14, new HashSet<>(MemoketBatchPlan.STOP_CASES).size());
        assertEquals("STOP_DISCONNECT", MemoketBatchPlan.STOP_CASES.get(13));
        assertEquals("STOP_OFF_DISCONNECT", MemoketBatchPlan.STOP_CASES.get(12));
    }
    @Test public void batchDoesNotConfuseFileTestsWithStopTests() {
        for (String id : MemoketBatchPlan.FILE_CASES) {
            assertTrue(MemoketBatchPlan.isFileCase(id));
            assertFalse(MemoketBatchPlan.isStopCase(id));
        }
        for (String id : MemoketBatchPlan.STOP_CASES) {
            assertTrue(MemoketBatchPlan.isStopCase(id));
            assertFalse(MemoketBatchPlan.isFileCase(id));
        }
    }
}
