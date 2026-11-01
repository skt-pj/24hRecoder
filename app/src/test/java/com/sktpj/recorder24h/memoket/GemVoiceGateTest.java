package com.sktpj.recorder24h.memoket;

import org.junit.Test;
import static org.junit.Assert.*;

public class GemVoiceGateTest {
    @Test public void silenceDoesNotStartRecording() {
        GemVoiceGate gate = new GemVoiceGate();
        assertFalse(gate.shouldStart(30_000L));
    }

    @Test public void speechTriggersStartAndSilenceTriggersStopAndFetch() {
        GemVoiceGate gate = new GemVoiceGate();
        gate.heardSpeech(10_000L);
        assertTrue(gate.shouldStart(10_000L));
        gate.startSent();
        assertFalse(gate.shouldStart(10_100L));
        gate.started();
        assertFalse(gate.shouldStop(11_999L));
        assertTrue(gate.shouldStop(12_000L));
        gate.stopSent();
        assertFalse(gate.shouldStart(12_100L));
        gate.acquired();
        assertFalse(gate.shouldStart(13_000L));
        gate.heardSpeech(15_000L);
        assertTrue(gate.shouldStart(15_000L));
    }

    @Test public void stoppingWhileConnectingFinishesOnlyAfterRetrieval() {
        GemVoiceGate gate = new GemVoiceGate();
        gate.heardSpeech(100L);
        gate.startSent();
        gate.stopRequested();
        assertFalse(gate.canExit());
        gate.started();
        assertTrue(gate.shouldStop(101L));
        gate.stopSent();
        assertFalse(gate.canExit());
        gate.acquired();
        assertTrue(gate.canExit());
    }
}
