package com.sktpj.recorder24h.transcription;

/** Keep WorkManager's scheduler in the default application process. */
final class DrainProcessPolicy {
    private DrainProcessPolicy() { }

    static boolean isMainProcess(String processName, String applicationId) {
        return applicationId != null && applicationId.equals(processName);
    }
}
