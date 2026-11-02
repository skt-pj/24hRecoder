package com.sktpj.recorder24h.transcription;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrainProcessPolicyTest {
    private static final String APP = "com.sktpj.recorder24h.debug";

    @Test public void mainProcessCanOwnWorkManager() {
        assertTrue(DrainProcessPolicy.isMainProcess(APP, APP));
    }

    @Test public void asrAndRecordingProcessesCannotOwnWorkManager() {
        assertFalse(DrainProcessPolicy.isMainProcess(APP + ":postprocess_asr", APP));
        assertFalse(DrainProcessPolicy.isMainProcess(APP + ":streaming_asr", APP));
        assertFalse(DrainProcessPolicy.isMainProcess(APP + ":recorder", APP));
        assertFalse(DrainProcessPolicy.isMainProcess(null, APP));
    }

    @Test public void differentApplicationIdDoesNotCountAsMainProcess() {
        assertFalse(DrainProcessPolicy.isMainProcess(APP, "com.sktpj.recorder24h"));
    }
}
