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
        assertNull(transfer.onControl(listResult()));
        assertNull(transfer.onControl(new byte[]{2,0,2}));
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

    @Test
    public void stopTransfersExactlyOneRecordingAndAcknowledgesOnlyAfterSave() throws Exception {
        byte[] data = new byte[480];
        java.util.concurrent.atomic.AtomicInteger saved = new java.util.concurrent.atomic.AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, audio, crc) -> {
            assertEquals(NAME, name);
            assertArrayEquals(data, audio);
            saved.incrementAndGet();
        }, name -> NAME.equals(name), 1);
        assertNull(transfer.onControl(listResult()));
        assertNull(transfer.onData(block(0, data)));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(data)));
        assertEquals(0, saved.get());
        byte[] ack = transfer.onControl(new byte[]{3, (byte) 0xff});
        assertEquals(1, saved.get());
        assertEquals(5, ack[0]);
        assertArrayEquals(NAME.getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(ack, 2, ack.length));
        assertNull(transfer.onControl(new byte[]{5, 1}));
        assertTrue(transfer.isDone());
        assertEquals(1, transfer.completedCount());
        assertNull(transfer.onControl(listResult()));
        assertEquals(1, saved.get());
    }

    @Test
    public void ignoresEarlyFinalizeResponseUntilCompleteDataIsReceived() throws Exception {
        byte[] audio = new byte[960];
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> saved.incrementAndGet());
        assertNull(transfer.onControl(listResult()));
        assertNull(transfer.onData(block(0, Arrays.copyOfRange(audio, 0, 480))));
        assertNull(transfer.onControl(metadata(audio)));
        assertNull(transfer.onControl(new byte[]{3, (byte) 0xff}));
        assertEquals(0, saved.get());
        assertEquals(480, transfer.bufferedBytes());
        assertArrayEquals(new byte[]{3}, transfer.onData(block(1, Arrays.copyOfRange(audio, 480, 960))));
        assertNull(transfer.onControl(new byte[]{3, 1}));
        assertEquals(5, transfer.onControl(new byte[]{3, (byte) 0xff})[0]);
        assertEquals(1, saved.get());
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
        assertNull(transfer.onControl(list.toByteArray()));
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
        assertNull(transfer.onControl(listResult()));
        assertNull(transfer.onControl(new byte[]{2,0,2}));
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
        assertNull(transfer.onControl(metadata(audio)));
        assertArrayEquals(new byte[]{3}, transfer.onData(block(1, Arrays.copyOfRange(audio, 480, 960))));
    }

    @Test
    public void ignoresUnrelatedCodeTwoUntilMetadataIsExpected() throws Exception {
        MemoketTransfer transfer = new MemoketTransfer((name, payload, crc) -> {});
        assertNull(transfer.onControl(new byte[]{2,0}));
        assertNull(transfer.onControl(listResult()));
    }

    private static byte[] finalizeInfo(byte[] file) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{3, 1, 0, 0, 5, (byte) NAME.length()});
        out.writeBytes(NAME.getBytes(StandardCharsets.US_ASCII));
        long size = file.length;
        out.write((int)(size >>> 24));
        out.write((int)(size >>> 16));
        out.write((int)(size >>> 8));
        out.write((int) size);
        return out.toByteArray();
    }

    @Test
    public void officialHciSavesOneFileThenRequests00After0501WithoutReopening01() throws Exception {
        byte[] audio = new byte[480];
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {
            assertEquals(NAME, name);
            assertArrayEquals(audio, bytes);
            saved.incrementAndGet();
        }, incoming -> NAME.equals(incoming), 1, true);
        assertNull(transfer.onControl(listResult()));
        assertNull(transfer.onData(block(0, audio)));
        assertArrayEquals(new byte[]{3}, transfer.onControl(metadata(audio)));
        assertNull(transfer.onControl(finalizeInfo(audio)));
        assertEquals(0, saved.get());
        byte[] delete = transfer.onControl(new byte[]{3, (byte) 0xff});
        assertEquals(5, delete[0]);
        assertEquals(1, saved.get());
        assertEquals(0, transfer.deleteAcks());
        assertArrayEquals(new byte[]{0}, transfer.onControl(new byte[]{5, 1}));
        assertEquals(1, transfer.deleteAcks());
        assertTrue(transfer.waitingForIdleStatus());
        assertFalse(transfer.isDone());
        assertNull(transfer.onControl(new byte[]{0, 1, 0, 2}));
        assertFalse(transfer.isDone());
        assertNull(transfer.onControl(new byte[]{0, 0}));
        assertEquals(1, transfer.verifiedRemovals());
        assertTrue(transfer.postDeleteIdleConfirmed());
        assertTrue(transfer.isDone());
    }

    @Test(expected = IllegalStateException.class)
    public void rejects03ffWithoutOfficial03InformationForDeleteMode() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                incoming -> NAME.equals(incoming), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        transfer.onControl(metadata(audio));
        transfer.onControl(new byte[]{3, (byte)0xff});
    }

    @Test(expected = IllegalStateException.class)
    public void rejects03InformationWithWrongFileSize() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                incoming -> NAME.equals(incoming), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        transfer.onControl(metadata(audio));
        byte[] bad = finalizeInfo(audio);
        bad[bad.length - 1]++;
        transfer.onControl(bad);
    }

    @Test(expected = MemoketTransfer.OlderRecordingBlockedException.class)
    public void protectsOlderFileWhenFirstListResponseIsNotThisRecording() throws Exception {
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {
            fail("Unselected file must not be persisted");
        }, filename -> false, 1, true);
        transfer.onControl(listResult());
    }

    @Test
    public void distinguishesIdle0000FromAmbiguous0001DuringHandshake() {
        MemoketSessionProtocol idle = new MemoketSessionProtocol();
        idle.allowActiveRecordingStatus(true);
        idle.firstCommand();
        idle.onResponse(new byte[]{0, 0});
        assertTrue(idle.initialIdleReported());
        assertFalse(idle.busyOrTransferringReported());

        MemoketSessionProtocol transferring = new MemoketSessionProtocol();
        transferring.allowActiveRecordingStatus(true);
        transferring.firstCommand();
        transferring.onResponse(new byte[]{0, 1, 0, 2});
        assertFalse(transferring.initialIdleReported());
        assertTrue(transferring.busyOrTransferringReported());
    }

    @Test
    public void acceptsActiveGemStatusOnlyWhenStopRequested() {
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        session.allowActiveRecordingStatus(true);
        session.firstCommand();
        assertArrayEquals(new byte[]{0x27,0x01},
                session.onResponse(new byte[]{0,1,0,'2'}));
    }

    @Test(expected = IllegalStateException.class)
    public void ut16RejectsSpoofedFinalizeHeaderEvenWithMatchingNameAndSize() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                name -> NAME.equals(name), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        transfer.onControl(metadata(audio));
        byte[] frame = finalizeInfo(audio);
        frame[2] = 1; // Official 03 01 00 00 05 length header is mandatory.
        transfer.onControl(frame);
    }

    @Test(expected = IllegalStateException.class)
    public void ut14RejectsSpoofedMetadataHeaderWithCorrectNameAndCrc() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                name -> NAME.equals(name), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        byte[] frame = metadata(audio);
        frame[2] = 0; // 02 00 01 00 05 + name + size + crc in captured HCI.
        transfer.onControl(frame);
    }

    @Test(expected = IllegalStateException.class)
    public void ut14RejectsTrailingMetadataBeyondExpectedFileAndCrc() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                name -> NAME.equals(name), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        byte[] frame = Arrays.copyOf(metadata(audio), metadata(audio).length + 1);
        transfer.onControl(frame);
    }

    @Test
    public void ut23PostDeleteIdleDoesNotProveDeviceFlashFileRemoval() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                name -> NAME.equals(name), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        transfer.onControl(metadata(audio));
        transfer.onControl(finalizeInfo(audio));
        transfer.onControl(new byte[]{3, (byte)0xff});
        assertArrayEquals(new byte[]{0}, transfer.onControl(new byte[]{5, 1}));
        transfer.onControl(new byte[]{0, 0});
        assertTrue(transfer.postDeleteIdleConfirmed());
        assertEquals(1, transfer.deleteAcks());
        assertEquals("00 00 is idle, not flash deletion proof", 0, transfer.verifiedRemovals());
        assertTrue(transfer.isDone());
    }

    @Test
    public void ut20IgnoresMalformedDeleteAckUntilExact0501() throws Exception {
        byte[] audio = new byte[480];
        MemoketTransfer transfer = new MemoketTransfer((name, bytes, crc) -> {},
                name -> NAME.equals(name), 1, true);
        transfer.onControl(listResult());
        transfer.onData(block(0, audio));
        transfer.onControl(metadata(audio));
        transfer.onControl(finalizeInfo(audio));
        assertEquals(5, transfer.onControl(new byte[]{3, (byte)0xff})[0]);
        assertNull(transfer.onControl(new byte[]{5, 1, 9}));
        assertEquals(0, transfer.deleteAcks());
        assertFalse(transfer.isDone());
    }

}
