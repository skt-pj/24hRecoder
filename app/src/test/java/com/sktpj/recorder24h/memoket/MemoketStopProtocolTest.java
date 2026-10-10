package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemoketStopProtocolTest {
    @Test
    public void stopDisablesNotificationsAndNeverRequestsReenableOnSameConnection() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        assertFalse(stop.notificationDisabled());
        assertEquals(MemoketStopProtocol.Next.DISCONNECT_BEFORE_TRANSFER,
                stop.onDataNotificationWriteSucceeded(false));
        assertTrue(stop.notificationDisabled());
    }

    @Test(expected = IllegalStateException.class)
    public void forbidsImmediateReenableWhichRestartedRecordingOnGem() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        stop.onDataNotificationWriteSucceeded(false);
        stop.onDataNotificationWriteSucceeded(true);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsOnBeforeOff() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        stop.onDataNotificationWriteSucceeded(true);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateOff() {
        MemoketStopProtocol stop = new MemoketStopProtocol();
        stop.begin();
        stop.onDataNotificationWriteSucceeded(false);
        stop.onDataNotificationWriteSucceeded(false);
    }
}
