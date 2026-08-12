package com.sktpj.recorder24h.transcription;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local cancellation generation for the normal/postprocess transcription runner. */
public final class TranscriptionCancellation {
    public static final String CANCELLED = "POSTPROCESS_TRANSCRIPTION_CANCELLED";
    private static final AtomicLong GENERATION = new AtomicLong(0L);

    private TranscriptionCancellation() {
    }

    public static long snapshot() {
        return GENERATION.get();
    }

    public static boolean isCancelled(long token) {
        return GENERATION.get() != token;
    }

    public static void throwIfCancelled(long token) {
        if (isCancelled(token)) {
            throw new IllegalStateException(CANCELLED);
        }
    }

    /** Cancel only the normal/postprocess runner in this process. Live ASR is in :streaming_asr. */
    public static long cancelCurrent() {
        long generation = GENERATION.incrementAndGet();
        try {
            LocalWhisperEngine.setNativePostprocessCancellationGeneration(generation);
        } catch (Throwable ignored) {
        }
        try {
            AndroidOnDeviceAsr.cancelActivePostprocessRecognition();
        } catch (Throwable ignored) {
        }
        return generation;
    }

    public static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(CANCELLED)) return true;
            current = current.getCause();
        }
        return false;
    }
}
