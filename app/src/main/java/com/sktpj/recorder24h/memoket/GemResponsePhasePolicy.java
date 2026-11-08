package com.sktpj.recorder24h.memoket;

/**
 * Separates successful GATT command writes from verified file-protocol replies.
 * Neither reply independently proves Gem's physical record/stop state.
 */
final class GemResponsePhasePolicy {
    static final long FILE_ANNOUNCEMENT_TIMEOUT_MS = 20_000L;
    static final int MAX_020000_REPLIES = 3;

    private GemResponsePhasePolicy() {}

    static boolean receivedFileAnnouncement(byte[] reply, String transferState) {
        return reply != null && reply.length > 3
                && reply[0] == 0x01 && reply[1] == 0x01 && reply[2] == 0x01
                && "RECEIVING_DATA".equals(transferState);
    }

    static boolean is020000(byte[] reply) {
        return reply != null && reply.length == 3
                && reply[0] == 0x02 && reply[1] == 0x00 && reply[2] == 0x00;
    }

    static boolean exceeded020000Limit(int consecutiveReplies) {
        return consecutiveReplies >= MAX_020000_REPLIES;
    }
}
