package com.sktpj.recorder24h.memoket;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public final class MemoketTransfer {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.opus");
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private enum State {
        WAIT_LIST,
        RECEIVING_DATA,
        WAIT_FINALIZE,
        WAIT_ACK,
        WAIT_POST_DELETE_STATUS,
        DONE
    }

    public interface CompletedFile {
        void persist(String name, byte[] payload, long expectedCrc) throws Exception;
    }

    /** Applied as soon as the Gem announces a filename, before accepting any audio bytes. */
    public interface FileSelector {
        boolean accept(String filename);
    }

    public static final class OlderRecordingBlockedException extends IllegalStateException {
        public OlderRecordingBlockedException(String filename) {
            super("今回の録音ではないファイルがGemの転送待ちにあります: " + filename
                    + "。過去の録音は取得・削除していません。必要なら手動取得してください。");
        }
    }

    private final CompletedFile save;
    private final FileSelector selector;
    private final int maxFiles;
    private final boolean verifyDeviceRemoval;
    private String lastDeletionAckFile;
    // 05 01 confirms the named acknowledgement; 00 00 confirms idle.
    // Neither response independently proves flash deletion.
    private int deleteAcks;
    private boolean postDeleteIdleConfirmed;
    private boolean finalizeInfoVerified;
    private int selectedFileId = -1;
    private String name;
    private long expectedSize = -1;
    private long expectedCrc = -1;
    private final CRC32 crc = new CRC32();
    private ByteArrayOutputStream buffer;
    private long nextSequence;
    private int completedCount;
    private boolean finalizeRequested;
    private State state = State.WAIT_LIST;

    public MemoketTransfer(CompletedFile save) {
        this(save, null);
    }

    public MemoketTransfer(CompletedFile save, FileSelector selector) {
        this(save, selector, 50);
    }

    /** maxFiles=1 for the Gem recording that the user explicitly stopped. */
    public MemoketTransfer(CompletedFile save, FileSelector selector, int maxFiles) {
        this(save, selector, maxFiles, false);
    }

    /** Follow the official 05 01 acknowledgement with non-destructive 00 status, never a second 01. */
    public MemoketTransfer(CompletedFile save, FileSelector selector, int maxFiles,
            boolean verifyDeviceRemoval) {
        if (maxFiles < 1) throw new IllegalArgumentException("Invalid file limit");
        this.save = save;
        this.selector = selector;
        this.maxFiles = maxFiles;
        this.verifyDeviceRemoval = verifyDeviceRemoval;
    }

    public static byte[] initialCommand() {
        return new byte[] {1, 0, 0};
    }

    public static byte[] metadataCommand() {
        return new byte[] {2, 0};
    }

    public static byte[] finalizeCommand() {
        return new byte[] {3};
    }

    /** Kept for source compatibility with older callers. */
    public static byte[] downloadCommand() {
        return finalizeCommand();
    }

    public synchronized byte[] onControl(byte[] payload) throws Exception {
        if (state == State.DONE || payload == null || payload.length == 0) return null;
        int code = payload[0] & 0xff;

        if (state == State.WAIT_LIST) {
            if (code != 1) return null;
            if (payload.length >= 3 && payload[1] == 0 && payload[2] == 0) {
                state = State.DONE;
                return null;
            }
            if (payload.length < 4 || payload[1] != 1 || payload[2] != 1) {
                throw new IllegalStateException("Gem一覧が未確定です: " + hex(payload));
            }
            String incoming = new String(payload, 3, payload.length - 3, StandardCharsets.US_ASCII);
            requireName(incoming);
            if (selector != null && !selector.accept(incoming)) {
                // A request for 01 immediately starts DATA on this Gem.
                // Never delete a different pending file to reach the target.
                throw new OlderRecordingBlockedException(incoming);
            }
            name = incoming;
            expectedSize = -1;
            expectedCrc = -1;
            buffer = new ByteArrayOutputStream();
            crc.reset();
            nextSequence = 0;
            finalizeRequested = false;
            finalizeInfoVerified = false;
            selectedFileId = -1;
            state = State.RECEIVING_DATA;
            // Observed BLE behavior: 01 starts DATA streaming.
            // Only send 02 after DATA has gone quiet.
            return null;
        }

        if (state == State.RECEIVING_DATA || state == State.WAIT_FINALIZE) {
            if (code == 2) {
                byte[] nameBytes = name == null ? null : name.getBytes(StandardCharsets.US_ASCII);
                int nameOffset;
                if (verifyDeviceRemoval) {
                    // 02 00 01 fileIdBE16 + filename + sizeBE32 + crcBE32.
                    // Official 2026-10-10: id=5; Oct-13 three-file capture: id=9.
                    if (payload.length == 3 && payload[1] == 0 && payload[2] == 2) return null;
                    int required = nameBytes == null ? -1 : 5 + nameBytes.length + 8;
                    if (payload.length != required || payload[1] != 0
                            || payload[2] != 1 || !bytesAt(payload, 5, nameBytes)) {
                        throw new IllegalStateException("公式02メタデータの構造・対象名が不正: " + hex(payload));
                    }
                    selectedFileId = u16(payload, 3);
                    nameOffset = 5;
                } else {
                    nameOffset = nameBytes == null ? -1 : indexOf(payload, nameBytes);
                    if (nameOffset < 0) {
                        if (payload.length <= 4) return null;
                        throw new IllegalStateException("Memoket metadata filename missing payload=" + hex(payload));
                    }
                }
                int valuesOffset = nameOffset + nameBytes.length;
                if (payload.length < valuesOffset + 8) {
                    throw new IllegalStateException("Memoket metadata missing size/CRC payload=" + hex(payload));
                }
                expectedSize = u32(payload, valuesOffset);
                expectedCrc = u32(payload, valuesOffset + 4);
                if (expectedSize <= 0 || expectedSize > MAX_BYTES) {
                    throw new IllegalStateException("Memoket file size invalid size=" + expectedSize + " payload=" + hex(payload));
                }
                if (buffer == null) throw new IllegalStateException("Memoket data buffer missing");
                if (buffer.size() > expectedSize) {
                    throw new IllegalStateException("Memoket file length exceeded expected=" + expectedSize + " actual=" + buffer.size());
                }
                state = State.WAIT_FINALIZE;
                return finalizeIfComplete();
            }

            if (code == 3 && state == State.WAIT_FINALIZE) {
                if (payload.length < 2 || !finalizeRequested) return null;
                int response = payload[1] & 0xff;
                if (response != 0xff) {
                    // 03 01 has one entry in the old official HCI, but 03 03
                    // has three entries in Oct-13 real Gem diagnostics.
                    if (!verifyDeviceRemoval) return null;
                    verify03Listing(payload);
                    finalizeInfoVerified = true;
                    return null;
                }
                if (verifyDeviceRemoval && !finalizeInfoVerified) {
                    throw new IllegalStateException("公式03 01のファイル名・サイズ応答が確認できません");
                }
                verifyComplete();
                byte[] data = buffer.toByteArray();
                save.persist(name, data, expectedCrc);
                completedCount++;
                buffer = null;
                byte[] n = name.getBytes(StandardCharsets.US_ASCII);
                byte[] ack = new byte[n.length + 2];
                ack[0] = 5;
                ack[1] = (byte) n.length;
                System.arraycopy(n, 0, ack, 2, n.length);
                state = State.WAIT_ACK;
                return ack;
            }
            return null;
        }

        if (state == State.WAIT_ACK) {
            if (code != 5 || payload.length != 2 || payload[1] != 1) return null;
            deleteAcks++;
            if (verifyDeviceRemoval) {
                lastDeletionAckFile = name;
                name = null;
                state = State.WAIT_POST_DELETE_STATUS;
                // Official HCI does not request 01 again after 05 01.
                return new byte[]{0x00};
            }
            if (completedCount >= maxFiles) {
                state = State.DONE;
                return null;
            }
            name = null;
            expectedSize = -1;
            expectedCrc = -1;
            finalizeRequested = false;
            state = State.WAIT_LIST;
            return initialCommand();
        }

        if (state == State.WAIT_POST_DELETE_STATUS) {
            if (code != 0) return null;
            if (payload.length == 2 && payload[1] == 0) {
                postDeleteIdleConfirmed = true;
                lastDeletionAckFile = null;
                state = State.DONE;
            }
            // 00 01 can also appear during DATA transfer: never treat as idle.
            return null;
        }
        return null;
    }

    public synchronized byte[] onData(byte[] payload) {
        if ((state != State.RECEIVING_DATA && state != State.WAIT_FINALIZE)
                || buffer == null || payload == null || payload.length < 6) return null;
        long sequence = ((long) (payload[0] & 0xff) << 32)
                | ((long) (payload[1] & 0xff) << 24)
                | ((long) (payload[2] & 0xff) << 16)
                | ((long) (payload[3] & 0xff) << 8)
                | (payload[4] & 0xff);
        if (sequence != nextSequence) {
            throw new IllegalStateException("Memoket block order mismatch expected=" + nextSequence + " actual=" + sequence);
        }
        int size = payload.length - 5;
        if ((long) buffer.size() + size > MAX_BYTES) {
            throw new IllegalStateException("Memoket file exceeded diagnostic memory limit "
                    + MAX_BYTES + " bytes before verified metadata and CRC");
        }
        if (expectedSize > 0 && (long) buffer.size() + size > expectedSize) {
            throw new IllegalStateException("Memoket file length exceeded expected=" + expectedSize
                    + " actualAfterBlock=" + ((long) buffer.size() + size));
        }
        buffer.write(payload, 5, size);
        crc.update(payload, 5, size);
        nextSequence++;
        if (state == State.WAIT_FINALIZE) return finalizeIfComplete();
        return null;
    }

    public synchronized boolean shouldRequestMetadata() {
        return state == State.RECEIVING_DATA && buffer != null && buffer.size() > 0;
    }

    public synchronized int bufferedBytes() {
        return buffer == null ? 0 : buffer.size();
    }

    public synchronized long dataBlockCount() {
        return nextSequence;
    }

    public synchronized boolean isDone() { return state == State.DONE; }
    public synchronized int completedCount() { return completedCount; }
    /** Deprecated: Gem flash deletion is not readable from 05 ACK or 00 idle. */
    @Deprecated public synchronized int verifiedRemovals() { return 0; }
    public synchronized int deleteAcks() { return deleteAcks; }
    public synchronized boolean postDeleteIdleConfirmed() { return postDeleteIdleConfirmed; }
    public synchronized boolean waitingForIdleStatus() { return state == State.WAIT_POST_DELETE_STATUS; }
    public synchronized String debugState() { return state.name(); }
    public synchronized String debugFileName() { return name == null ? "" : name; }
    public synchronized long debugExpectedSize() { return expectedSize; }
    public synchronized String debugExpectedCrcHex() {
        return expectedCrc < 0 ? "" : Long.toHexString(expectedCrc);
    }

    private byte[] finalizeIfComplete() {
        if (expectedSize <= 0 || buffer == null || buffer.size() < expectedSize
                || finalizeRequested) return null;
        verifyComplete();
        finalizeRequested = true;
        return finalizeCommand();
    }

    private void verifyComplete() {
        if (buffer == null || expectedSize <= 0 || expectedCrc < 0
                || buffer.size() != expectedSize || crc.getValue() != expectedCrc) {
            throw new IllegalStateException(
                    "Memoket transfer incomplete expectedSize=" + expectedSize
                            + " actualSize=" + (buffer == null ? -1 : buffer.size())
                            + " expectedCrc=" + Long.toHexString(expectedCrc)
                            + " actualCrc=" + Long.toHexString(crc.getValue()));
        }
    }

    private static void requireName(String value) {
        if (value == null || value.length() > 100 || !SAFE_NAME.matcher(value).matches()
                || value.startsWith(".")) throw new IllegalStateException("Unsafe Memoket filename");
    }

    private static boolean bytesAt(byte[] data, int offset, byte[] expected) {
        if (expected == null || offset < 0 || data.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void verify03Listing(byte[] payload) {
        int count = payload[1] & 0xff;
        if (count < 1 || count > 50 || selectedFileId < 0 || name == null) {
            throw new IllegalStateException("03リストの件数または02のfileIdが不正");
        }
        int cursor = 2;
        boolean selectedFound = false;
        String selectedTimestamp = timestamp(name);
        for (int i = 0; i < count; i++) {
            if (cursor + 4 > payload.length) {
                throw new IllegalStateException("03リストが途中で切れました");
            }
            int index = payload[cursor++] & 0xff;
            int fileId = u16(payload, cursor);
            cursor += 2;
            int len = payload[cursor++] & 0xff;
            if (index != i || len < 1 || len > 100 || cursor + len + 4 > payload.length) {
                throw new IllegalStateException("03リストのindexまたは名前長が不正");
            }
            String listed = new String(payload, cursor, len, StandardCharsets.US_ASCII);
            requireName(listed);
            cursor += len;
            long listedSize = u32(payload, cursor);
            cursor += 4;
            String listedTimestamp = timestamp(listed);
            if (listedTimestamp.compareTo(selectedTimestamp) > 0
                    || (listedTimestamp.equals(selectedTimestamp) && !listed.equals(name))) {
                throw new IllegalStateException("03に今回の対象より新しい・同時刻の別ファイルがあります: " + listed);
            }
            if (listed.equals(name)) {
                if (selectedFound || fileId != selectedFileId || listedSize != expectedSize) {
                    throw new IllegalStateException("03と02の対象fileId・名前・サイズが一致しません");
                }
                selectedFound = true;
            }
        }
        if (!selectedFound || cursor != payload.length) {
            throw new IllegalStateException("03の全一覧に一致する最新ファイルがありません");
        }
    }

    private static String timestamp(String filename) {
        if (filename == null || !filename.matches("[0-9]{8}_[0-9]{6}_[A-Za-z0-9_-]+\\.opus")) {
            throw new IllegalStateException("最新順を判定できないファイル名: " + filename);
        }
        return filename.substring(0, 15);
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
