package com.sktpj.recorder24h.memoket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Strict readback for the 00 -> e1 intermediate status in the official HCI. */
public final class MemoketTransferStatus {
    private MemoketTransferStatus() {}

    public static void verifyIntermediate00(byte[] payload, String selectedName) {
        if (selectedName == null || selectedName.isEmpty()
                || !selectedName.matches("[A-Za-z0-9._-]+\\.opus")) {
            throw new IllegalStateException("Gem transfer has no selected recording");
        }
        byte[] name = selectedName.getBytes(StandardCharsets.US_ASCII);
        if (payload == null || payload.length != name.length + 4
                || payload[0] != 0 || payload[1] != 1
                || payload[2] != 0 || payload[3] != 2
                || !Arrays.equals(Arrays.copyOfRange(payload, 4, payload.length), name)) {
            throw new IllegalStateException("Gem 00 transfer status did not match selected recording");
        }
    }

    public static void verifyIntermediateE1(byte[] payload) {
        // Transfer HCI has e1 3a 02; Oct-13 device has e1 62 02.
        if (payload == null || payload.length != 3 || payload[0] != (byte) 0xe1
                || payload[2] != 0x02) {
            throw new IllegalStateException("Gem intermediate e1 reply has invalid structure");
        }
    }
}
