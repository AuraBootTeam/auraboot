package com.auraboot.framework.agent.runtime;

/**
 * Stable record-scope version metadata persisted with a pending tool snapshot.
 */
public record PendingContextVersion(
        String modelCode,
        String recordId,
        String recordVersion,
        String contextVersion) {

    public static PendingContextVersion unresolved(String modelCode, String recordId) {
        return new PendingContextVersion(modelCode, recordId, null, null);
    }

    public boolean verifiable() {
        return hasText(modelCode) && hasText(recordId) && hasText(recordVersion) && hasText(contextVersion);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
