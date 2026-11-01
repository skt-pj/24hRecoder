package com.sktpj.recorder24h.ai;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the most recent successful inference duration for each concrete AI target period. */
public final class AiProcessingDurationStore {
    private static final String PREFS = "ai_processing_durations_v1";

    private AiProcessingDurationStore() {
    }

    public static void record(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs,
            long durationMs) {
        if (kind == null || kind.isEmpty() || periodStartMs <= 0L || periodEndMs <= periodStartMs) {
            return;
        }
        preferences(context).edit()
                .putLong(key(kind, periodStartMs, periodEndMs), Math.max(0L, durationMs))
                .apply();
    }

    public static long get(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs) {
        if (kind == null || kind.isEmpty() || periodStartMs <= 0L || periodEndMs <= periodStartMs) {
            return 0L;
        }
        return Math.max(0L, preferences(context)
                .getLong(key(kind, periodStartMs, periodEndMs), 0L));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String kind, long periodStartMs, long periodEndMs) {
        return kind + ":" + periodStartMs + ":" + periodEndMs;
    }
}
