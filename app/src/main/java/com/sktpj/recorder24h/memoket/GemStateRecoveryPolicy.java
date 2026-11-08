package com.sktpj.recorder24h.memoket;

/**
 * App-side interrupted operation recovery. This never verifies Gem's physical
 * recording state and never authorizes deleting a device-side audio file.
 */
public final class GemStateRecoveryPolicy {
    private GemStateRecoveryPolicy() {}

    public static boolean interruptedByProcessReplacement(
            String inputSource, String previousState) {
        // The process that owned this state no longer exists after restart,
        // even if the user had already switched the request flag off.
        if (!"MEMOKET".equals(inputSource)) return false;
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
