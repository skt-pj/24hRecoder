package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoketOfficialStopSequenceTest {
    @Test public void stopNeverSendsExtra03AndCannotClaimPhysicalStop() {
        MemoketOfficialStopSequence sequence = new MemoketOfficialStopSequence();
        sequence.begin();
        assertFalse(sequence.readyForFileTransfer());
        assertFalse(sequence.hardwareStopConfirmed());
        assertEquals(Boolean.TRUE, sequence.onCccdWritten(new byte[]{0, 0}));
        assertFalse(sequence.readyForFileTransfer());
        assertNull(sequence.onCccdWritten(new byte[]{1, 0}));
        assertTrue(sequence.readyForFileTransfer());
        assertFalse(sequence.hardwareStopConfirmed());
        // File-transfer opcode is 010000, not STOP 03.
        assertArrayEquals(new byte[]{1, 0, 0}, MemoketTransfer.initialCommand());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateStop() {
        MemoketOfficialStopSequence sequence = new MemoketOfficialStopSequence();
        sequence.begin();
        sequence.begin();
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsWrongFirstDescriptorValue() {
        MemoketOfficialStopSequence sequence = new MemoketOfficialStopSequence();
        sequence.begin();
        sequence.onCccdWritten(new byte[]{1, 0});
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateOffResponse() {
        MemoketOfficialStopSequence sequence = new MemoketOfficialStopSequence();
        sequence.begin();
        sequence.onCccdWritten(new byte[]{0, 0});
        sequence.onCccdWritten(new byte[]{0, 0});
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsMalformedDescriptor() {
        MemoketOfficialStopSequence sequence = new MemoketOfficialStopSequence();
        sequence.begin();
        sequence.onCccdWritten(new byte[]{0, 0, 0});
    }
}
