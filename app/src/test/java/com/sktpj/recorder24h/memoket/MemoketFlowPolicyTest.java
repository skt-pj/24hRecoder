package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoketFlowPolicyTest {
    @Test public void cannotRemotelyStartWithoutVerifiedRemoteStop() {
        assertFalse(MemoketFlowPolicy.remoteStartAllowed());
    }
    @Test public void noUnattendedTransferWhenRecordingStatusIsUnknown() {
        assertFalse(MemoketFlowPolicy.periodicTransferAllowed());
    }
    @Test public void unknownStopNeverEnablesRetrieval() {
        assertFalse(MemoketFlowPolicy.transferAllowed(false, false));
        assertTrue(MemoketFlowPolicy.transferAllowed(false, true));
        assertTrue(MemoketFlowPolicy.transferAllowed(true, false));
    }
    @Test public void noGemAckBeforeAllPersistenceAndIntegrityEvidence() {
        assertFalse(MemoketFlowPolicy.canAcknowledgeFile(false,true,true));
        assertFalse(MemoketFlowPolicy.canAcknowledgeFile(true,false,true));
        assertFalse(MemoketFlowPolicy.canAcknowledgeFile(true,true,false));
        assertTrue(MemoketFlowPolicy.canAcknowledgeFile(true,true,true));
    }
}
