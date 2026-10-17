package com.sktpj.recorder24h.memoket;

/** Evidence classification for one-session file diagnostics. */
public final class MemoketBatchAssessment {
    private MemoketBatchAssessment() {}

    public static String fileState(int savedFiles, String error, long firstDataSequence) {
        if (savedFiles > 0) return "AUDIO_SAVED";
        if (error != null && !error.isEmpty()) return "FAILED";
        if (firstDataSequence > 0) return "PARTIAL_STREAM_ALREADY_ACTIVE";
        return "NO_FILE_RECEIVED";
    }
}
