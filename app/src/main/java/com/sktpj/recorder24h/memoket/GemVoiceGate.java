package com.sktpj.recorder24h.memoket;

public final class GemVoiceGate {
    private static final long SPEECH_TO_START_MS = 2_000L;
    private static final long SILENCE_TO_STOP_MS = 60_000L;
    private long lastSpeechAtMs;
    private boolean starting;
    private boolean recording;
    private boolean stopping;
    private boolean fetching;
    private boolean userStopped;

    public void heardSpeech(long timeMs) {
        lastSpeechAtMs = timeMs;
    }

    public boolean shouldStart(long nowMs) {
        return !userStopped && !starting && !recording && !stopping && !fetching
                && lastSpeechAtMs > 0L && nowMs - lastSpeechAtMs < SPEECH_TO_START_MS;
    }

    public void startSent() {
        starting = true;
    }

    public void started() {
        starting = false;
        recording = true;
    }

    public boolean shouldStop(long nowMs) {
        return recording && !stopping && !fetching && (userStopped
                || lastSpeechAtMs <= 0L || nowMs - lastSpeechAtMs >= SILENCE_TO_STOP_MS);
    }

    public void stopSent() {
        stopping = true;
    }

    public void fetchStarted() {
        stopping = false;
        fetching = true;
    }

    public void acquired() {
        starting = false;
        recording = false;
        stopping = false;
        fetching = false;
    }

    public void stopRequested() {
        userStopped = true;
    }

    public boolean canExit() {
        return userStopped && !starting && !recording && !stopping && !fetching;
    }

    public boolean isStarting() {
        return starting;
    }

    public boolean isRecording() {
        return recording;
    }

    public boolean isStopping() {
        return stopping;
    }

    public boolean isFetching() {
        return fetching;
    }

    public boolean isStopped() {
        return userStopped;
    }
}
