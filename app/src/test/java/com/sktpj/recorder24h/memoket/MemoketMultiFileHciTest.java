package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.junit.Test;

/** Two genuine HCI structural fixtures: old one-file and new three-file response. */
public final class MemoketMultiFileHciTest {
    private static final String OLD = "20261010_224936_2.opus";
    private static final String LATEST = "20261013_185440_2.opus";
    private static final String FIRST = "20261013_185358_2.opus";
    private static final String SECOND = "20261013_185416_2.opus";

    private static void be32(ByteArrayOutputStream out, long n) {
        out.write((int) (n >>> 24)); out.write((int) (n >>> 16));
        out.write((int) (n >>> 8)); out.write((int) n);
    }
    private static byte[] block(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 0, 0, 0, 0});
        out.writeBytes(data);
        return out.toByteArray();
    }
    private static byte[] metadata(String name, int id, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{2, 0, 1, (byte)(id >>> 8), (byte) id});
        out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        be32(out, data.length);
        CRC32 crc = new CRC32(); crc.update(data);
        be32(out, crc.getValue());
        return out.toByteArray();
    }
    private static void entry(ByteArrayOutputStream out, int seq, int id, String name, int size) {
        byte[] text = name.getBytes(StandardCharsets.US_ASCII);
        out.write(seq); out.write(id >>> 8); out.write(id);
        out.write(text.length); out.writeBytes(text); be32(out, size);
    }
    private static byte[] infoOld(String name, int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{3, 1});
        entry(out, 0, 5, name, size);
        return out.toByteArray();
    }
    private static byte[] infoThree(String chosenName, int chosenId, int chosenSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{3, 3});
        entry(out, 0, 11, FIRST, 44160);
        entry(out, 1, 6, SECOND, 25920);
        entry(out, 2, chosenId, chosenName, chosenSize);
        return out.toByteArray();
    }
    private static MemoketTransfer started(String name, int id, byte[] data,
            AtomicInteger writes) throws Exception {
        MemoketTransfer transfer = new MemoketTransfer((n, b, crc) -> writes.incrementAndGet(),
                name::equals, 1, true);
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        list.writeBytes(new byte[]{1,1,1});
        list.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        assertNull(transfer.onControl(list.toByteArray()));
        assertNull(transfer.onData(block(data)));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(name, id, data)));
        return transfer;
    }

    @Test public void hciOldOneFileId5StillAccepted() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(OLD, 5, audio, saved);
        assertNull(t.onControl(infoOld(OLD, 480)));
        assertEquals(0, saved.get());
        assertEquals(5, t.onControl(new byte[]{3,(byte)0xff})[0]);
        assertEquals(1, saved.get());
    }

    @Test public void hciNewThreeFilesId9LatestAccepted() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(LATEST, 9, audio, saved);
        assertNull(t.onControl(infoThree(LATEST, 9, 480)));
        assertEquals(0, saved.get());
        byte[] delete = t.onControl(new byte[]{3,(byte)0xff});
        assertEquals(5, delete[0]);
        assertEquals(1, saved.get());
        assertArrayEquals(new byte[]{0}, t.onControl(new byte[]{5,1}));
        assertFalse(t.isDone());
        assertNull(t.onControl(new byte[]{0,0}));
        assertTrue(t.isDone());
        assertEquals(0,t.verifiedRemovals());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsSelectedIdMismatchBetween02And03() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(LATEST, 9, audio, saved);
        t.onControl(infoThree(LATEST, 7, 480));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsOlderSelectedWhenNewerIsIn03List() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(SECOND, 6, audio, saved);
        t.onControl(infoThree(LATEST, 9, 36000));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsAmbiguousDuplicateLatestTimestamp() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(LATEST, 9, audio, saved);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{3,2});
        entry(out, 0, 9, LATEST, 480);
        entry(out, 1, 10, "20261013_185440_1.opus", 480);
        t.onControl(out.toByteArray());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsTruncated03List() throws Exception {
        byte[] audio = new byte[480]; AtomicInteger saved = new AtomicInteger();
        MemoketTransfer t = started(LATEST, 9, audio, saved);
        byte[] f = infoThree(LATEST, 9, 480);
        t.onControl(java.util.Arrays.copyOf(f, f.length - 1));
    }

    private static void handshakeE8(int variant) {
        MemoketSessionProtocol t = new MemoketSessionProtocol();
        t.firstCommand();
        t.onResponse(new byte[]{0,0});
        t.onResponse(new byte[]{0x27,2});
        t.onResponse(new byte[]{(byte)0xe1,0x3a,2});
        t.onResponse(new byte[]{(byte)0xf3,0,0});
        t.onResponse(new byte[]{(byte)0xe3,'0','1'});
        t.onResponse(new byte[]{(byte)0xff,0x68,1,2,3,4});
        t.onResponse(new byte[]{(byte)0xe5,1});
        t.onResponse(new byte[]{(byte)0xe8,0x0c,(byte)variant,'9','6','0'});
        assertTrue(t.isReady());
    }
    @Test public void bothObservedE8VariantsAccepted() {
        handshakeE8(0);
        handshakeE8(2);
    }
    @Test public void bothObservedE1TransferResponsesAccepted() {
        MemoketTransferStatus.verifyIntermediateE1(new byte[]{(byte)0xe1,0x3a,2});
        MemoketTransferStatus.verifyIntermediateE1(new byte[]{(byte)0xe1,0x62,2});
    }
}
