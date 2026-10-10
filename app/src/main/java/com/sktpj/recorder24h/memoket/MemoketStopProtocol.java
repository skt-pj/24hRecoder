package com.sktpj.recorder24h.memoket;

/** Confirm recording stop from DATA notification OFF -> ON, not from file downloads. */
public final class MemoketStopProtocol {
    public enum Next { ENABLE_DATA, RECORDING_STOPPED }
    private enum State { IDLE, WAIT_OFF, WAIT_ON, STOPPED }
    private State state = State.IDLE;

    public void begin() {
        if (state != State.IDLE) throw new IllegalStateException("Stop already requested");
        state = State.WAIT_OFF;
    }

    public Next onDataNotificationWriteSucceeded(boolean enabled) {
        if (state == State.WAIT_OFF && !enabled) {
            state = State.WAIT_ON;
            return Next.ENABLE_DATA;
        }
        if (state == State.WAIT_ON && enabled) {
            state = State.STOPPED;
            return Next.RECORDING_STOPPED;
        }
        throw new IllegalStateException("Unexpected stop notification transition: " + state + " enabled=" + enabled);
    }

    public boolean isStopped() {
        return state == State.STOPPED;
    }
}
