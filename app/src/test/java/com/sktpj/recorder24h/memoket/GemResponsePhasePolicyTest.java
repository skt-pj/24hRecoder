package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GemResponsePhasePolicyTest {
    @Test public void gattWriteAloneCannotConfirmStart() {
        assertFalse(GemResponsePhasePolicy.receivedFileAnnouncement(
                null, "WAIT_LIST"));
        assertFalse(GemResponsePhasePolicy.receivedFileAnnouncement(
                new byte[]{1, 1, 1}, "WAIT_LIST"));
        assertFalse(GemResponsePhasePolicy.receivedFileAnnouncement(
                new byte[]{1, 1, 1, 65}, "WAIT_LIST"));
        assertFalse(GemResponsePhasePolicy.receivedFileAnnouncement(
                new byte[]{2, 0, 0}, "RECEIVING_DATA"));
    }

    @Test public void validatedFileAnnouncementCanAdvanceMicGate() throws Exception {
        String file = "20261108_001021_2.opus";
        byte[] name = file.getBytes(StandardCharsets.US_ASCII);
        byte[] reply = new byte[3 + name.length];
        reply[0] = 1; reply[1] = 1; reply[2] = 1;
        System.arraycopy(name, 0, reply, 3, name.length);
        MemoketTransfer transfer = new MemoketTransfer((filename, data, crc) -> {
        }, file::equals, 1, true, false);
        assertNull(transfer.onControl(reply));
        assertTrue(GemResponsePhasePolicy.receivedFileAnnouncement(
                reply, transfer.debugState()));
        assertEquals("RECEIVING_DATA", transfer.debugState());
    }

    @Test public void observed020000IsNotMetadataSuccessAndRetriesAreBounded() {
        byte[] observed = new byte[]{2, 0, 0};
        assertTrue(GemResponsePhasePolicy.is020000(observed));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 1}));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 2}));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 0, 0}));
        assertFalse(GemResponsePhasePolicy.exceeded020000Limit(1));
        assertFalse(GemResponsePhasePolicy.exceeded020000Limit(2));
        assertTrue(GemResponsePhasePolicy.exceeded020000Limit(3));
    }
}
