package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * A Gem can record locally without sending a single DATA block. The file is
 * fetched only after the stop-stage metadata response.
 */
public class GemDelayedTransferTest {
    @Test public void firstFileDataAfterStopCompletesVerifiedTransfer() throws Exception {
        GemVoiceGate gate = new GemVoiceGate();
        gate.heardSpeech(10_000L);
        gate.startSent();
        gate.started();
        assertFalse(gate.isFetching());
        assertFalse(GemTransferProgressPolicy.stalled(
                true, false, false, false, 100_000L, 1L));
        gate.stopSent();
        assertTrue(gate.isStopping());
        assertFalse(gate.isFetching());

        String filename = "20261108_120000_2.opus";
        byte[] name = filename.getBytes(StandardCharsets.US_ASCII);
        byte[] audio = new byte[]{10, 20, 30, 40, 50};
        CRC32 crc = new CRC32();
        crc.update(audio);
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((file, payload, checksum) -> {
            assertEquals(filename, file);
            assertArrayEquals(audio, payload);
            assertEquals(crc.getValue(), checksum);
            saved.incrementAndGet();
        }, filename::equals, 1, true, false);

        // 02 can arrive while Gem has not sent a 01 filename or any DATA.
        assertEquals("WAIT_LIST", transfer.debugState());
        assertNull(transfer.onControl(metadata(name, audio.length, crc.getValue())));
        assertTrue(GemTransferProgressPolicy.requestPostStopFetch(true, true,
                true, "WAIT_LIST".equals(transfer.debugState()), false));
        assertArrayEquals(new byte[]{1, 0, 0}, MemoketTransfer.initialCommand());
        gate.fetchStarted();
        assertTrue(gate.isFetching());
        assertFalse(GemTransferProgressPolicy.stalled(
                true, true, true, false, 100_000L, 1L));

        byte[] announced = new byte[3 + name.length];
        announced[0] = 1; announced[1] = 1; announced[2] = 1;
        System.arraycopy(name, 0, announced, 3, name.length);
        assertNull(transfer.onControl(announced));
        byte[] block = new byte[5 + audio.length];
        System.arraycopy(audio, 0, block, 5, audio.length);
        assertNull(transfer.onData(block));
        assertTrue(transfer.shouldRequestMetadata());
        assertArrayEquals(new byte[]{3}, transfer.onControl(
                metadata(name, audio.length, crc.getValue())));

        byte[] listing = new byte[6 + name.length + 4];
        listing[0] = 3; listing[1] = 1;
        listing[2] = 0; listing[3] = 0; listing[4] = 7;
        listing[5] = (byte) name.length;
        System.arraycopy(name, 0, listing, 6, name.length);
        write32(listing, 6 + name.length, audio.length);
        assertNull(transfer.onControl(listing));
        byte[] ack = transfer.onControl(new byte[]{3, (byte) 0xff});
        assertNotNull(ack);
        assertEquals(5, ack[0]);
        assertEquals(1, saved.get());
        assertArrayEquals(new byte[]{0}, transfer.onControl(new byte[]{5, 1}));
        assertNull(transfer.onControl(new byte[]{0, 0}));
        assertTrue(transfer.isDone());
        gate.acquired();
        assertFalse(gate.isFetching());
    }

    private static byte[] metadata(byte[] name, int length, long crc) {
        byte[] out = new byte[5 + name.length + 8];
        out[0] = 2; out[1] = 0; out[2] = 1; out[3] = 0; out[4] = 7;
        System.arraycopy(name, 0, out, 5, name.length);
        write32(out, 5 + name.length, length);
        write32(out, 9 + name.length, crc);
        return out;
    }

    private static void write32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte)(value >> 24);
        bytes[offset + 1] = (byte)(value >> 16);
        bytes[offset + 2] = (byte)(value >> 8);
        bytes[offset + 3] = (byte)value;
    }
}
