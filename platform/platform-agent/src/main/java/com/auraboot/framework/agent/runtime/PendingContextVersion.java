package com.auraboot.framework.agent.runtime;

/**
 * Stable record-scope version metadata persisted with a pending tool snapshot.
 */
public record PendingContextVersion(
        String modelCode,
        String recordPid,
        String recordVersion,
        String contextVersion) {

    public static PendingContextVersion unresolved(String modelCode, String recordPid) {
        return new PendingContextVersion(modelCode, recordPid, null, null);
    }

    public boolean verifiable() {
        return hasText(modelCode) && hasText(recordPid) && hasText(recordVersion) && hasText(contextVersion);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
