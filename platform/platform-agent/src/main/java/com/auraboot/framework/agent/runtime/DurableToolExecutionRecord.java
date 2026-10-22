package com.auraboot.framework.agent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replayable result for a direct side-effecting tool execution.
 */
public record DurableToolExecutionRecord(
        String executionKey,
        DurableToolExecutionStatus status,
        String rawResult,
        Map<String, Object> result,
        String errorMessage,
        long updatedAt,
        DurableToolExecutionRequest request,
        int attemptCount,
        int maxAttempts,
        long nextRetryAt,
        boolean retryable,
        String compensationReason) {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    public DurableToolExecutionRecord {
        status = status == null ? DurableToolExecutionStatus.RUNNING : status;
        result = result == null ? Map.of() : new LinkedHashMap<>(result);
        attemptCount = Math.max(0, attemptCount);
        maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
    }

    public DurableToolExecutionRecord(String executionKey,
                                      DurableToolExecutionStatus status,
                                      String rawResult,
                                      Map<String, Object> result,
                                      String errorMessage,
                                      long updatedAt) {
        this(executionKey, status, rawResult, result, errorMessage, updatedAt,
                null, 0, DEFAULT_MAX_ATTEMPTS, 0L, false, null);
    }

    public static DurableToolExecutionRecord running(String executionKey) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.RUNNING,
                null,
                Map.of(),
                null,
                System.currentTimeMillis());
    }

    public static DurableToolExecutionRecord running(String executionKey,
                                                     DurableToolExecutionRequest request) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.RUNNING,
                null,
                Map.of(),
                null,
                System.currentTimeMillis(),
                request,
                1,
                DEFAULT_MAX_ATTEMPTS,
                0L,
                hasExternalIdempotencyKey(request),
                null);
    }

    public static DurableToolExecutionRecord succeeded(String executionKey,
                                                       String rawResult,
                                                       Map<String, Object> result) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.SUCCEEDED,
                rawResult,
                result,
                null,
                System.currentTimeMillis());
    }

    public static DurableToolExecutionRecord succeeded(String executionKey,
                                                       String rawResult,
                                                       Map<String, Object> result,
                                                       DurableToolExecutionRecord prior) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.SUCCEEDED,
                rawResult,
                result,
                null,
                System.currentTimeMillis(),
                prior == null ? null : prior.request(),
                prior == null ? 0 : prior.attemptCount(),
                prior == null ? DEFAULT_MAX_ATTEMPTS : prior.maxAttempts(),
                0L,
                prior != null && prior.retryable(),
                null);
    }

    public static DurableToolExecutionRecord failed(String executionKey,
                                                    String rawResult,
                                                    Map<String, Object> result,
                                                    String errorMessage) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.FAILED,
                rawResult,
                result,
                errorMessage,
                System.currentTimeMillis());
    }

    public DurableToolExecutionRecord withRecovery(DurableToolExecutionRequest request,
                                                   int attemptCount,
                                                   int maxAttempts,
                                                   long nextRetryAt,
                                                   boolean retryable,
                                                   String compensationReason) {
        return new DurableToolExecutionRecord(
                executionKey,
                status,
                rawResult,
                result,
                errorMessage,
                updatedAt,
                request,
                attemptCount,
                maxAttempts,
                nextRetryAt,
                retryable,
                compensationReason);
    }

    public DurableToolExecutionRecord retryRunning() {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.RUNNING,
                rawResult,
                result,
                errorMessage,
                System.currentTimeMillis(),
                request,
                attemptCount + 1,
                maxAttempts,
                0L,
                retryable,
                null);
    }

    public DurableToolExecutionRecord retryFailed(String rawResult,
                                                  Map<String, Object> result,
                                                  String errorMessage,
                                                  long nextRetryAt) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.FAILED,
                rawResult,
                result,
                errorMessage,
                System.currentTimeMillis(),
                request,
                attemptCount,
                maxAttempts,
                nextRetryAt,
                retryable,
                null);
    }

    public DurableToolExecutionRecord compensationRequired(String reason) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.COMPENSATION_REQUIRED,
                rawResult,
                result,
                errorMessage,
                System.currentTimeMillis(),
                request,
                attemptCount,
                maxAttempts,
                0L,
                retryable,
                reason);
    }

    public DurableToolExecutionRecord compensated(String rawResult, Map<String, Object> result) {
        return new DurableToolExecutionRecord(
                executionKey,
                DurableToolExecutionStatus.COMPENSATED,
                rawResult,
                result,
                null,
                System.currentTimeMillis(),
                request,
                attemptCount,
                maxAttempts,
                0L,
                retryable,
                compensationReason);
    }

    private static boolean hasExternalIdempotencyKey(DurableToolExecutionRequest request) {
        if (request == null || request.input() == null || request.input().isEmpty()) {
            return false;
        }
        return request.input().containsKey("idempotencyKey")
                || request.input().containsKey("idempotency_key")
                || request.input().containsKey("clientRequestId")
                || request.input().containsKey("client_request_id");
    }
}
