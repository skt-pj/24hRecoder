package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/** Diagnostic acquisition must persist a checked file without sending the destructive 05 ACK. */
public class MemoketDiagnosticTransferTest {
    @Test
    public void oneFileIsSavedAfterMetadataAndNeverAcknowledgedToGem() throws Exception {
        String filename = "20261101_120001_2.opus";
        byte[] name = filename.getBytes(StandardCharsets.US_ASCII);
        byte[] audio = new byte[]{0x4f,0x67,0x67,0x53,0x01,0x02,0x03};
        CRC32 checksum = new CRC32();
        checksum.update(audio);
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = new MemoketTransfer((n, payload, crc) -> {
            assertEquals(filename, n);
            assertArrayEquals(audio, payload);
            assertEquals(checksum.getValue(), crc);
            saved.incrementAndGet();
        }, null, 1, true, true, false);

        byte[] list = new byte[3 + name.length];
        list[0] = 1; list[1] = 1; list[2] = 1;
        System.arraycopy(name, 0, list, 3, name.length);
        assertNull(t.onControl(list));
        byte[] data = new byte[5 + audio.length];
        System.arraycopy(audio, 0, data, 5, audio.length);
        assertNull(t.onData(data));

        byte[] info = new byte[5 + name.length + 8];
        info[0] = 2; info[1] = 0; info[2] = 1;
        info[3] = 0; info[4] = 7;
        System.arraycopy(name, 0, info, 5, name.length);
        int offset = 5 + name.length;
        write32(info, offset, audio.length);
        write32(info, offset + 4, checksum.getValue());
        // Normally a successful save emits CONTROL 05; the independent diagnostic must not.
        assertNull(t.onControl(info));
        assertTrue(t.isDone());
        assertEquals(1, saved.get());
        assertEquals(1, t.completedCount());
        assertEquals(0, t.deleteAcks());
    }

    private static void write32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >> 24);
        data[offset + 1] = (byte) (value >> 16);
        data[offset + 2] = (byte) (value >> 8);
        data[offset + 3] = (byte) value;
    }
}
