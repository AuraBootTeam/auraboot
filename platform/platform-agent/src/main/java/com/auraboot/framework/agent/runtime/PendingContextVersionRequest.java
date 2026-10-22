package com.auraboot.framework.agent.runtime;

/**
 * Request for resolving the latest stable version of a record-scoped context.
 */
public record PendingContextVersionRequest(
        Long tenantId,
        String modelCode,
        String recordPid) {

    public boolean verifiable() {
        return tenantId != null && hasText(modelCode) && hasText(recordPid);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
