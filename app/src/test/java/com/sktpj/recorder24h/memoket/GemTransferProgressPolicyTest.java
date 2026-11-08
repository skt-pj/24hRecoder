package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import static org.junit.Assert.*;

public class GemTransferProgressPolicyTest {
    @Test public void recordingForHoursWithoutBleDataIsNotTransferFailure() {
        assertFalse(GemTransferProgressPolicy.stalled(
                true, false, false, false, 7_200_000L, 1L));
    }

    @Test public void stopRequestedWithNoMetadataOrFileDataCannotBeCalledStalled() {
        assertFalse(GemTransferProgressPolicy.stalled(
                true, true, false, false, 120_000L, 1L));
    }

    @Test public void validStopMetadataWithoutPostStopDataIsStillWaitingForFile() {
        assertFalse(GemTransferProgressPolicy.stalled(
                true, true, true, false, 120_000L, 1L));
    }

    @Test public void actualFileTransferStallIsDistinctFromRecordingSilence() {
        assertFalse(GemTransferProgressPolicy.stalled(
                true, true, true, true, 70_000L, 10_000L));
        assertTrue(GemTransferProgressPolicy.stalled(
                true, true, true, true, 70_001L, 10_000L));
        assertFalse(GemTransferProgressPolicy.stalled(
                false, false, false, false, 70_000L, 10_000L));
        assertTrue(GemTransferProgressPolicy.stalled(
                false, false, false, false, 70_001L, 10_000L));
    }

    @Test public void silenceStopRetriesMetadataWithoutRequiringRealTimeData() {
        assertFalse(GemTransferProgressPolicy.retryMetadata(
                true, true, false, false, true, true, 6_000L, 500L));
        assertFalse(GemTransferProgressPolicy.retryMetadata(
                true, true, false, false, false, true, 5_499L, 500L));
        assertTrue(GemTransferProgressPolicy.retryMetadata(
                true, true, false, false, false, true, 5_500L, 500L));
        assertFalse(GemTransferProgressPolicy.retryMetadata(
                true, true, true, false, false, true, 6_000L, 500L));
        assertTrue(GemTransferProgressPolicy.retryMetadata(
                true, true, true, true, false, true, 6_000L, 500L));
        assertFalse(GemTransferProgressPolicy.retryMetadata(
                false, true, false, true, false, true, 6_000L, 500L));
    }

    @Test public void fetchOnlyAfterStopMetadataAndOnlyIfNoFileWasAnnounced() {
        assertFalse(GemTransferProgressPolicy.requestPostStopFetch(
                true, false, false, true, false));
        assertFalse(GemTransferProgressPolicy.requestPostStopFetch(
                true, true, false, true, false));
        assertFalse(GemTransferProgressPolicy.requestPostStopFetch(
                true, true, true, false, false));
        assertFalse(GemTransferProgressPolicy.requestPostStopFetch(
                true, true, true, true, true));
        assertFalse(GemTransferProgressPolicy.requestPostStopFetch(
                false, true, true, true, false));
        assertTrue(GemTransferProgressPolicy.requestPostStopFetch(
                true, true, true, true, false));
    }
}
