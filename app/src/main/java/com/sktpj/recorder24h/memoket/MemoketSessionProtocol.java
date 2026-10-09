package com.sktpj.recorder24h.memoket;

import java.util.Arrays;

public final class MemoketSessionProtocol {
    private int step;

    public byte[] firstCommand() {
        step = 1;
        return new byte[]{0x00};
    }

    public byte[] onResponse(byte[] value) {
        if (value == null || value.length == 0) return null;
        int opcode = value[0] & 0xff;

        switch (step) {
            case 0:
                return null;
            case 1:
                if (opcode != 0x00) return null;
                requirePrefix(value, new byte[]{0x00, 0x00}, "00");
                step = 2;
                return new byte[]{0x27, 0x01};
            case 2:
                if (opcode != 0x27) return null;
                requirePrefix(value, new byte[]{0x27, 0x02}, "27");
                step = 3;
                return new byte[]{(byte) 0xe1};
            case 3:
                if (opcode != 0xe1) return null;
                step = 4;
                return new byte[]{(byte) 0xf3, 0x00};
            case 4:
                if (opcode != 0xf3) return null;
                step = 5;
                return new byte[]{(byte) 0xe3, 0x01};
            case 5:
                if (opcode != 0xe3) return null;
                step = 6;
                return new byte[]{(byte) 0xff, 0x68};
            case 6:
                if (opcode != 0xff) return null;
                if (value.length < 6 || (value[1] & 0xff) != 0x68) {
                    throw new IllegalStateException("Unexpected ff68 challenge response");
                }
                byte[] token = Arrays.copyOfRange(value, 2, 6);
                incrementBigEndian(token);
                byte[] reply = new byte[5];
                reply[0] = (byte) 0xe5;
                System.arraycopy(token, 0, reply, 1, token.length);
                step = 7;
                return reply;
            case 7:
                if (opcode != 0xe5) return null;
                requirePrefix(value, new byte[]{(byte) 0xe5, 0x01}, "e5");
                step = 8;
                return new byte[]{(byte) 0xe8};
            case 8:
                if (opcode != 0xe8) return null;
                step = 9;
                return null;
            default:
                return null;
        }
    }

    public boolean isReady() {
        return step >= 9;
    }

    private static void requirePrefix(byte[] actual, byte[] expected, String label) {
        if (actual.length < expected.length) {
            throw new IllegalStateException("Unexpected Memoket " + label + " response");
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                throw new IllegalStateException("Unexpected Memoket " + label + " response");
            }
        }
    }

    private static void incrementBigEndian(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            bytes[i]++;
            if (bytes[i] != 0) return;
        }
    }
}
