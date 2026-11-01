package com.sktpj.recorder24h.memoket;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

public class MemoketDiskTransferTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String NAME = "20261102_070000_2.opus";

    private static byte[] frame(int sequence, byte[] payload) {
        byte[] result = new byte[payload.length + 5];
        result[0] = (byte) (sequence >>> 32);
        result[1] = (byte) (sequence >>> 24);
        result[2] = (byte) (sequence >>> 16);
        result[3] = (byte) (sequence >>> 8);
        result[4] = (byte) sequence;
        System.arraycopy(payload, 0, result, 5, payload.length);
        return result;
    }

    private static byte[] metadata(long size, long crc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{2, 0, 1, 0, 1});
        out.writeBytes(NAME.getBytes(StandardCharsets.US_ASCII));
        for (long value : new long[]{size, crc}) {
            out.write((int) (value >>> 24));
            out.write((int) (value >>> 16));
            out.write((int) (value >>> 8));
            out.write((int) value);
        }
        return out.toByteArray();
    }

    private static byte[] list() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{1, 1, 1});
        out.writeBytes(NAME.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test public void largerThan64MiBStreamsToDiskAndSavesOnlyAfterCrc() throws Exception {
        byte[] audio = new byte[480];
        for (int i = 0; i < audio.length; i += 80) audio[i] = (byte) 0xbc;
        int blocks = 64 * 1024 * 1024 / 480 + 2;
        long total = (long) blocks * audio.length;
        CRC32 checksum = new CRC32();
        AtomicInteger saves = new AtomicInteger();
        File ogg = new File(folder.getRoot(), "saved.ogg");
        MemoketTransfer transfer = new MemoketTransfer((name, staged, expectedCrc) -> {
            assertEquals(NAME, name);
            assertEquals(total, staged.length());
            assertEquals(checksum.getValue(), expectedCrc);
            MemoketOgg.encode(staged, ogg, 37);
            saves.incrementAndGet();
        }, folder.getRoot(), null, 1, false, false, true);
        try {
            assertNull(transfer.onControl(list()));
            byte[] data = frame(0, audio);
            for (int i = 0; i < blocks; i++) {
                data[3] = (byte) (i >>> 8);
                data[4] = (byte) i;
                transfer.onData(data);
                checksum.update(audio);
            }
            assertEquals(total, transfer.bufferedBytes());
            assertTrue(total > 64L * 1024 * 1024);
            assertEquals(0, saves.get());
            assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(total, checksum.getValue())));
            assertEquals(0, saves.get());
            byte[] ack = transfer.onControl(new byte[]{3, (byte) 0xff});
            assertEquals(5, ack[0]);
            assertEquals(1, saves.get());
            try (FileInputStream in = new FileInputStream(ogg)) {
                byte[] signature = new byte[4];
                assertEquals(4, in.read(signature));
                assertArrayEquals(new byte[]{'O', 'g', 'g', 'S'}, signature);
            }
            assertTrue(ogg.length() > total);
        } finally {
            transfer.close();
        }
        assertEquals(0, folder.getRoot().listFiles((d, n) -> n.endsWith(".part")).length);
    }

    @Test public void mismatchedCrcNeverPersistsOrAcknowledges() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, staged, crc) ->
                saves.incrementAndGet(), folder.getRoot(), null, 1, false, false, true);
        try {
            assertNull(transfer.onControl(list()));
            transfer.onData(frame(0, new byte[]{1, 2, 3}));
            try {
                transfer.onControl(metadata(3, 12345));
                fail("CRC mismatch must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("incomplete"));
            }
            assertEquals(0, saves.get());
        } finally {
            transfer.close();
        }
        assertEquals(0, folder.getRoot().listFiles((d, n) -> n.endsWith(".part")).length);
    }
}
