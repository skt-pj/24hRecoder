package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class MemoketActiveSessionTest {
    @Test
    public void recordedActiveStatusDoesNotBlockStopOnlyHandshake() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        session.allowActiveRecordingStatus(true);
        assertArrayEquals(new byte[]{0}, session.firstCommand());
        byte[] fileName = "20261101_132937_2.opus".getBytes(StandardCharsets.US_ASCII);
        byte[] status = new byte[4 + fileName.length];
        status[0] = 0; status[1] = 1; status[2] = 0; status[3] = 7;
        System.arraycopy(fileName, 0, status, 4, fileName.length);
        assertArrayEquals(new byte[]{0x27, 0x01}, session.onResponse(status));
        assertTrue(session.busyOrTransferringReported());
        assertFalse(session.initialIdleReported());
        assertEquals("WAIT_2702", session.debugStep());
    }

    @Test
    public void idleStatusStillAdvancesHandshake() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        session.allowActiveRecordingStatus(true);
        assertArrayEquals(new byte[]{0}, session.firstCommand());
        assertArrayEquals(new byte[]{0x27, 0x01}, session.onResponse(new byte[]{0, 0}));
        assertTrue(session.initialIdleReported());
    }

    @Test(expected = IllegalStateException.class)
    public void startOnlyPreservesPreviousIdleGuard() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        session.firstCommand();
        session.onResponse(new byte[]{0, 1, 0, 7});
    }
}
