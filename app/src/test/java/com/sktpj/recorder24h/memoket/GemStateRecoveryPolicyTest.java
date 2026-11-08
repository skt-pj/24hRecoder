package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class GemStateRecoveryPolicyTest {
    @Test public void packageUpdateMarksInterruptedGemStopAndTransferAsNoLongerLive() {
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "STOPPING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "TRANSFERRING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "MONITORING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "RECORDING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "STARTING"));
    }

    @Test public void neverResetLocalRecordingOrAnAlreadyStoppedGemOnPackageReplacement() {
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "LOCAL", true, "STOPPING"));
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", false, "STOPPING"));
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "STOPPED"));
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", true, "ERROR"));
    }

    @Test public void manualAbortIsLimitedToGemStopAndFileFetchStages() {
        assertTrue(GemStateRecoveryPolicy.requiresExplicitLocalAbort(
                "MEMOKET", "STOPPING"));
        assertTrue(GemStateRecoveryPolicy.requiresExplicitLocalAbort(
                "MEMOKET", "TRANSFERRING"));
        assertFalse(GemStateRecoveryPolicy.requiresExplicitLocalAbort(
                "MEMOKET", "RECORDING"));
        assertFalse(GemStateRecoveryPolicy.requiresExplicitLocalAbort(
                "MEMOKET", "MONITORING"));
        assertFalse(GemStateRecoveryPolicy.requiresExplicitLocalAbort(
                "LOCAL", "STOPPING"));
    }
}
