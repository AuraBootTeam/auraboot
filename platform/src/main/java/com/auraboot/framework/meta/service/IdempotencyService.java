package com.auraboot.framework.meta.service;

import java.util.Map;

/**
 * Reusable idempotency service for command execution.
 * Provides check-and-record semantics to prevent duplicate processing.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface IdempotencyService {

    /**
     * Check if request was already processed.
     *
     * @param clientRequestId unique client request identifier
     * @param tenantId tenant scope
     * @return cached outcome if idempotent replay, null if first execution
     */
    Map<String, Object> checkIdempotency(String clientRequestId, Long tenantId);

    /**
     * Record successful execution outcome for future idempotent replay.
     *
     * @param clientRequestId unique client request identifier
     * @param commandCode command that was executed
     * @param payload original request payload
     * @param result execution result to cache
     * @param tenantId tenant scope
     */
    void recordOutcome(String clientRequestId, String commandCode,
                       Map<String, Object> payload, Map<String, Object> result,
                       Long tenantId);

    /**
     * Clean up expired idempotency records.
     *
     * @return number of records deleted
     */
    int cleanupExpired();
}
