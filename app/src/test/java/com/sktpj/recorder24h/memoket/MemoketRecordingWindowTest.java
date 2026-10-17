package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class MemoketRecordingWindowTest {
    private static long at(int hour, int minute, int second) {
        return LocalDateTime.of(2026, 10, 10, hour, minute, second)
                .atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    private static byte[] announced(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{1, 1, 1});
        out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    public void acceptsCurrentRecordingIncludingNumberedSegments() {
        MemoketRecordingWindow window = new MemoketRecordingWindow(
                at(17, 20, 30), at(17, 25, 0), "Asia/Tokyo");
        assertTrue(window.matches("20261010_172032_2.opus"));
        assertTrue(window.matches("20261010_172450.opus"));
        assertFalse(window.matches("20261010_162846_2.opus"));
        assertFalse(window.matches("20261010_180000_2.opus"));
        assertFalse(window.matches("incorrect-name.opus"));
        assertFalse(window.matches("20261099_172032.opus"));
    }

    @Test
    public void refusesEarlierFileBeforeReceivingOrAcknowledgingIt() throws Exception {
        MemoketRecordingWindow window = new MemoketRecordingWindow(
                at(17, 20, 30), at(17, 25, 0), "Asia/Tokyo");
        AtomicInteger saved = new AtomicInteger();
        MemoketTransfer transfer = new MemoketTransfer(
                (name, bytes, crc) -> saved.incrementAndGet(), window::matches);
        try {
            transfer.onControl(announced("20261010_162846_2.opus"));
            fail("Must not download or ACK a previous recording");
        } catch (MemoketTransfer.OlderRecordingBlockedException expected) {
            assertTrue(expected.getMessage().contains("20261010_162846_2.opus"));
        }
        assertEquals(0, saved.get());
        assertEquals(0, transfer.bufferedBytes());
        assertEquals(0, transfer.completedCount());
    }

    @Test
    public void selectsCurrentRecordingInsteadOfPreviousSession() throws Exception {
        MemoketRecordingWindow window = new MemoketRecordingWindow(
                at(17, 20, 30), at(17, 25, 0), "Asia/Tokyo");
        MemoketTransfer transfer = new MemoketTransfer(
                (name, bytes, crc) -> {}, window::matches);
        assertNull(transfer.onControl(announced("20261010_172032_2.opus")));
        assertEquals("RECEIVING_DATA", transfer.debugState());
        assertEquals("20261010_172032_2.opus", transfer.debugFileName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotFallbackToUnrestrictedSyncWhenStartMissing() {
        new MemoketRecordingWindow(0L, at(17, 25, 0), "Asia/Tokyo");
    }
}
