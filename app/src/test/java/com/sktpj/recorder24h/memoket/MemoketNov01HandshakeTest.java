package com.sktpj.recorder24h.memoket;

import org.junit.Test;

import static org.junit.Assert.*;

public final class MemoketNov01HandshakeTest {
    private static final byte[] OBSERVED_E8 = bytes(
            "e80d01313938342f33363934333336");

    @Test public void observedGemIdentityCompletesHandshake() {
        assertEquals(15, OBSERVED_E8.length);
        MemoketSessionProtocol session = new MemoketSessionProtocol();
        assertArrayEquals(bytes("00"), session.firstCommand());
        assertArrayEquals(bytes("2701"), session.onResponse(bytes("0000")));
        assertArrayEquals(bytes("e1"), session.onResponse(bytes("2702")));
        assertArrayEquals(bytes("f300"), session.onResponse(bytes("e11b02")));
        assertArrayEquals(bytes("e301"), session.onResponse(bytes("f30000")));
        assertArrayEquals(bytes("ff68"), session.onResponse(bytes("e330312e35302e30312e3130")));
        assertArrayEquals(bytes("e500000002"), session.onResponse(bytes("ff6800000001")));
        assertArrayEquals(bytes("e8"), session.onResponse(bytes("e501")));
        assertNull(session.onResponse(OBSERVED_E8));
        assertTrue(session.isReady());
        assertEquals("READY", session.debugStep());
    }

    @Test public void knownE8ResponsesRemainSupported() {
        assertTrue(MemoketSessionProtocol.isSupportedE8Response(bytes("e80c00")));
        assertTrue(MemoketSessionProtocol.isSupportedE8Response(bytes("e80c02")));
        assertTrue(MemoketSessionProtocol.isSupportedE8Response(bytes("e80e02aa")));
        assertTrue(MemoketSessionProtocol.isSupportedE8Response(OBSERVED_E8));
    }

    @Test public void malformedOrUnknownE8CannotAuthenticate() {
        assertFalse(MemoketSessionProtocol.isSupportedE8Response(bytes("e80d01")));
        assertFalse(MemoketSessionProtocol.isSupportedE8Response(bytes("e80d01313938342f333639343333")));
        assertFalse(MemoketSessionProtocol.isSupportedE8Response(bytes("e80d02313938342f33363934333336")));
        assertFalse(MemoketSessionProtocol.isSupportedE8Response(bytes("e80d01313938342f333639343333ff")));
        assertFalse(MemoketSessionProtocol.isSupportedE8Response(bytes("e80f01")));
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
