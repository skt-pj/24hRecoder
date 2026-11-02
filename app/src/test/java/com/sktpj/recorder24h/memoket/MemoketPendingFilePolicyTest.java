package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import static org.junit.Assert.*;

public final class MemoketPendingFilePolicyTest {
    private static long ms(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 2, hour, minute)
                .atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    @Test public void appOwnedStoppedRecordingCanBeRecovered() {
        assertTrue(MemoketPendingFilePolicy.mayAcknowledge(
                "20261102_134047_2.opus", ms(13, 40), ms(13, 45), "Asia/Tokyo"));
    }

    @Test public void unknownOrUnstoppedAudioIsNotAcknowledged() {
        assertFalse(MemoketPendingFilePolicy.mayAcknowledge(
                "20261102_134047_2.opus", ms(14, 59), ms(15, 1), "Asia/Tokyo"));
        assertFalse(MemoketPendingFilePolicy.mayAcknowledge(
                "20261102_134047_2.opus", ms(13, 40), 0, "Asia/Tokyo"));
        assertFalse(MemoketPendingFilePolicy.mayAcknowledge(
                "20261102_134047_2.opus", 0, ms(13, 45), "Asia/Tokyo"));
    }
}
