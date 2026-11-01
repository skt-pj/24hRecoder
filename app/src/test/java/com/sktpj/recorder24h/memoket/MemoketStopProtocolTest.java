package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemoketStopProtocolTest {
    @Test public void physicalConfirmationRequired() {
        MemoketStopProtocol p = new MemoketStopProtocol();
        assertFalse(p.isStopConfirmed());
        assertEquals(MemoketStopProtocol.Next.PHYSICAL_STOP_REQUIRED, p.requestStop());
        assertFalse(p.isStopConfirmed());
        assertEquals(MemoketStopProtocol.Next.READY_TO_RETRIEVE, p.confirmPhysicalStop());
        assertTrue(p.isStopConfirmed());
    }
    @Test(expected=IllegalStateException.class) public void refusesUnrequestedConfirmation() {
        new MemoketStopProtocol().confirmPhysicalStop();
    }
    @Test(expected=IllegalStateException.class) public void refusesDuplicateConfirmation() {
        MemoketStopProtocol p = new MemoketStopProtocol();
        p.requestStop(); p.confirmPhysicalStop(); p.confirmPhysicalStop();
    }
}
