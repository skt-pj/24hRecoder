package com.sktpj.recorder24h.memoket;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class MemoketBatchAssessmentTest {
    @Test public void completedBleProcedureWithoutAudioIsNotSuccess() {
        assertEquals("NO_FILE_RECEIVED", MemoketBatchAssessment.fileState(0, "", -1));
    }
    @Test public void partialStreamCannotBeReportedAsSavedAudio() {
        assertEquals("PARTIAL_STREAM_ALREADY_ACTIVE", MemoketBatchAssessment.fileState(0, "", 5));
        assertEquals("PARTIAL_STREAM_ALREADY_ACTIVE", MemoketBatchAssessment.fileState(0, "", 35));
    }
    @Test public void actualSavedAudioHasPriorityOnlyWhenPersisted() {
        assertEquals("AUDIO_SAVED", MemoketBatchAssessment.fileState(1, "", 0));
        assertEquals("FAILED", MemoketBatchAssessment.fileState(0, "CRC mismatch", 5));
    }
}
