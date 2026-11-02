package com.sktpj.recorder24h.memoket;

final class MemoketContinuousIntentPolicy {
    private static final String START = "com.sktpj.recorder24h.memoket.START_CONTINUOUS";
    private static final String STOP = "com.sktpj.recorder24h.memoket.STOP_CONTINUOUS";

    private MemoketContinuousIntentPolicy() {}

    static String resolveAction(String action, boolean recordingRequested, String source) {
        if (action != null) return action;
        return recordingRequested && "MEMOKET".equals(source) ? START : null;
    }

    static boolean isContinuousStart(String action) {
        return START.equals(action);
    }

    static boolean isContinuousStop(String action) {
        return STOP.equals(action);
    }
}
