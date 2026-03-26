package com.auraboot.framework.bpm.chain.saga;

/**
 * Status enum for saga executions and steps.
 */
public enum SagaStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    SKIPPED
}
