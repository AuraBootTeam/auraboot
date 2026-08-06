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
     * Check a replay key inside one operation and require the canonical request payload to match.
     * A reused key with different intent is a conflict, never a cached success.
     */
    Map<String, Object> checkScopedIdempotency(
            String clientRequestId,
            String operationCode,
            Map<String, Object> payload,
            Long tenantId);

    /**
     * Atomically claim an operation-scoped request inside the caller's transaction.
     *
     * <p>The unique ledger row is inserted with {@code processing} status. A concurrent caller
     * blocks on the database uniqueness fence until the owner transaction commits or rolls back:
     * completed owners replay their outcome, while rolled-back owners leave no poison row and let
     * the waiter claim the request. A different canonical intent always conflicts.</p>
     *
     * @return cached outcome for a completed replay, or {@code null} when this transaction owns
     *         the newly-created claim and may execute the operation
     */
    Map<String, Object> claimScopedIdempotency(
            String clientRequestId,
            String operationCode,
            Map<String, Object> payload,
            Long tenantId);

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
     * Complete the transaction-owned scoped claim using the exact canonical intent used to claim
     * it. Missing/mismatched claims fail rather than silently committing an un-replayable write.
     */
    void recordScopedOutcome(String clientRequestId, String operationCode,
                             Map<String, Object> payload, Map<String, Object> result,
                             Long tenantId);

    /**
     * Clean up expired idempotency records.
     *
     * @return number of records deleted
     */
    int cleanupExpired();
}
