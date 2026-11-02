package com.sktpj.recorder24h.memoket;

/** Never ACK a Gem file unless it falls within this app's persisted start/stop window. */
final class MemoketPendingFilePolicy {
    private MemoketPendingFilePolicy() {}

    static boolean mayAcknowledge(String name, long startedAtMs, long stopRequestedAtMs,
            String zoneId) {
        if (name == null || name.isEmpty() || startedAtMs <= 0
                || stopRequestedAtMs <= startedAtMs || zoneId == null || zoneId.isEmpty()) {
            return false;
        }
        try {
            return new MemoketRecordingWindow(startedAtMs, stopRequestedAtMs, zoneId).matches(name);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
