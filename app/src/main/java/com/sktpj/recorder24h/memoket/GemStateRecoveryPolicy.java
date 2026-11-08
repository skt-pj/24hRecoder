package com.sktpj.recorder24h.memoket;

/**
 * App-side interrupted operation recovery. This never verifies Gem's physical
 * recording state and never authorizes deleting a device-side audio file.
 */
public final class GemStateRecoveryPolicy {
    private GemStateRecoveryPolicy() {}

    public static boolean interruptedByProcessReplacement(
            String inputSource, boolean wasRequested, String previousState) {
        if (!"MEMOKET".equals(inputSource) || !wasRequested) return false;
        return "STARTING".equals(previousState)
                || "MONITORING".equals(previousState)
                || "RECORDING".equals(previousState)
                || "STOPPING".equals(previousState)
                || "TRANSFERRING".equals(previousState)
                || "ROTATING".equals(previousState)
                || "RECOVERING".equals(previousState);
    }

    public static boolean requiresExplicitLocalAbort(String inputSource, String currentState) {
        return "MEMOKET".equals(inputSource)
                && ("STOPPING".equals(currentState)
                    || "TRANSFERRING".equals(currentState));
    }
}
