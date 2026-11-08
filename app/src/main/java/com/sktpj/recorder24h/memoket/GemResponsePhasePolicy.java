package com.sktpj.recorder24h.memoket;

/** Observed 020000 metadata-response handling; does not prove physical STOP. */
final class GemResponsePhasePolicy {
    static final int MAX_020000_REPLIES = 3;

    private GemResponsePhasePolicy() {}

    static boolean is020000(byte[] reply) {
        return reply != null && reply.length == 3
                && reply[0] == 0x02 && reply[1] == 0x00 && reply[2] == 0x00;
    }

    static boolean exceeded020000Limit(int consecutiveReplies) {
        return consecutiveReplies >= MAX_020000_REPLIES;
    }
}
