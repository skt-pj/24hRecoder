package com.sktpj.recorder24h.memoket;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict time window for files created during a user-started Gem recording. */
public final class MemoketRecordingWindow {
    private static final Pattern NAME = Pattern.compile("^(\\d{8}_\\d{6})(?:_[A-Za-z0-9_-]+)?\\.opus$");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final long CLOCK_ALLOWANCE_MS = 10_000L;
    private final long startMs;
    private final long stopMs;
    private final ZoneId zone;

    public MemoketRecordingWindow(long startMs, long stopMs, String zoneId) {
        if (startMs <= 0 || stopMs < startMs || zoneId == null || zoneId.isEmpty()) {
            throw new IllegalArgumentException("Gemの録音期間を確定できません。自動で過去の録音を取得しません");
        }
        this.startMs = startMs;
        this.stopMs = stopMs;
        this.zone = ZoneId.of(zoneId);
    }

    public boolean matches(String filename) {
        if (filename == null) return false;
        Matcher matcher = NAME.matcher(filename);
        if (!matcher.matches()) return false;
        try {
            long recordedAt = LocalDateTime.parse(matcher.group(1), FORMAT)
                    .atZone(zone).toInstant().toEpochMilli();
            return recordedAt >= startMs - CLOCK_ALLOWANCE_MS
                    && recordedAt <= stopMs + CLOCK_ALLOWANCE_MS;
        } catch (DateTimeException exception) {
            return false;
        }
    }

    public long startMs() { return startMs; }
    public long stopMs() { return stopMs; }
    public String zoneId() { return zone.getId(); }
}
