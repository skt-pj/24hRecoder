package com.sktpj.recorder24h.memoket;

/**
 * DATA notifications disabled for the recording-control BLE connection.
 * Disabling the CCCD is a transport operation, not proof of device stop.
 *
 * Never automatically enable DATA again on this same connection: a user's
 * physical observation showed recording immediately restarting after OFF->ON.
 */
public final class MemoketStopProtocol {
    public enum Next { DISCONNECT_BEFORE_TRANSFER }
    private enum State { IDLE, WAIT_OFF, DATA_DISABLED }
    private State state = State.IDLE;

    public void begin() {
        if (state != State.IDLE) throw new IllegalStateException("Stop already requested");
        state = State.WAIT_OFF;
    }

    public Next onDataNotificationWriteSucceeded(boolean enabled) {
        if (state == State.WAIT_OFF && !enabled) {
            state = State.DATA_DISABLED;
            return Next.DISCONNECT_BEFORE_TRANSFER;
        }
        throw new IllegalStateException("Unexpected stop notification transition: " + state + " enabled=" + enabled);
    }

    public boolean notificationDisabled() { return state == State.DATA_DISABLED; }
}
