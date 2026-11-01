package com.sktpj.recorder24h.memoket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MemoketOgg {
    private MemoketOgg() {}

    public static byte[] encode(byte[] raw, int serial) {
        if (raw == null || raw.length == 0 || raw.length % 80 != 0) {
            throw new IllegalArgumentException("Unsupported OPUS frame size");
        }
        for (int i = 0; i < raw.length; i += 80) {
            if ((raw[i] & 0xff) != 0xbc) throw new IllegalArgumentException("Unknown Memoket OPUS layout");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] head = new byte[] {'O','p','u','s','H','e','a','d',1,1,0,0,(byte)0x80,0x3e,0,0,0,0,0};
        byte[] vendor = "24hRecoder".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
        writeLE(tag, vendor.length, 4);
        tag.writeBytes(vendor);
        writeLE(tag, 0, 4);
        result.writeBytes(page(new byte[][]{head}, serial, 0, 0, 2));
        result.writeBytes(page(new byte[][]{tag.toByteArray()}, serial, 1, 0, 0));
        int frameCount = raw.length / 80;
        int pageNumber = 2;
        for (int first = 0; first < frameCount; first += 100) {
            int count = Math.min(100, frameCount - first);
            byte[][] packets = new byte[count][];
            for (int i = 0; i < count; i++) {
                packets[i] = Arrays.copyOfRange(raw, (first + i) * 80, (first + i + 1) * 80);
            }
            int flags = first + count == frameCount ? 4 : 0;
            result.writeBytes(page(packets, serial, pageNumber++, (long) (first + count) * 960, flags));
        }
        return result.toByteArray();
    }

    private static byte[] page(byte[][] packets, int serial, int seq, long granule, int flags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'O','g','g','S',0,(byte) flags});
        writeLE(out, granule, 8);
        writeLE(out, serial, 4);
        writeLE(out, seq, 4);
        writeLE(out, 0, 4);
        out.write(packets.length);
        for (byte[] packet : packets) out.write(packet.length);
        for (byte[] packet : packets) out.writeBytes(packet);
        byte[] data = out.toByteArray();
        int crc = 0;
        for (byte value : data) {
            crc ^= (value & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ ((crc & 0x80000000) != 0 ? 0x04c11db7 : 0);
            }
        }
        data[22] = (byte) crc;
        data[23] = (byte) (crc >>> 8);
        data[24] = (byte) (crc >>> 16);
        data[25] = (byte) (crc >>> 24);
        return data;
    }

    private static void writeLE(ByteArrayOutputStream out, long value, int length) {
        for (int i = 0; i < length; i++) out.write((int) (value >>> (i * 8)) & 0xff);
    }
}
