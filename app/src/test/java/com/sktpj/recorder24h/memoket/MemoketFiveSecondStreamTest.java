package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MemoketFiveSecondStreamTest {
    @Test
    public void metadataBeforeFinalDataWaitsForCompleteCrcThenFinalizesAndAcknowledges() throws Exception {
        String filename = "20261101_162000_2.opus";
        byte[] name = filename.getBytes(StandardCharsets.US_ASCII);
        byte[] audio = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        CRC32 crc = new CRC32();
        crc.update(audio);
        AtomicInteger stored = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((file, payload, checksum) -> {
            assertEquals(filename, file);
            assertArrayEquals(audio, payload);
            assertEquals(crc.getValue(), checksum);
            stored.incrementAndGet();
        }, file -> filename.equals(file), 1, true, false);

        byte[] started = new byte[3 + name.length];
        started[0] = 1; started[1] = 1; started[2] = 1;
        System.arraycopy(name, 0, started, 3, name.length);
        assertNull(transfer.onControl(started));
        assertNull(transfer.onData(packet(0, new byte[]{1,2,3,4,5})));
        assertTrue(transfer.shouldRequestMetadata());

        byte[] metadata = new byte[5 + name.length + 8];
        metadata[0] = 2; metadata[1] = 0; metadata[2] = 1;
        metadata[3] = 0; metadata[4] = 7;
        System.arraycopy(name, 0, metadata, 5, name.length);
        write32(metadata, 5 + name.length, audio.length);
        write32(metadata, 9 + name.length, crc.getValue());
        assertNull(transfer.onControl(metadata));
        assertEquals(0, stored.get());
        assertArrayEquals(new byte[]{3},
                transfer.onData(packet(1, new byte[]{6,7,8,9,10})));
        assertEquals(0, stored.get());

        byte[] listing = new byte[2 + 1 + 2 + 1 + name.length + 4];
        listing[0] = 3; listing[1] = 1;
        listing[2] = 0; listing[3] = 0; listing[4] = 7;
        listing[5] = (byte) name.length;
        System.arraycopy(name, 0, listing, 6, name.length);
        write32(listing, 6 + name.length, audio.length);
        assertNull(transfer.onControl(listing));
        byte[] ack = transfer.onControl(new byte[]{3, (byte) 0xff});
        assertNotNull(ack);
        assertEquals(5, ack[0]);
        assertEquals(1, stored.get());
        assertArrayEquals(new byte[]{0}, transfer.onControl(new byte[]{5,1}));
        assertNull(transfer.onControl(new byte[]{0,0}));
        assertTrue(transfer.isDone());
    }
    private static byte[] packet(int index, byte[] body) {
        byte[] data = new byte[5+body.length];
        data[4] = (byte)index;
        System.arraycopy(body,0,data,5,body.length);
        return data;
    }
    private static void write32(byte[] a, int off, long n) {
        a[off]=(byte)(n>>24); a[off+1]=(byte)(n>>16);
        a[off+2]=(byte)(n>>8); a[off+3]=(byte)n;
    }
}
