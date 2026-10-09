package com.sktpj.recorder24h.memoket;

import java.util.Arrays;

public final class MemoketSessionProtocol {
    private int step;

    public byte[] firstCommand() {
        step = 1;
        return new byte[]{0x00};
    }

    public byte[] onResponse(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalStateException("Empty Memoket session response");
        }
        switch (step) {
            case 1:
                require(value, new byte[]{0x00, 0x00}, "00");
                step = 2;
                return new byte[]{0x27, 0x01};
            case 2:
                require(value, new byte[]{0x27, 0x02}, "27");
                step = 3;
                return new byte[]{(byte) 0xe1};
            case 3:
                if ((value[0] & 0xff) != 0xe1) throw new IllegalStateException("Unexpected e1 response");
                step = 4;
                return new byte[]{(byte) 0xf3, 0x00};
            case 4:
                if ((value[0] & 0xff) != 0xf3) throw new IllegalStateException("Unexpected f3 response");
                step = 5;
                return new byte[]{(byte) 0xe3, 0x01};
            case 5:
                if ((value[0] & 0xff) != 0xe3) throw new IllegalStateException("Unexpected e3 response");
                step = 6;
                return new byte[]{(byte) 0xff, 0x68};
            case 6:
                if (value.length < 6 || (value[0] & 0xff) != 0xff || (value[1] & 0xff) != 0x68) {
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
                require(value, new byte[]{(byte) 0xe5, 0x01}, "e5");
                step = 8;
                return new byte[]{(byte) 0xe8};
            case 8:
                if ((value[0] & 0xff) != 0xe8) throw new IllegalStateException("Unexpected e8 response");
                step = 9;
                return MemoketTransfer.initialCommand();
            default:
                return null;
        }
    }

    public boolean isReady() {
        return step >= 9;
    }

    private static void require(byte[] actual, byte[] expected, String label) {
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException("Unexpected Memoket " + label + " response");
        }
    }

    private static void incrementBigEndian(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            bytes[i]++;
            if (bytes[i] != 0) return;
        }
    }
}
