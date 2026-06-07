package com.auraboot.framework.eventpolicy.executor;

/**
 * Records executed action plans so a replayed event / retry does not re-run a side effect
 * (docs/2.md §8.2). Keyed by tenant + idempotency key. A DB-backed implementation persists to
 * {@code ab_drt_policy_exec_log}; an in-memory one is used in unit tests.
 */
public interface IdempotencyStore {

    /** True if this idempotency key already executed successfully for the tenant. */
    boolean alreadySucceeded(Long tenantId, String idempotencyKey);

    /** Record the outcome of an action execution (idempotency key is unique per tenant). */
    void record(Long tenantId, ActionExecutionResult result);
}
