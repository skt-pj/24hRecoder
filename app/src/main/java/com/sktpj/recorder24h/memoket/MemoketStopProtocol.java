package com.sktpj.recorder24h.memoket;

/** Hardware stop must be confirmed by the user; CCCD acknowledgments are not physical stop evidence. */
public final class MemoketStopProtocol {
    public enum Next { PHYSICAL_STOP_REQUIRED, READY_TO_RETRIEVE }
    private boolean requested;
    private boolean confirmed;

    public Next requestStop() {
        if (requested) throw new IllegalStateException("Stop already requested");
        requested = true;
        return Next.PHYSICAL_STOP_REQUIRED;
    }
    public Next confirmPhysicalStop() {
        if (!requested || confirmed) throw new IllegalStateException("Unexpected hardware stop confirmation");
        confirmed = true;
        return Next.READY_TO_RETRIEVE;
    }
    public boolean isStopConfirmed() { return confirmed; }
}
