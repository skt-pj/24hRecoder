package com.sktpj.recorder24h.memoket;

import java.util.Arrays;

public final class MemoketSessionProtocol {
    private int step;
    private boolean allowRecordingStatus;
    private String recordingFileHint = "";
    private boolean initialIdle;
    public boolean initialIdleReported() { return initialIdle; }
    // 00 01 + filename is observed during DATA transfer: not recording proof.
    private boolean busyOrTransferringReported;
    public boolean busyOrTransferringReported() { return busyOrTransferringReported; }
    public String recordingFileHint() { return recordingFileHint; }
    public void allowActiveRecordingStatus(boolean allowed) { allowRecordingStatus = allowed; }

    public byte[] firstCommand() {
        step = 1;
        initialIdle = false;
        busyOrTransferringReported = false;
        recordingFileHint = "";
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
                if (allowRecordingStatus && value.length >= 2
                        && value[0] == 0 && value[1] == 1) {
                    busyOrTransferringReported = true;
                    if (value.length > 4 && value[2] == 0 && value[3] == 2) {
                        String file = new String(value, 4, value.length - 4,
                                java.nio.charset.StandardCharsets.US_ASCII);
                        if (file.matches("[A-Za-z0-9._-]+\\.opus")) recordingFileHint = file;
                    }
                } else {
                    requirePrefix(value, new byte[]{0x00, 0x00}, "00");
                    if (value.length != 2) throw new IllegalStateException("Unexpected 00 idle response length");
                    initialIdle = true;
                }
                step = 2;
                return new byte[]{0x27, 0x01};
            case 2:
                if (opcode != 0x27) return null;
                requirePrefix(value, new byte[]{0x27, 0x02}, "27");
                step = 3;
                return new byte[]{(byte) 0xe1};
            case 3:
                if (opcode != 0xe1) return null;
                if (value.length != 3 || value[2] != 0x02) {
                    throw new IllegalStateException("Unexpected e1 handshake status: " + hex(value));
                }
                // Official 2026-10-31 HCI: e1 29 02 -> e3 01 directly, no f3 00.
                // Keep the earlier f3 route for other observed e1 status values.
                if ((value[1] & 0xff) == 0x29) {
                    step = 5;
                    return new byte[]{(byte) 0xe3, 0x01};
                }
                step = 4;
                return new byte[]{(byte) 0xf3, 0x00};
            case 4:
                if (opcode != 0xf3) return null;
                requirePrefix(value, new byte[]{(byte) 0xf3, 0x00, 0x00}, "f3");
                if (value.length != 3) throw new IllegalStateException("Unexpected f3 status length");
                step = 5;
                return new byte[]{(byte) 0xe3, 0x01};
            case 5:
                if (opcode != 0xe3) return null;
                step = 6;
                return new byte[]{(byte) 0xff, 0x68};
            case 6:
                if (opcode != 0xff) return null;
                if (value.length < 6 || (value[1] & 0xff) != 0x68) {
                    throw new IllegalStateException("Unexpected ff68 challenge response actual=" + hex(value));
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
                if (!isSupportedE8Response(value)) {
                    throw new IllegalStateException("Unexpected e8 response: " + hex(value));
                }
                step = 9;
                return null;
            default:
                return null;
        }
    }

    public boolean isReady() {
        return step >= 9;
    }

    public String debugStep() {
        switch (step) {
            case 0: return "IDLE";
            case 1: return "WAIT_0000";
            case 2: return "WAIT_2702";
            case 3: return "WAIT_E1";
            case 4: return "WAIT_F3";
            case 5: return "WAIT_E3";
            case 6: return "WAIT_FF68";
            case 7: return "WAIT_E501";
            case 8: return "WAIT_E8";
            default: return "READY";
        }
    }

    static boolean isSupportedE8Response(byte[] value) {
        if (value == null || value.length < 3 || (value[0] & 0xff) != 0xe8) return false;
        int format = value[1] & 0xff;
        int status = value[2] & 0xff;
        if ((format == 0x0c || format == 0x0e) && (status == 0 || status == 2)) {
            return true;
        }
        if (format != 0x0d || status != 1 || value.length != 16) return false;
        for (int i = 3; i < value.length; i++) {
            int c = value[i] & 0xff;
            if (!((c >= '0' && c <= '9') || c == '/')) return false;
        }
        return true;
    }

    private static void requirePrefix(byte[] actual, byte[] expected, String label) {
        if (actual.length < expected.length) {
            throw new IllegalStateException("Unexpected Memoket " + label + " response expected="
                    + hex(expected) + " actual=" + hex(actual));
        }
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                throw new IllegalStateException("Unexpected Memoket " + label + " response expected="
                        + hex(expected) + " actual=" + hex(actual));
            }
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static void incrementBigEndian(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            bytes[i]++;
            if (bytes[i] != 0) return;
        }
    }
}
