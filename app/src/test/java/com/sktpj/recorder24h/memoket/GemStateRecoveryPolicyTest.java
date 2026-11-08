package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class GemStateRecoveryPolicyTest {
    @Test public void packageUpdateMarksInterruptedGemStopAndTransferAsNoLongerLive() {
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "STOPPING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "TRANSFERRING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "MONITORING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "RECORDING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "STARTING"));
    }

    @Test public void neverResetLocalRecordingOrAnAlreadyStoppedGemOnPackageReplacement() {
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "LOCAL", "STOPPING"));
        assertTrue(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "STOPPING"));
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "STOPPED"));
        assertFalse(GemStateRecoveryPolicy.interruptedByProcessReplacement(
                "MEMOKET", "ERROR"));
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
