package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

public class MemoketTransferTest {
    private static final String NAME = "20261009_213722_2.opus";

    private static byte[] metadata(byte[] file) {
        CRC32 crc = new CRC32();
        crc.update(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{2, 0, 1, 0, 6});
        out.writeBytes(NAME.getBytes(StandardCharsets.US_ASCII));
        long size = file.length;
        long check = crc.getValue();
        for (long number : new long[]{size, check}) {
            out.write((int) (number >>> 24));
            out.write((int) (number >>> 16));
            out.write((int) (number >>> 8));
            out.write((int) number);
        }
        return out.toByteArray();
    }

    private static byte[] listResult() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{1,1,1});
        out.writeBytes(NAME.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] block(int seq, byte[] bytes) {
        long value = seq & 0xffffffffL;
        byte[] packet = new byte[5 + bytes.length];
        packet[0] = (byte) (value >>> 32);
        packet[1] = (byte) (value >>> 24);
        packet[2] = (byte) (value >>> 16);
        packet[3] = (byte) (value >>> 8);
        packet[4] = (byte) value;
        System.arraycopy(bytes, 0, packet, 5, bytes.length);
        return packet;
    }

    @Test
    public void readsFileChecksCrcAndSendsAcknowledgmentAfterDurableSave() throws Exception {
        byte[] frame = new byte[80];
        frame[0] = (byte) 0xbc;
        byte[] audio = new byte[480];
        for (int i = 0; i < 6; i++) System.arraycopy(frame, 0, audio, i * 80, 80);
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {
            assertEquals(NAME, name);
            assertArrayEquals(audio, payload);
            CRC32 check = new CRC32();
            check.update(payload);
            assertEquals(check.getValue(), crc);
            saved.incrementAndGet();
        });
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(listResult()));
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(new byte[]{2,0,2}));
        transfer.onData(block(0,audio));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(audio)));
        assertEquals(0,saved.get());
        assertNull(transfer.onControl(new byte[]{3,1,0,0}));
        byte[] ack = transfer.onControl(new byte[]{3, (byte) 0xff});
        assertEquals(1,saved.get());
        assertEquals(5,ack[0]);
        assertEquals(NAME.length(), ack[1]);
        assertArrayEquals(new byte[]{1,0,0}, transfer.onControl(new byte[]{5,1}));
        transfer.onControl(new byte[]{1,0,0});
        assertTrue(transfer.isDone());
    }

    @Test(expected = IllegalStateException.class)
    public void refusesAcknowledgmentForIncompleteTransfer() throws Exception {
        byte[] audio = new byte[960];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> fail("no save"));
        transfer.onControl(listResult());
        transfer.onData(block(0,new byte[480]));
        transfer.onControl(metadata(audio));
        transfer.onControl(new byte[]{3,(byte)0xff});
    }

    @Test(expected = IllegalStateException.class)
    public void refusesOutOfOrderChunks() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name,bytes,crc) -> {});
        transfer.onControl(listResult());
        transfer.onControl(metadata(audio));
        transfer.onData(block(1,audio));
    }

    @Test
    public void producesOggOpusWithoutReencoding() {
        byte[] audio = new byte[6 * 80];
        for(int i=0; i<audio.length; i+=80) audio[i]=(byte)0xbc;
        byte[] ogg = MemoketOgg.encode(audio, 100);
        assertEquals("OggS", new String(Arrays.copyOf(ogg,4), StandardCharsets.US_ASCII));
        assertTrue(new String(ogg, StandardCharsets.ISO_8859_1).contains("OpusHead"));
        assertTrue(new String(ogg, StandardCharsets.ISO_8859_1).contains("OpusTags"));
    }
    @Test
    public void replaysObservedSessionHandshake() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        assertArrayEquals(new byte[]{0x00}, session.firstCommand());
        assertArrayEquals(new byte[]{0x27,0x01}, session.onResponse(new byte[]{0x00,0x00}));
        assertArrayEquals(new byte[]{(byte)0xe1}, session.onResponse(new byte[]{0x27,0x02}));
        assertArrayEquals(new byte[]{(byte)0xf3,0x00}, session.onResponse(new byte[]{(byte)0xe1,0x1f,0x02}));
        assertArrayEquals(new byte[]{(byte)0xe3,0x01}, session.onResponse(new byte[]{(byte)0xf3,0x00,0x00}));
        assertArrayEquals(new byte[]{(byte)0xff,0x68}, session.onResponse(new byte[]{(byte)0xe3,'0','1','.','4','8'}));
        assertArrayEquals(new byte[]{(byte)0xe5,0x6a,(byte)0xc9,0x5e,0x6e},
                session.onResponse(new byte[]{(byte)0xff,0x68,0x6a,(byte)0xc9,0x5e,0x6d}));
        assertArrayEquals(new byte[]{(byte)0xe8}, session.onResponse(new byte[]{(byte)0xe5,0x01}));
        assertNull(session.onResponse(new byte[]{(byte)0xe8,0x0c,0x00,'7','0','4'}));
        assertTrue(session.isReady());
    }

    @Test
    public void ignoresUnsolicitedStatusDuringHandshake() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        assertArrayEquals(new byte[]{0x00}, session.firstCommand());
        assertArrayEquals(new byte[]{0x27,0x01}, session.onResponse(new byte[]{0x00,0x00}));
        assertNull(session.onResponse(new byte[]{0x00,0x00}));
        assertNull(session.onResponse(new byte[]{0x00,0x01}));
        assertArrayEquals(new byte[]{(byte)0xe1}, session.onResponse(new byte[]{0x27,0x02}));
    }

    @Test
    public void parsesCapturedMetadataBySelectedFilename() throws Exception {
        String fileName = "20261009_213958_2.opus";
        byte[] audio = new byte[14400];
        Arrays.fill(audio, (byte) 0x11);
        CRC32 crc = new CRC32();
        crc.update(audio);
        MemoketTransfer transfer = new MemoketTransfer((name, payload, expectedCrc) -> {});
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        list.writeBytes(new byte[]{1,1,1});
        list.writeBytes(fileName.getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(list.toByteArray()));
        for (int seq = 0; seq < 30; seq++) {
            transfer.onData(block(seq, Arrays.copyOfRange(audio, seq * 480, (seq + 1) * 480)));
        }

        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        metadata.writeBytes(new byte[]{2,0,1,0,3});
        metadata.writeBytes(fileName.getBytes(StandardCharsets.US_ASCII));
        long size = audio.length;
        long check = crc.getValue();
        for (long number : new long[]{size, check}) {
            metadata.write((int)(number >>> 24));
            metadata.write((int)(number >>> 16));
            metadata.write((int)(number >>> 8));
            metadata.write((int)number);
        }
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata.toByteArray()));
    }

    @Test
    public void pollsObservedShortMetadataStatusUntilDataIsReady() throws Exception {
        byte[] audio = new byte[480];
        for (int i = 0; i < audio.length; i += 80) audio[i] = (byte) 0xbc;
        MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {});
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(listResult()));
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(new byte[]{2,0,2}));
        transfer.onData(block(0, audio));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(audio)));
    }

    @Test
    public void metadataBeforeFinalBlockKeepsPollingUntilComplete() throws Exception {
        byte[] audio = new byte[960];
        for (int i = 0; i < audio.length; i += 80) audio[i] = (byte) 0xbc;
        MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {});
        transfer.onControl(listResult());
        transfer.onData(block(0, Arrays.copyOfRange(audio, 0, 480)));
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(metadata(audio)));
        transfer.onData(block(1, Arrays.copyOfRange(audio, 480, 960)));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(audio)));
    }

    @Test
    public void ignoresUnrelatedCodeTwoUntilMetadataIsExpected() throws Exception {
        MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {});
        assertNull(transfer.onControl(new byte[]{2,0}));
        assertArrayEquals(new byte[]{2,0}, transfer.onControl(listResult()));
    }

}
