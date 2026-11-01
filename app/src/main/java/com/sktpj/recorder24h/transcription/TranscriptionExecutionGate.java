package com.sktpj.recorder24h.transcription;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide ownership for the transcription execution lane.
 *
 * Both the WorkManager drain worker and the user-visible foreground queue service must acquire
 * this gate before they may drain the persisted queue. This keeps exactly one logical runner
 * active; LocalWhisperEngine.class remains synchronized as a second safety net around inference.
 */
final class TranscriptionExecutionGate {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    private TranscriptionExecutionGate() {
    }

    static boolean tryAcquire() {
        return ACTIVE.compareAndSet(false, true);
    }

    static void release() {
        ACTIVE.set(false);
    }

    static boolean isActive() {
        return ACTIVE.get();
    }
}
