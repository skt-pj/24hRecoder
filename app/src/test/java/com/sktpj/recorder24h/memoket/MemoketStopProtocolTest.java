package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemoketStopProtocolTest {
    @Test
    public void completesStopAfterNotificationOffThenOnWithoutFileTransfer() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        assertFalse(stop.isStopped());
        assertEquals(MemoketStopProtocol.Next.ENABLE_DATA,
                stop.onDataNotificationWriteSucceeded(false));
        assertFalse(stop.isStopped());
        assertEquals(MemoketStopProtocol.Next.RECORDING_STOPPED,
                stop.onDataNotificationWriteSucceeded(true));
        assertTrue(stop.isStopped());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsOnBeforeOff() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        stop.onDataNotificationWriteSucceeded(true);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateNotificationCallback() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        stop.onDataNotificationWriteSucceeded(false);
        stop.onDataNotificationWriteSucceeded(false);
    }
}
