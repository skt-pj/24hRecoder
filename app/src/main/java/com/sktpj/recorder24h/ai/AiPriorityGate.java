package com.sktpj.recorder24h.ai;

import android.content.Context;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes actual AI inference according to the user-visible semantic queue order.
 *
 * WorkManager does not expose per-request priorities. Multiple workers may therefore reach the
 * inference boundary at the same time. They wait here without consuming a WorkManager retry; only
 * the highest eligible semantic queue entry is allowed to enter inference. A request that is
 * already inside inference is never preempted by a later reorder.
 */
public final class AiPriorityGate {
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Condition CHANGED = LOCK.newCondition();
    private static volatile String activeQueueId;
    private static volatile long activeStartedAtMs;

    private AiPriorityGate() {
    }

    public static Turn awaitTurn(Context context, String queueId) {
        if (queueId == null || queueId.isEmpty()) return null;
        Context app = context.getApplicationContext();
        LOCK.lock();
        try {
            while (true) {
                if (!contains(app, queueId)) {
                    return null;
                }
                if (activeQueueId == null && isHighestEligible(app, queueId)) {
                    activeQueueId = queueId;
                    activeStartedAtMs = System.currentTimeMillis();
                    return new Turn(queueId);
                }
                try {
                    CHANGED.await(500L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        } finally {
            LOCK.unlock();
        }
    }

    public static boolean isActive(String queueId) {
        return queueId != null && queueId.equals(activeQueueId);
    }

    public static long activeElapsedMs(String queueId) {
        if (!isActive(queueId) || activeStartedAtMs <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - activeStartedAtMs);
    }

    public static void signalChanged() {
        LOCK.lock();
        try {
            CHANGED.signalAll();
        } finally {
            LOCK.unlock();
        }
    }

    private static boolean contains(Context context, String queueId) {
        for (AiQueueStore.Entry entry : AiQueueStore.load(context)) {
            if (queueId.equals(entry.id)) return true;
        }
        return false;
    }

    private static boolean isHighestEligible(Context context, String queueId) {
        long now = System.currentTimeMillis();
        List<AiQueueStore.Entry> entries = AiQueueStore.load(context);
        for (AiQueueStore.Entry entry : entries) {
            if (!isInferenceCandidate(entry, now)) continue;
            return queueId.equals(entry.id);
        }
        return false;
    }

    private static boolean isInferenceCandidate(AiQueueStore.Entry entry, long now) {
        if (AiQueueStore.STATE_RUNNING.equals(entry.state)) return true;
        if (!AiQueueStore.STATE_QUEUED.equals(entry.state)) return false;

        if (AiAnalysisScheduler.KIND_HOURLY.equals(entry.kind)) {
            return entry.periodEndMs <= now;
        }
        if (AiAnalysisScheduler.KIND_DAILY.equals(entry.kind)) {
            return entry.periodEndMs + 15L * 60L * 1000L <= now;
        }
        // Rollups only become semantic queue rows once the rollup worker is actually running.
        return false;
    }

    public static final class Turn implements AutoCloseable {
        private final String queueId;
        private boolean closed;

        private Turn(String queueId) {
            this.queueId = queueId;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            LOCK.lock();
            try {
                if (queueId.equals(activeQueueId)) {
                    activeQueueId = null;
                    activeStartedAtMs = 0L;
                }
                CHANGED.signalAll();
            } finally {
                LOCK.unlock();
            }
        }
    }
}
