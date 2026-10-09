package com.sktpj.recorder24h.memoket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class MemoketTransfer {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.opus");
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    public interface CompletedFile {
        void persist(String name, byte[] payload, long expectedCrc) throws Exception;
    }

    private final CompletedFile save;
    private String name;
    private long expectedSize;
    private long expectedCrc;
    private final CRC32 crc = new CRC32();
    private ByteArrayOutputStream buffer;
    private int nextSequence;
    private int completedCount;
    private boolean done;
    private boolean streaming;

    public MemoketTransfer(CompletedFile save) {
        this.save = save;
    }

    public static byte[] initialCommand() {
        return new byte[] {1, 0, 0};
    }

    public static byte[] metadataCommand() {
        return new byte[] {2, 0};
    }

    public static byte[] downloadCommand() {
        return new byte[] {3};
    }

    public synchronized byte[] onControl(byte[] payload) throws Exception {
        if (done || payload == null || payload.length == 0) return null;
        int code = payload[0] & 0xff;
        if (code == 1) {
            if (payload.length < 4 || payload[1] != 1 || payload[2] != 1) {
                done = true;
                return null;
            }
            String incoming = new String(payload, 3, payload.length - 3, StandardCharsets.US_ASCII);
            requireName(incoming);
            name = incoming;
            return metadataCommand();
        }
        if (code == 2) {
            int ext = indexOf(payload, ".opus".getBytes(StandardCharsets.US_ASCII));
            if (ext < 0 || payload.length < ext + 13) throw new IllegalStateException("Memoket metadata missing size/CRC");
            String metadataName = new String(payload, 5, ext + 5 - 5, StandardCharsets.US_ASCII);
            requireName(metadataName);
            if (!metadataName.equals(name)) throw new IllegalStateException("Memoket filename changed during sync");
            expectedSize = u32(payload, ext + 5);
            expectedCrc = u32(payload, ext + 9);
            if (expectedSize == 0 || expectedSize > MAX_BYTES) throw new IllegalStateException("Memoket file exceeds safe limit");
            buffer = new ByteArrayOutputStream((int) expectedSize);
            crc.reset();
            nextSequence = 0;
            streaming = true;
            return downloadCommand();
        }
        if (code == 3 && payload.length >= 2 && (payload[1] & 0xff) == 0xff) {
            if (!streaming || buffer == null || buffer.size() != expectedSize || crc.getValue() != expectedCrc) {
                throw new IllegalStateException("Memoket transfer incomplete or CRC mismatch");
            }
            byte[] data = buffer.toByteArray();
            save.persist(name, data, expectedCrc);
            completedCount++;
            streaming = false;
            buffer = null;
            byte[] n = name.getBytes(StandardCharsets.US_ASCII);
            byte[] ack = new byte[n.length + 2];
            ack[0] = 5;
            ack[1] = (byte) n.length;
            System.arraycopy(n, 0, ack, 2, n.length);
            return ack;
        }
        if (code == 5 && payload.length >= 2 && payload[1] == 1) {
            if (completedCount >= 50) {
                done = true;
                return null;
            }
            name = null;
            return initialCommand();
        }
        return null;
    }

    public synchronized void onData(byte[] payload) {
        if (!streaming || buffer == null || payload == null || payload.length < 6) return;
        long sequence = ((long) (payload[0] & 0xff) << 32)
                | ((long) (payload[1] & 0xff) << 24)
                | ((long) (payload[2] & 0xff) << 16)
                | ((long) (payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);
        if (sequence != nextSequence) throw new IllegalStateException("Memoket block order mismatch");
        int size = payload.length - 5;
        if ((long) buffer.size() + size > expectedSize) throw new IllegalStateException("Memoket file length exceeded");
        buffer.write(payload, 5, size);
        crc.update(payload, 5, size);
        nextSequence++;
    }

    public synchronized boolean isDone() { return done; }
    public synchronized int completedCount() { return completedCount; }

    private static void requireName(String value) {
        if (value == null || value.length() > 100 || !SAFE_NAME.matcher(value).matches()
                || value.startsWith(".")) throw new IllegalStateException("Unsafe Memoket filename");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) return i;
        }
        return -1;
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }
}
