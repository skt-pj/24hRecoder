package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import static org.junit.Assert.*;

public class GemResponsePhasePolicyTest {
    @Test public void observed020000IsNotMetadataSuccessAndRetriesAreBounded() {
        byte[] observed = new byte[]{2, 0, 0};
        assertTrue(GemResponsePhasePolicy.is020000(observed));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 1}));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 2}));
        assertFalse(GemResponsePhasePolicy.is020000(new byte[]{2, 0, 0, 0}));
        assertFalse(GemResponsePhasePolicy.exceeded020000Limit(1));
        assertFalse(GemResponsePhasePolicy.exceeded020000Limit(2));
        assertTrue(GemResponsePhasePolicy.exceeded020000Limit(3));
    }
}
