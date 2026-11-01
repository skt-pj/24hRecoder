package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/** Hex-level regressions from the official 2026-10-31 23:45 capture. */
public final class MemoketOct31OfficialHciTest {
    @Test public void newHandshakeSkipsF3AndAcceptsE80E02() {
        MemoketSessionProtocol protocol = new MemoketSessionProtocol();
        assertArrayEquals(new byte[]{0}, protocol.firstCommand());
        assertArrayEquals(new byte[]{0x27,1}, protocol.onResponse(new byte[]{0,0}));
        assertArrayEquals(new byte[]{(byte)0xe1}, protocol.onResponse(new byte[]{0x27,2}));
        assertArrayEquals(new byte[]{(byte)0xe3,1}, protocol.onResponse(new byte[]{(byte)0xe1,0x29,2}));
        assertArrayEquals(new byte[]{(byte)0xff,0x68}, protocol.onResponse(new byte[]{(byte)0xe3,0x30,0x31}));
        assertArrayEquals(new byte[]{(byte)0xe5,0,0,0,2},
                protocol.onResponse(new byte[]{(byte)0xff,0x68,0,0,0,1}));
        assertArrayEquals(new byte[]{(byte)0xe8}, protocol.onResponse(new byte[]{(byte)0xe5,1}));
        assertNull(protocol.onResponse(new byte[]{(byte)0xe8,0x0e,2,0x33}));
        assertTrue(protocol.isReady());
    }

    @Test public void oct31TransferCompletesAfter02Without03OnlyWhenCrcMatches() throws Exception {
        String file = "20261031_234534_2.opus";
        byte[] filename = file.getBytes(StandardCharsets.US_ASCII);
        byte[] audio = "opus-test-data".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(audio);
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((name, payload, actualCrc) -> {
            assertEquals(file, name);
            assertArrayEquals(audio, payload);
            assertEquals(crc.getValue(), actualCrc);
            saved.incrementAndGet();
        }, file::equals, 1, true, true);
        byte[] list = new byte[3 + filename.length];
        list[0]=1; list[1]=1; list[2]=1;
        System.arraycopy(filename,0,list,3,filename.length);
        assertNull(transfer.onControl(list));
        byte[] packet = new byte[5+audio.length];
        System.arraycopy(audio,0,packet,5,audio.length);
        assertNull(transfer.onData(packet));

        byte[] meta = new byte[5+filename.length+8];
        meta[0]=2; meta[2]=1; meta[4]=6;
        System.arraycopy(filename,0,meta,5,filename.length);
        write32(meta, 5+filename.length, audio.length);
        write32(meta, 9+filename.length, crc.getValue());
        byte[] ack = transfer.onControl(meta);
        assertEquals(5, ack[0]);
        assertEquals(filename.length, ack[1] & 0xff);
        assertEquals(file, new String(ack, 2, filename.length, StandardCharsets.US_ASCII));
        assertEquals(1, saved.get());
        assertArrayEquals(new byte[]{0}, transfer.onControl(new byte[]{5,1}));
        assertNull(transfer.onControl(new byte[]{0,0}));
        assertTrue(transfer.isDone());
    }

    @Test public void metadataOnlyRejectsWrongCrcWithout05() throws Exception {
        byte[] name = "20261031_234547_2.opus".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer((filename, bytes, crc) -> saved.incrementAndGet(),
                filename -> true, 1, true, true);
        byte[] list = new byte[3+name.length];
        list[0]=1; list[1]=1; list[2]=1;
        System.arraycopy(name,0,list,3,name.length);
        transfer.onControl(list);
        transfer.onData(new byte[]{0,0,0,0,0,0x41});
        byte[] meta = new byte[5+name.length+8];
        meta[0]=2; meta[2]=1; meta[4]=9;
        System.arraycopy(name,0,meta,5,name.length);
        write32(meta,5+name.length,1);
        write32(meta,9+name.length,0);
        try { transfer.onControl(meta); fail("CRC mismatch must block ACK"); }
        catch (IllegalStateException expected) { assertEquals(0,saved.get()); }
    }

    private static void write32(byte[] out,int offset,long value) {
        out[offset]=(byte)(value>>>24);out[offset+1]=(byte)(value>>>16);
        out[offset+2]=(byte)(value>>>8);out[offset+3]=(byte)value;
    }
}
