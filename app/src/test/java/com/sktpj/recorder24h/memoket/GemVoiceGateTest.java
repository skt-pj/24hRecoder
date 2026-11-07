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
        assertFalse(gate.shouldStop(69_999L));
        assertTrue(gate.shouldStop(70_000L));
        gate.stopSent();
        assertFalse(gate.shouldStart(70_100L));
        gate.acquired();
        assertFalse(gate.shouldStart(70_100L));
        gate.heardSpeech(71_000L);
        assertTrue(gate.shouldStart(71_000L));
    }

    @Test public void speechWindowForStartingStaysTwoSeconds() {
        GemVoiceGate gate = new GemVoiceGate();
        gate.heardSpeech(10_000L);
        assertTrue(gate.shouldStart(11_999L));
        assertFalse(gate.shouldStart(12_000L));
    }

    @Test public void newSpeechResetsOneMinuteSilenceCountdown() {
        GemVoiceGate gate = new GemVoiceGate();
        gate.heardSpeech(10_000L);
        gate.startSent();
        gate.started();
        assertFalse(gate.shouldStop(69_999L));
        gate.heardSpeech(60_000L);
        assertFalse(gate.shouldStop(119_999L));
        assertTrue(gate.shouldStop(120_000L));
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
